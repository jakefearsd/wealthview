# Load Test Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a k6-driven capacity/breaking-point + hot-path profiling harness for the WealthView backend that runs against a fully isolated local Docker stack seeded with synthetic multi-tenant data, with a first-class Prometheus + Grafana + Pyroscope observability stack and pprof export.

**Architecture:** A disposable Docker Compose stack (`loadtest/docker-compose.loadtest.yml`) brings up an isolated Postgres (port 5434), a dedicated app instance on a new `loadtest` Spring profile (datasource → that DB, Pyroscope Java agent attached via `JAVA_TOOL_OPTIONS`), k6, Prometheus, Grafana, Grafana Pyroscope, and postgres-exporter. A profile-gated `LoadTestDataSeeder` bulk-loads ~25 synthetic tenants. k6 remote-writes its metrics into Prometheus so client- and server-side metrics share a Grafana timeline. A `run.sh` orchestrates bootstrap → seed → load → export (pprof + Prometheus snapshot) → report.

**Tech Stack:** Spring Boot 3.5 / Java 21 (existing app + new seeder), Docker Compose, Grafana k6 (TS scenarios bundled with esbuild), Prometheus, Grafana, Grafana Pyroscope + Pyroscope Java agent (async-profiler), prometheuscommunity/postgres-exporter, bash.

**Reference spec:** `docs/superpowers/specs/2026-05-22-load-test-harness-design.md`

---

## File Structure

**Backend (additive, profile-gated — no behaviour change to existing profiles):**
- `backend/wealthview-app/src/main/resources/application-loadtest.yml` — `loadtest` profile: datasource → `loadtest-db`, seeding knobs, actuator exposure.
- `backend/wealthview-app/src/main/java/com/wealthview/app/config/LoadTestDataSeeder.java` — `@Profile("loadtest")` `ApplicationRunner`; bulk-seeds synthetic tenants via JDBC batch; writes manifest.
- `backend/wealthview-app/src/test/java/com/wealthview/app/config/LoadTestDataSeederTest.java` — verifies tenant/data shape (Testcontainers).

**Harness (new top-level `loadtest/` dir):**
- `loadtest/docker-compose.loadtest.yml` — the isolated stack.
- `loadtest/scenarios/lib/{config.ts,auth.ts,manifest.ts}` — shared k6 helpers.
- `loadtest/scenarios/{baseline.ts,hotpaths.ts,ramp.ts,soak.ts}` — k6 scenarios.
- `loadtest/scenarios/package.json`, `loadtest/scenarios/tsconfig.json` — `@types/k6` + esbuild bundling.
- `loadtest/observability/prometheus/prometheus.yml`
- `loadtest/observability/grafana/provisioning/datasources/datasources.yml`
- `loadtest/observability/grafana/provisioning/dashboards/dashboards.yml` + `dashboards/*.json`
- `loadtest/observability/pyroscope/` — agent jar fetch + server config notes.
- `loadtest/observability/pg-exporter/` — exporter wiring (env in compose).
- `loadtest/run.sh` — orchestrator.
- `loadtest/README.md`
- `.gitignore` — add `loadtest/results/` and `loadtest/scenarios/dist/` and `loadtest/scenarios/node_modules/`.

---

## Task 1: `loadtest` Spring profile config

**Files:**
- Create: `backend/wealthview-app/src/main/resources/application-loadtest.yml`

- [ ] **Step 1: Inspect the existing docker profile config for the shape to mirror**

Run: `sed -n '1,60p' backend/wealthview-app/src/main/resources/application-docker.yml`
Note the `spring.datasource`, `jpa`, `flyway`, and `management` keys and the `${VAR}` style.

- [ ] **Step 2: Create the loadtest profile config**

Create `backend/wealthview-app/src/main/resources/application-loadtest.yml`:

```yaml
# Load-test profile — used ONLY by the isolated loadtest Docker stack.
# Datasource points at the dedicated loadtest Postgres; never the dev DB.
spring:
  config:
    activate:
      on-profile: loadtest
  datasource:
    url: ${LOADTEST_DB_URL:jdbc:postgresql://loadtest-db:5432/wealthview_loadtest}
    username: ${LOADTEST_DB_USER:wv_loadtest}
    password: ${LOADTEST_DB_PASSWORD:loadtest_local_pw}
    hikari:
      maximum-pool-size: ${LOADTEST_HIKARI_MAX:20}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.batch_size: 200
      hibernate.order_inserts: true
      hibernate.order_updates: true
  flyway:
    enabled: true

# Knobs the seeder reads (see LoadTestDataSeeder).
loadtest:
  seed:
    tenants: ${LOADTEST_TENANTS:25}
    transactions-per-tenant: ${LOADTEST_TXNS_PER_TENANT:1500}
    manifest-path: ${LOADTEST_MANIFEST_PATH:/loadtest/results/manifest.json}
    password: ${LOADTEST_TENANT_PASSWORD:LoadTest-Fake-Pw-123}

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
  metrics:
    tags:
      application: wealthview-loadtest
```

- [ ] **Step 3: Verify it parses (profile activation, no startup against dev DB)**

Run: `mvn -f backend/pom.xml -q -pl wealthview-app -am test-compile`
Expected: BUILD SUCCESS (YAML is resource-only; this confirms the module still compiles). The profile is exercised end-to-end in Task 5.

- [ ] **Step 4: Commit**

```bash
git add backend/wealthview-app/src/main/resources/application-loadtest.yml
git commit -m "feat(app): add loadtest Spring profile config

Datasource points at the dedicated loadtest Postgres only; adds JDBC
batch tuning and the seeder knobs. Profile-gated — no effect on
dev/docker/prod.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: `LoadTestDataSeeder` — synthetic tenant data (TDD)

**Files:**
- Create: `backend/wealthview-app/src/main/java/com/wealthview/app/config/LoadTestDataSeeder.java`
- Test: `backend/wealthview-app/src/test/java/com/wealthview/app/config/LoadTestDataSeederTest.java`

**Context:** Mirror `SampleDataInitializer` (same package) for entity construction — read it first (`SampleDataInitializer.java`) and reuse its exact `new TenantEntity(...)`, `new AccountEntity(...)`, `new UserEntity(...)`, `new TransactionEntity(...)`, `new HoldingEntity(...)` constructor calls and repository save patterns. For the high-volume tables (transactions, holdings, prices) use `JdbcTemplate.batchUpdate` for speed. Verify exact column names against the Flyway migrations in `wealthview-persistence/src/main/resources/db/migration/` and the entity `@Column` annotations.

- [ ] **Step 1: Read the patterns to mirror**

Run: `sed -n '70,240p' backend/wealthview-app/src/main/java/com/wealthview/app/config/SampleDataInitializer.java`
Note the tenant/user/account/transaction construction and how holdings are recomputed. Note the `accounts` table columns and `transactions` table columns from `git grep -n "create table transactions" backend/wealthview-persistence/src/main/resources/db/migration/`.

- [ ] **Step 2: Write the failing test (Testcontainers, loadtest-shaped seed)**

Create `LoadTestDataSeederTest.java`. Extend the existing app integration test base if one is reusable (check `backend/wealthview-app/src/test/java` for `AbstractApiIntegrationTest`); otherwise use `@DataJpaTest` + Testcontainers per the persistence module pattern. The test drives the seeder with a small config (3 tenants, 50 txns each) and asserts the shape:

```java
package com.wealthview.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class LoadTestDataSeederTest extends AbstractApiIntegrationTest {

    @Autowired
    LoadTestDataSeeder seeder;
    @Autowired
    com.wealthview.persistence.repository.TenantRepository tenantRepository;
    @Autowired
    com.wealthview.persistence.repository.AccountRepository accountRepository;
    @Autowired
    com.wealthview.persistence.repository.TransactionRepository transactionRepository;
    @Autowired
    com.wealthview.persistence.repository.UserRepository userRepository;

    @Test
    void seed_threeTenants_createsTenantsAccountsAndTransactions() {
        var result = seeder.seed(3, 50);

        assertThat(result.tenantIds()).hasSize(3);
        // Each tenant gets a login user with the documented email pattern.
        assertThat(userRepository.findByEmail("loadtest-tenant-1@loadtest.local")).isPresent();
        // Each tenant has at least one account of each core type.
        var firstTenant = result.tenantIds().get(0);
        assertThat(accountRepository.findByTenant_Id(firstTenant)).hasSizeGreaterThanOrEqualTo(3);
        // Transactions were bulk-inserted at the requested volume (50 per tenant).
        assertThat(result.transactionsPerTenant()).isEqualTo(50);
        assertThat(transactionRepository.findByTenant_Id(firstTenant)).hasSize(50);
    }

    @Test
    void seed_isDeterministic_sameSymbolsAndCountsAcrossRuns() {
        var a = seeder.seed(2, 20);
        // A second seed into a clean DB (fresh container per test class) is not
        // run here; instead assert the per-tenant account count is stable/expected.
        assertThat(accountRepository.findByTenant_Id(a.tenantIds().get(0)))
                .extracting(com.wealthview.persistence.entity.AccountEntity::getType)
                .contains("brokerage", "ira", "roth");
    }
}
```

- [ ] **Step 3: Run the test to confirm it fails (no seeder yet)**

Run: `mvn -f backend/pom.xml -pl wealthview-app test -Dtest=LoadTestDataSeederTest`
Expected: FAIL — `LoadTestDataSeeder` does not exist / does not compile.

- [ ] **Step 4: Implement `LoadTestDataSeeder`**

Create `LoadTestDataSeeder.java`. Key requirements:
- `@Component @Profile("loadtest") @Order(2)` implementing `ApplicationRunner`.
- Constructor-injects the same repositories `SampleDataInitializer` uses, plus `JdbcTemplate`, `PasswordEncoder`, and binds `loadtest.seed.*` via `@Value` or a `@ConfigurationProperties` record.
- A public `SeedResult seed(int tenants, int txnsPerTenant)` method (so the test can call it directly) that `run(...)` delegates to using the configured knobs.
- Deterministic: `var rng = new java.util.Random(42L);` — all randomized choices draw from it.
- Per tenant `n` (1..N): create `TenantEntity`, a `UserEntity` (`loadtest-tenant-{n}@loadtest.local`, password = configured fake pw via `passwordEncoder.encode(...)`, role admin), accounts of types `brokerage`, `ira`, `roth`, `taxable`, `bank` (mirror `AccountEntity` constructor from SampleDataInitializer), and at least one projection scenario + guardrail + Roth config so compute endpoints have inputs (mirror however SampleDataInitializer/Dev seeds scenarios; if it doesn't, create the minimal scenario rows the projection endpoints require — verify required columns in the migrations).
- Bulk-insert `txnsPerTenant` transactions per tenant and the corresponding holdings + price history via `jdbcTemplate.batchUpdate(...)` with explicit column lists (verify columns against the migrations). Use a handful of real symbols from the seed price set (e.g. `AAPL, MSFT, VOO, VTI, BND, SCHD` — see project price seed data) so holdings are priceable.
- After inserting transactions, recompute holdings (reuse the same recompute call SampleDataInitializer uses, or compute net quantity/cost basis directly in the batch).
- Write the manifest: a `record SeedManifest(List<TenantManifest> tenants)` where `TenantManifest(UUID tenantId, String email, String password)`, serialized with Jackson to `loadtest.seed.manifest-path`. Create parent dirs.
- `SeedResult` is a record: `record SeedResult(List<UUID> tenantIds, int transactionsPerTenant) {}`.
- Log progress (`log.info("Seeded {} tenants, {} txns each in {} ms", ...)`).

Match real entity constructors/repository signatures exactly — compile-check against the persistence module.

- [ ] **Step 5: Run the test to confirm it passes**

Run: `mvn -f backend/pom.xml -pl wealthview-app test -Dtest=LoadTestDataSeederTest`
Expected: PASS (both tests).

- [ ] **Step 6: Run the full app module unit tests to confirm no regressions**

Run: `mvn -f backend/pom.xml -pl wealthview-app test`
Expected: BUILD SUCCESS — the new `@Profile("loadtest")` bean is inert under the test profile except where the test activates it.

- [ ] **Step 7: Commit**

```bash
git add backend/wealthview-app/src/main/java/com/wealthview/app/config/LoadTestDataSeeder.java \
        backend/wealthview-app/src/test/java/com/wealthview/app/config/LoadTestDataSeederTest.java
git commit -m "feat(app): add profile-gated LoadTestDataSeeder

Bulk-seeds N synthetic tenants (accounts, holdings, transactions, price
history, projection scenarios) via JDBC batch with a deterministic seed,
and writes a manifest of tenant logins for k6. @Profile(loadtest) only.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: k6 scenario project scaffold (TS + esbuild + types)

**Files:**
- Create: `loadtest/scenarios/package.json`
- Create: `loadtest/scenarios/tsconfig.json`
- Create: `loadtest/scenarios/lib/config.ts`
- Modify: `.gitignore`

- [ ] **Step 1: Create the k6 scenarios package**

Create `loadtest/scenarios/package.json`:

```json
{
  "name": "wealthview-loadtest-scenarios",
  "private": true,
  "type": "module",
  "scripts": {
    "build": "esbuild baseline.ts hotpaths.ts ramp.ts soak.ts --bundle --format=esm --platform=neutral --external:k6 --external:k6/* --outdir=dist"
  },
  "devDependencies": {
    "@types/k6": "^1.0.0",
    "esbuild": "^0.25.0",
    "typescript": "^5.9.0"
  }
}
```

Create `loadtest/scenarios/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "noEmit": true,
    "types": ["k6"],
    "esModuleInterop": true
  },
  "include": ["**/*.ts"]
}
```

- [ ] **Step 2: Create the shared config module**

Create `loadtest/scenarios/lib/config.ts`:

```ts
// Central knobs read from k6 env (-e KEY=VAL) with sane defaults.
export const BASE_URL = __ENV.BASE_URL || 'http://loadtest-app:8080';
export const MANIFEST_PATH = __ENV.MANIFEST_PATH || '/loadtest/results/manifest.json';
export const VUS_MAX = parseInt(__ENV.VUS_MAX || '200', 10);

export interface TenantManifest {
  tenantId: string;
  email: string;
  password: string;
}
export interface SeedManifest {
  tenants: TenantManifest[];
}
```

- [ ] **Step 3: Gitignore generated artifacts**

Add to `.gitignore`:

```
# Load test harness
loadtest/results/
loadtest/scenarios/dist/
loadtest/scenarios/node_modules/
```

- [ ] **Step 4: Install deps and verify the build tooling works**

Run: `cd loadtest/scenarios && npm install && npx tsc --noEmit`
Expected: install succeeds; `tsc` reports no errors (only `config.ts` exists so far).

- [ ] **Step 5: Commit**

```bash
git add loadtest/scenarios/package.json loadtest/scenarios/tsconfig.json \
        loadtest/scenarios/lib/config.ts loadtest/scenarios/package-lock.json .gitignore
git commit -m "chore(loadtest): scaffold k6 TypeScript scenario project

Adds the esbuild bundling setup, @types/k6, shared config module, and
gitignores generated/results artifacts.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: k6 auth + manifest libraries

**Files:**
- Create: `loadtest/scenarios/lib/manifest.ts`
- Create: `loadtest/scenarios/lib/auth.ts`

**Context:** The app uses httpOnly-cookie auth (`POST /api/v1/auth/login` sets `access_token`, `refresh_token`, `XSRF-TOKEN` cookies). k6's cookie jar handles cookies automatically per-VU; for state-changing requests the `X-XSRF-TOKEN` header must echo the `XSRF-TOKEN` cookie value. GET requests need only the cookie.

- [ ] **Step 1: Manifest loader**

Create `loadtest/scenarios/lib/manifest.ts`:

```ts
import { SeedManifest, TenantManifest, MANIFEST_PATH } from './config.ts';

// Loaded once at init (k6 reads the file from the mounted results volume).
const raw = open(MANIFEST_PATH);
const manifest: SeedManifest = JSON.parse(raw);

export function allTenants(): TenantManifest[] {
  return manifest.tenants;
}

// Pick a tenant for this VU, spreading VUs across tenants round-robin.
export function tenantForVu(vuId: number): TenantManifest {
  const t = manifest.tenants;
  return t[(vuId - 1) % t.length];
}
```

- [ ] **Step 2: Auth helper (login + authed request wrapper)**

Create `loadtest/scenarios/lib/auth.ts`:

```ts
import http, { RefinedResponse, ResponseType } from 'k6/http';
import { check } from 'k6';
import { BASE_URL, TenantManifest } from './config.ts';

export interface Session {
  xsrf: string;
}

// Logs in; k6's per-VU cookie jar retains the auth cookies automatically.
export function login(tenant: TenantManifest): Session {
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: tenant.email, password: tenant.password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } },
  );
  check(res, { 'login 200': (r) => r.status === 200 });

  const jar = http.cookieJar();
  const cookies = jar.cookiesForURL(BASE_URL);
  const xsrf = cookies['XSRF-TOKEN'] ? cookies['XSRF-TOKEN'][0] : '';
  return { xsrf };
}

export function authedGet(path: string, name: string): RefinedResponse<ResponseType | undefined> {
  return http.get(`${BASE_URL}${path}`, { tags: { name } });
}

export function authedPost(path: string, body: unknown, name: string, session: Session) {
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': session.xsrf },
    tags: { name },
  });
}
```

- [ ] **Step 3: Type-check**

Run: `cd loadtest/scenarios && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add loadtest/scenarios/lib/manifest.ts loadtest/scenarios/lib/auth.ts
git commit -m "feat(loadtest): k6 auth (cookie+XSRF) and manifest helpers

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: Isolated app+db stack (compose, no observability yet) + bootstrap verification

**Files:**
- Create: `loadtest/docker-compose.loadtest.yml` (db + app only in this task; observability added in Tasks 6-8)

**Context:** Reuse the existing root `Dockerfile` to build the app image (its final stage runs `java -jar app.jar`, EXPOSE 8080). Attach the loadtest profile via `SPRING_PROFILES_ACTIVE=loadtest`. The app container mounts `loadtest/results` so the seeder can write the manifest there.

- [ ] **Step 1: Create the compose file (db + app)**

Create `loadtest/docker-compose.loadtest.yml`:

```yaml
name: wealthview-loadtest

services:
  loadtest-db:
    image: postgres:16
    environment:
      POSTGRES_DB: wealthview_loadtest
      POSTGRES_USER: wv_loadtest
      POSTGRES_PASSWORD: loadtest_local_pw
    ports:
      - "5434:5432"
    volumes:
      - loadtest-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U wv_loadtest -d wealthview_loadtest"]
      interval: 3s
      timeout: 3s
      retries: 20

  loadtest-app:
    build:
      context: ..
      dockerfile: Dockerfile
    depends_on:
      loadtest-db:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: loadtest
      LOADTEST_DB_URL: jdbc:postgresql://loadtest-db:5432/wealthview_loadtest
      LOADTEST_DB_USER: wv_loadtest
      LOADTEST_DB_PASSWORD: loadtest_local_pw
      LOADTEST_TENANTS: ${LOADTEST_TENANTS:-25}
      LOADTEST_TXNS_PER_TENANT: ${LOADTEST_TXNS_PER_TENANT:-1500}
      LOADTEST_MANIFEST_PATH: /loadtest/results/manifest.json
      # JWT/security env the app requires — fake, loadtest-only values.
      JWT_SECRET: loadtest-only-fake-jwt-secret-min-32-characters-long
    volumes:
      - ./results:/loadtest/results
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health | grep -q UP || exit 1"]
      interval: 5s
      timeout: 5s
      retries: 40

volumes:
  loadtest-db-data:
```

> Verify which env vars the app actually requires at boot (JWT secret, etc.) by checking `application.yml` / `application-docker.yml` and `ProductionConfigValidator`; add any missing required `${VAR}` here with obviously-fake loadtest values (never a real-looking secret).

- [ ] **Step 2: Bring up db+app and confirm the seeder ran against the isolated DB**

Run:
```bash
docker compose -f loadtest/docker-compose.loadtest.yml up --build -d
# wait for health
until docker compose -f loadtest/docker-compose.loadtest.yml ps loadtest-app | grep -q healthy; do sleep 3; done
cat loadtest/results/manifest.json | head -c 300
```
Expected: app reaches healthy; `manifest.json` exists listing 25 tenants with `loadtest-tenant-N@loadtest.local` emails.

- [ ] **Step 3: Confirm isolation (dev DB on 5433 untouched, loadtest DB on 5434 populated)**

Run:
```bash
docker compose -f loadtest/docker-compose.loadtest.yml exec loadtest-db \
  psql -U wv_loadtest -d wealthview_loadtest -c "select count(*) from tenants;"
```
Expected: count == 25 (the synthetic tenants), confirming data is in the separate DB.

- [ ] **Step 4: Smoke an authenticated request against the loadtest app**

Run:
```bash
EMAIL=$(python3 -c "import json;print(json.load(open('loadtest/results/manifest.json'))['tenants'][0]['email'])")
PW=$(python3 -c "import json;print(json.load(open('loadtest/results/manifest.json'))['tenants'][0]['password'])")
curl -s -c /tmp/lt.txt -X POST http://localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" -o /dev/null -w "login %{http_code}\n"
curl -s -b /tmp/lt.txt -o /dev/null -w "accounts %{http_code}\n" http://localhost:8081/api/v1/accounts
```
(Map the app to host port `8081` by adding `ports: ["8081:8080"]` to `loadtest-app` for this manual check — keep it, it's harmless and useful.)
Expected: `login 200`, `accounts 200`.

- [ ] **Step 5: Tear down and commit**

```bash
docker compose -f loadtest/docker-compose.loadtest.yml down -v
git add loadtest/docker-compose.loadtest.yml
git commit -m "feat(loadtest): isolated app+db compose stack on a separate DB

Builds the app on the loadtest profile against a dedicated Postgres
(5434), seeds synthetic tenants on boot, and writes the manifest to a
mounted results volume. Verified isolated from the dev DB.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Prometheus + postgres-exporter

**Files:**
- Create: `loadtest/observability/prometheus/prometheus.yml`
- Modify: `loadtest/docker-compose.loadtest.yml` (add `prometheus`, `postgres-exporter`)

- [ ] **Step 1: Prometheus scrape config (app + pg-exporter; remote-write receiver for k6)**

Create `loadtest/observability/prometheus/prometheus.yml`:

```yaml
global:
  scrape_interval: 5s
  evaluation_interval: 5s

scrape_configs:
  - job_name: loadtest-app
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['loadtest-app:8080']
  - job_name: postgres
    static_configs:
      - targets: ['postgres-exporter:9187']
```

- [ ] **Step 2: Add the services to compose**

Add under `services:` in `loadtest/docker-compose.loadtest.yml`:

```yaml
  prometheus:
    image: prom/prometheus:latest
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --web.enable-remote-write-receiver
      - --web.enable-admin-api
      - --storage.tsdb.path=/prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./observability/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus

  postgres-exporter:
    image: prometheuscommunity/postgres-exporter:latest
    environment:
      DATA_SOURCE_NAME: "postgresql://wv_loadtest:loadtest_local_pw@loadtest-db:5432/wealthview_loadtest?sslmode=disable"
    depends_on:
      loadtest-db:
        condition: service_healthy
```

Add `prometheus-data:` under the top-level `volumes:`.

- [ ] **Step 3: Verify scraping**

Run:
```bash
docker compose -f loadtest/docker-compose.loadtest.yml up --build -d loadtest-db loadtest-app postgres-exporter prometheus
sleep 20
curl -s 'http://localhost:9090/api/v1/targets' | python3 -c "import sys,json;[print(t['labels']['job'],t['health']) for t in json.load(sys.stdin)['data']['activeTargets']]"
```
Expected: `loadtest-app up` and `postgres up`.

- [ ] **Step 4: Confirm a key app series is present**

Run: `curl -s 'http://localhost:9090/api/v1/query?query=hikaricp_connections_max' | grep -o '"value"' | head -1`
Expected: a non-empty result (HikariCP gauge is being scraped).

- [ ] **Step 5: Tear down and commit**

```bash
docker compose -f loadtest/docker-compose.loadtest.yml down -v
git add loadtest/observability/prometheus/prometheus.yml loadtest/docker-compose.loadtest.yml
git commit -m "feat(loadtest): Prometheus + postgres-exporter

Scrapes the loadtest app actuator and Postgres; enables the remote-write
receiver (for k6) and admin API (for run snapshots).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: Grafana Pyroscope + Java agent

**Files:**
- Create: `loadtest/observability/pyroscope/README.md` (agent fetch instructions)
- Modify: `loadtest/docker-compose.loadtest.yml` (add `pyroscope`; attach agent to `loadtest-app`)

**Context:** The Pyroscope Java agent (`pyroscope.jar`, from grafana/pyroscope releases) is attached via `JAVA_TOOL_OPTIONS` without rebuilding the app image. It uses async-profiler. The agent jar is fetched into `loadtest/observability/pyroscope/` by `run.sh` (Task 11) and mounted into the app container.

- [ ] **Step 1: Add the pyroscope server service**

Add under `services:`:

```yaml
  pyroscope:
    image: grafana/pyroscope:latest
    ports:
      - "4040:4040"
```

- [ ] **Step 2: Attach the agent to `loadtest-app`**

Add to `loadtest-app`:
- under `environment:`:
  ```yaml
      JAVA_TOOL_OPTIONS: "-javaagent:/pyroscope/pyroscope.jar"
      PYROSCOPE_APPLICATION_NAME: wealthview-loadtest
      PYROSCOPE_SERVER_ADDRESS: http://pyroscope:4040
      PYROSCOPE_PROFILING_INTERVAL: 10ms
      PYROSCOPE_PROFILER_EVENT: itimer
      PYROSCOPE_PROFILER_ALLOC: 512k
      PYROSCOPE_PROFILER_LOCK: 10ms
      PYROSCOPE_FORMAT: jfr
  ```
- under `volumes:`:
  ```yaml
      - ./observability/pyroscope:/pyroscope:ro
  ```
- add `pyroscope` to `loadtest-app.depends_on` (no condition needed).

- [ ] **Step 3: Document the agent fetch**

Create `loadtest/observability/pyroscope/README.md`:

```markdown
# Pyroscope Java agent

`run.sh` downloads `pyroscope.jar` here before bringing up the stack:

    curl -sSL -o loadtest/observability/pyroscope/pyroscope.jar \
      https://github.com/grafana/pyroscope-java/releases/latest/download/pyroscope.jar

The jar is mounted read-only into the app container and attached via
`JAVA_TOOL_OPTIONS=-javaagent:/pyroscope/pyroscope.jar`. It is gitignored.
```

Add `loadtest/observability/pyroscope/pyroscope.jar` to `.gitignore`.

- [ ] **Step 4: Verify profiles flow to Pyroscope**

Run:
```bash
curl -sSL -o loadtest/observability/pyroscope/pyroscope.jar \
  https://github.com/grafana/pyroscope-java/releases/latest/download/pyroscope.jar
docker compose -f loadtest/docker-compose.loadtest.yml up --build -d loadtest-db loadtest-app pyroscope
sleep 30
# generate a little CPU
EMAIL=$(python3 -c "import json;print(json.load(open('loadtest/results/manifest.json'))['tenants'][0]['email'])")
curl -s 'http://localhost:4040/api/apps' | grep -q wealthview-loadtest && echo "pyroscope sees the app"
```
Expected: `pyroscope sees the app` (the app registered as a profiling source).

- [ ] **Step 5: Tear down and commit**

```bash
docker compose -f loadtest/docker-compose.loadtest.yml down -v
git add loadtest/observability/pyroscope/README.md loadtest/docker-compose.loadtest.yml .gitignore
git commit -m "feat(loadtest): Grafana Pyroscope continuous JVM profiling

Attaches the Pyroscope Java agent to the loadtest app via
JAVA_TOOL_OPTIONS (no image change) for CPU/alloc/lock profiles; runs a
Pyroscope server for flame graphs and pprof export.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: Grafana with provisioned datasources + dashboards

**Files:**
- Create: `loadtest/observability/grafana/provisioning/datasources/datasources.yml`
- Create: `loadtest/observability/grafana/provisioning/dashboards/dashboards.yml`
- Create: `loadtest/observability/grafana/provisioning/dashboards/loadtest-overview.json`
- Modify: `loadtest/docker-compose.loadtest.yml` (add `grafana`)

- [ ] **Step 1: Datasource provisioning (Prometheus + Pyroscope)**

Create `loadtest/observability/grafana/provisioning/datasources/datasources.yml`:

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
  - name: Pyroscope
    type: grafana-pyroscope-datasource
    access: proxy
    url: http://pyroscope:4040
```

- [ ] **Step 2: Dashboard provider**

Create `loadtest/observability/grafana/provisioning/dashboards/dashboards.yml`:

```yaml
apiVersion: 1
providers:
  - name: loadtest
    type: file
    options:
      path: /etc/grafana/provisioning/dashboards
```

- [ ] **Step 3: Overview dashboard (k6 + app + db on one timeline)**

Create `loadtest/observability/grafana/provisioning/dashboards/loadtest-overview.json` — a dashboard with these panels (use Prometheus queries; `uid` stable):
- **k6 request rate**: `sum(rate(k6_http_reqs_total[1m]))`
- **k6 p95 latency by scenario**: `histogram_quantile(0.95, sum by (le, name) (rate(k6_http_req_duration_seconds_bucket[1m])))` *(adjust metric names to the k6 RW exporter's emitted names — verify after Task 9 with `curl 'http://localhost:9090/api/v1/label/__name__/values' | grep k6`)*
- **k6 error rate**: `sum(rate(k6_http_req_failed_total[1m])) / sum(rate(k6_http_reqs_total[1m]))`
- **HTTP server p95 by URI**: `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[1m])))`
- **HikariCP**: `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_max`
- **JVM heap + GC**: `jvm_memory_used_bytes{area="heap"}`, `rate(jvm_gc_pause_seconds_sum[1m])`
- **Postgres connections**: `pg_stat_activity_count` (or the exporter's connection series)

Write valid Grafana dashboard JSON (schemaVersion ≥ 39). Keep it to these panels; the implementer may lay them out in a simple grid. A minimal valid skeleton:

```json
{
  "uid": "loadtest-overview",
  "title": "Load Test — Overview",
  "schemaVersion": 39,
  "time": { "from": "now-15m", "to": "now" },
  "panels": [
    {
      "type": "timeseries", "title": "k6 request rate (req/s)",
      "datasource": { "type": "prometheus", "uid": "${DS_PROMETHEUS}" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "targets": [ { "expr": "sum(rate(k6_http_reqs_total[1m]))" } ]
    }
  ]
}
```

Expand with the remaining panels following the same object shape (one per query above). Use the provisioned datasource by name (`"datasource": {"type":"prometheus","uid":"Prometheus"}`) or the default datasource.

- [ ] **Step 4: Add Grafana to compose**

Add under `services:`:

```yaml
  grafana:
    image: grafana/grafana:latest
    ports:
      - "3001:3000"
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Admin
      GF_AUTH_DISABLE_LOGIN_FORM: "true"
    volumes:
      - ./observability/grafana/provisioning:/etc/grafana/provisioning:ro
    depends_on:
      - prometheus
      - pyroscope
```

- [ ] **Step 5: Verify Grafana provisions cleanly**

Run:
```bash
docker compose -f loadtest/docker-compose.loadtest.yml up -d prometheus pyroscope grafana
sleep 15
curl -s http://localhost:3001/api/datasources | python3 -c "import sys,json;[print(d['name'],d['type']) for d in json.load(sys.stdin)]"
curl -s http://localhost:3001/api/search?query=Load%20Test | grep -q "Load Test" && echo "dashboard provisioned"
```
Expected: Prometheus + Pyroscope datasources listed; `dashboard provisioned`.

- [ ] **Step 6: Tear down and commit**

```bash
docker compose -f loadtest/docker-compose.loadtest.yml down -v
git add loadtest/observability/grafana
git add loadtest/docker-compose.loadtest.yml
git commit -m "feat(loadtest): Grafana with provisioned datasources + overview dashboard

Anonymous-admin Grafana wired to Prometheus and Pyroscope, with a
pre-provisioned overview dashboard correlating k6 load, app HTTP/Hikari/JVM,
and Postgres on one timeline.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: k6 scenarios — baseline + hot paths

**Files:**
- Create: `loadtest/scenarios/baseline.ts`
- Create: `loadtest/scenarios/hotpaths.ts`

**Context:** Verify the exact request paths/payloads against the controllers before writing: dashboard is `/api/v1/dashboard/summary` and `/api/v1/dashboard/portfolio-history`; transactions/holdings are nested under `/api/v1/accounts/{id}/...`. The compute endpoints (projection run, guardrail optimize, Roth optimize) — find their controller mappings under `backend/wealthview-api/src/main/java/com/wealthview/api/controller/` and use the correct method + request body. Each VU operates within its assigned tenant only.

- [ ] **Step 1: Baseline read scenario**

Create `loadtest/scenarios/baseline.ts`:

```ts
import { sleep } from 'k6';
import { login, authedGet } from './lib/auth.ts';
import { tenantForVu } from './lib/manifest.ts';
import { check } from 'k6';
import exec from 'k6/execution';

export function setup() {
  return {}; // per-VU login happens in default() to keep cookies VU-scoped
}

export default function () {
  const tenant = tenantForVu(exec.vu.idInTest);
  login(tenant);

  const r1 = authedGet('/api/v1/accounts', 'accounts');
  check(r1, { 'accounts 200': (r) => r.status === 200 });
  authedGet('/api/v1/dashboard/summary', 'dashboard_summary');
  authedGet('/api/v1/dashboard/portfolio-history', 'portfolio_history');

  sleep(1);
}
```

- [ ] **Step 2: Hot-path scenario (the focus)**

Create `loadtest/scenarios/hotpaths.ts`. After confirming the real endpoints/bodies, implement scenario steps that POST/GET the projection run, the Monte Carlo guardrail optimize, and the Roth conversion optimize for the VU's tenant + one of its scenarios (read the scenario id from `GET /api/v1/projection/scenarios` or equivalent — verify the listing endpoint). Skeleton:

```ts
import { login, authedGet, authedPost } from './lib/auth.ts';
import { tenantForVu } from './lib/manifest.ts';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

export default function () {
  const tenant = tenantForVu(exec.vu.idInTest);
  const session = login(tenant);

  // 1. list scenarios for this tenant, pick one
  const list = authedGet('/api/v1/projection/scenarios', 'scenario_list');
  check(list, { 'scenarios 200': (r) => r.status === 200 });
  const scenarios = list.json() as Array<{ id: string }>;
  if (!scenarios || scenarios.length === 0) return;
  const sid = scenarios[0].id;

  // 2. deterministic projection run (verify the real path/verb/body)
  const proj = authedGet(`/api/v1/projection/scenarios/${sid}/run`, 'projection_run');
  check(proj, { 'projection 200': (r) => r.status === 200 });

  // 3. Monte Carlo guardrail optimize (verify path/body — likely a POST)
  authedPost(`/api/v1/projection/scenarios/${sid}/guardrail/optimize`, {}, 'mc_optimize', session);

  // 4. Roth conversion optimize (verify path/body)
  authedPost(`/api/v1/projection/scenarios/${sid}/roth/optimize`, {}, 'roth_optimize', session);

  sleep(1);
}
```

> The exact paths/verbs/bodies above are placeholders for the real mappings — replace each with what the controllers actually expose (confirmed in this step), and assert the real success status. Do not leave guessed paths in the committed file.

- [ ] **Step 3: Type-check + bundle**

Run: `cd loadtest/scenarios && npx tsc --noEmit && npm run build`
Expected: no type errors; `dist/baseline.js` and `dist/hotpaths.js` produced.

- [ ] **Step 4: Commit**

```bash
git add loadtest/scenarios/baseline.ts loadtest/scenarios/hotpaths.ts
git commit -m "feat(loadtest): k6 baseline-read and hot-path scenarios

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 10: k6 load profiles — ramp + soak

**Files:**
- Create: `loadtest/scenarios/ramp.ts`
- Create: `loadtest/scenarios/soak.ts`

- [ ] **Step 1: Ramp profile (find the breaking point)**

Create `loadtest/scenarios/ramp.ts`:

```ts
import { Options } from 'k6/options';
import baseline from './baseline.ts';
import hotpaths from './hotpaths.ts';
import { VUS_MAX } from './lib/config.ts';

export const options: Options = {
  scenarios: {
    read_baseline: {
      executor: 'ramping-vus',
      exec: 'baseline',
      startVUs: 0,
      stages: [
        { duration: '1m', target: Math.round(VUS_MAX * 0.1) },
        { duration: '2m', target: Math.round(VUS_MAX * 0.25) },
        { duration: '2m', target: Math.round(VUS_MAX * 0.5) },
        { duration: '2m', target: VUS_MAX },
        { duration: '1m', target: 0 },
      ],
    },
    hot_paths: {
      executor: 'ramping-vus',
      exec: 'hotpaths',
      startVUs: 0,
      stages: [
        { duration: '2m', target: Math.round(VUS_MAX * 0.1) },
        { duration: '4m', target: Math.round(VUS_MAX * 0.25) },
        { duration: '1m', target: 0 },
      ],
    },
  },
  // Recorded as observations, not pass/fail gates (abortOnFail: false).
  thresholds: {
    'http_req_duration{name:projection_run}': ['p(95)<2000'],
    'http_req_failed': ['rate<0.05'],
  },
};

export function baseline() { (baselineDefault as () => void)(); }
export function hotpaths() { (hotpathsDefault as () => void)(); }

// re-export the scenario bodies under named exec functions
import baselineDefault from './baseline.ts';
import hotpathsDefault from './hotpaths.ts';
```

> Note: k6 needs each `exec` function as a named export. If the re-export wrapper above is awkward after bundling, instead merge the baseline/hotpath bodies into named exports here (`export function baseline() {...}`) importing only the lib helpers. Verify the chosen form runs with `--smoke` in Task 11.

- [ ] **Step 2: Soak profile (steady hot-path characterization)**

Create `loadtest/scenarios/soak.ts`:

```ts
import { Options } from 'k6/options';
import { VUS_MAX } from './lib/config.ts';
export { default as hotpaths } from './hotpaths.ts';

export const options: Options = {
  scenarios: {
    hot_paths_soak: {
      executor: 'constant-vus',
      exec: 'hotpaths',
      vus: Math.max(5, Math.round(VUS_MAX * 0.15)),
      duration: __ENV.SOAK_DURATION || '15m',
    },
  },
  thresholds: { 'http_req_failed': ['rate<0.02'] },
};
```

- [ ] **Step 3: Type-check + bundle**

Run: `cd loadtest/scenarios && npx tsc --noEmit && npm run build`
Expected: no errors; `dist/ramp.js`, `dist/soak.js` produced.

- [ ] **Step 4: Commit**

```bash
git add loadtest/scenarios/ramp.ts loadtest/scenarios/soak.ts
git commit -m "feat(loadtest): k6 ramp (breaking-point) and soak load profiles

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 11: `run.sh` orchestrator

**Files:**
- Create: `loadtest/run.sh` (executable)
- Modify: `loadtest/docker-compose.loadtest.yml` (add the `k6` service)

- [ ] **Step 1: Add the k6 service to compose (Prometheus remote-write output)**

Add under `services:`:

```yaml
  k6:
    image: grafana/k6:latest
    profiles: ["k6"]   # only run via `docker compose run`, not `up`
    environment:
      K6_PROMETHEUS_RW_SERVER_URL: http://prometheus:9090/api/v1/write
      K6_PROMETHEUS_RW_TREND_STATS: "p(50),p(95),p(99)"
      BASE_URL: http://loadtest-app:8080
      MANIFEST_PATH: /loadtest/results/manifest.json
    volumes:
      - ./scenarios/dist:/scripts:ro
      - ./results:/loadtest/results
    depends_on:
      loadtest-app:
        condition: service_healthy
```

- [ ] **Step 2: Write the orchestrator**

Create `loadtest/run.sh` (`chmod +x`):

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
COMPOSE="docker compose -f docker-compose.loadtest.yml"

PROFILE="ramp"; KEEP=1; SMOKE=0
export LOADTEST_TENANTS="${LOADTEST_TENANTS:-25}"
export VUS_MAX="${VUS_MAX:-200}"
while [[ $# -gt 0 ]]; do case "$1" in
  --profile) PROFILE="$2"; shift 2;;
  --vus-max) export VUS_MAX="$2"; shift 2;;
  --tenants) export LOADTEST_TENANTS="$2"; shift 2;;
  --smoke) SMOKE=1; PROFILE="ramp"; shift;;
  --teardown) KEEP=0; shift;;
  --keep) KEEP=1; shift;;
  *) echo "unknown arg $1"; exit 1;;
esac; done

TS="$(date +%Y%m%d-%H%M%S)"; OUT="results/$TS"; mkdir -p "$OUT" results

echo "==> Fetching Pyroscope agent (if missing)"
[[ -f observability/pyroscope/pyroscope.jar ]] || \
  curl -sSL -o observability/pyroscope/pyroscope.jar \
    https://github.com/grafana/pyroscope-java/releases/latest/download/pyroscope.jar

echo "==> Bringing up isolated stack (wiping prior volume)"
$COMPOSE down -v >/dev/null 2>&1 || true
$COMPOSE up --build -d loadtest-db loadtest-app prometheus postgres-exporter pyroscope grafana

echo "==> Waiting for app + observability to be healthy"
until $COMPOSE ps loadtest-app | grep -q healthy; do sleep 3; done
curl -sf http://localhost:9090/-/ready >/dev/null
echo "    Grafana: http://localhost:3001   Prometheus: http://localhost:9090   Pyroscope: http://localhost:4040"

echo "==> Building k6 scenarios"
( cd scenarios && npm install --silent && npm run build --silent )

START_EPOCH=$(date +%s)
if [[ "$SMOKE" == "1" ]]; then
  echo "==> SMOKE run (1 VU, 30s)"
  $COMPOSE run --rm -e VUS_MAX=1 k6 run --vus 1 --duration 30s -o experimental-prometheus-rw \
    --summary-export=/loadtest/results/$TS/k6-summary.json /scripts/ramp.js
else
  echo "==> k6 $PROFILE run (VUS_MAX=$VUS_MAX)"
  $COMPOSE run --rm k6 run -o experimental-prometheus-rw \
    --summary-export=/loadtest/results/$TS/k6-summary.json /scripts/$PROFILE.js | tee "$OUT/k6-stdout.txt"
fi
END_EPOCH=$(date +%s)

echo "==> Exporting pprof CPU profile for the run window"
# Pyroscope render API → pprof (CPU). Adjust query to the app name + cpu profile type.
curl -sG 'http://localhost:4040/pyroscope/render' \
  --data-urlencode "query=process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"wealthview-loadtest\"}" \
  --data-urlencode "from=${START_EPOCH}" --data-urlencode "until=${END_EPOCH}" \
  --data-urlencode "format=pprof" -o "$OUT/cpu.pprof" || echo "   (pprof export skipped — verify Pyroscope query)"

echo "==> Snapshotting Prometheus TSDB"
SNAP=$(curl -s -XPOST http://localhost:9090/api/v1/admin/tsdb/snapshot | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['name'])" 2>/dev/null || true)
[[ -n "${SNAP:-}" ]] && $COMPOSE cp prometheus:/prometheus/snapshots/$SNAP "$OUT/prometheus-snapshot" || echo "   (prom snapshot skipped)"

cp results/manifest.json "$OUT/" 2>/dev/null || true

echo "==> Generating REPORT.md"
python3 gen_report.py "$OUT" "$PROFILE" "$VUS_MAX" "$START_EPOCH" "$END_EPOCH" > "$OUT/REPORT.md" || \
  echo "# Load test $TS ($PROFILE, VUS_MAX=$VUS_MAX)" > "$OUT/REPORT.md"

echo "==> Done. Artifacts in $OUT"
echo "    Explore live: http://localhost:3001 (Grafana)"
if [[ "$KEEP" == "0" ]]; then echo "==> Tearing down"; $COMPOSE down -v; fi
```

- [ ] **Step 3: Minimal report generator**

Create `loadtest/gen_report.py` that reads `k6-summary.json` and queries Prometheus for the peak `hikaricp_connections_active` vs `hikaricp_connections_max` and prints a Markdown report (run params, k6 p95/p99/error-rate per scenario, peak pool utilisation, a note on the saturating resource, and links to the Grafana dashboard + the `cpu.pprof`). Keep it dependency-free (stdlib `json`, `urllib`).

- [ ] **Step 4: Smoke-validate the whole harness end to end**

Run: `loadtest/run.sh --smoke`
Expected: stack builds and goes healthy; the 1-VU k6 run completes with `login`/`accounts`/scenario checks passing; `results/<ts>/k6-summary.json` and `REPORT.md` are produced; Grafana URL printed. Fix any wiring issues surfaced (k6 metric names in the dashboard, Pyroscope query for pprof, scenario exec-function form) here.

- [ ] **Step 5: Tear down and commit**

```bash
docker compose -f loadtest/docker-compose.loadtest.yml down -v
chmod +x loadtest/run.sh
git add loadtest/run.sh loadtest/gen_report.py loadtest/docker-compose.loadtest.yml
git commit -m "feat(loadtest): run.sh orchestrator + report generator

One command: wipe+up isolated stack, seed, build+run k6 (Prometheus
remote-write), export pprof + Prometheus snapshot, generate REPORT.md.
Validated with --smoke.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 12: README + full ramp validation

**Files:**
- Create: `loadtest/README.md`

- [ ] **Step 1: Write the README**

Create `loadtest/README.md` documenting: prerequisites (Docker, Node for the bundle step), `loadtest/run.sh` usage + flags (`--profile ramp|soak`, `--vus-max`, `--tenants`, `--smoke`, `--keep`/`--teardown`), how to read the Grafana dashboards (URL, what each panel means), how to open the flame graph in Grafana and the exported `cpu.pprof` (`go tool pprof -http=:8080 results/<ts>/cpu.pprof`), how to interpret `REPORT.md`, and the isolation guarantees (separate DB on 5434, never touches the dev DB).

- [ ] **Step 2: Run a real (short) ramp to validate breaking-point output**

Run: `VUS_MAX=80 loadtest/run.sh --profile ramp`
Expected: completes; `REPORT.md` shows per-scenario p95/p99 + peak HikariCP utilisation; Grafana shows the k6 + app + db timeline; `cpu.pprof` is non-empty and opens in pprof. (Use a modest `VUS_MAX` for the validation run; the host is single-node.)

- [ ] **Step 3: Tear down and commit**

```bash
docker compose -f loadtest/docker-compose.loadtest.yml down -v
git add loadtest/README.md
git commit -m "docs(loadtest): harness usage, dashboards, and pprof guide

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Final acceptance

- [ ] `loadtest/run.sh` (defaults) brings up an isolated stack on a separate DB + full observability, seeds 25 synthetic tenants, runs the ramp, and produces `results/<ts>/` with `k6-summary.json`, `cpu.pprof`, `prometheus-snapshot`, `manifest.json`, and `REPORT.md` — one command, dev DB untouched (verify `docker compose ... exec loadtest-db psql ... "select count(*) from tenants"` == 25 while the dev stack is unaffected).
- [ ] Grafana (`:3001`) shows k6 + app (HTTP/HikariCP/JVM) + Postgres on one timeline, plus Pyroscope flame graphs.
- [ ] `mvn -f backend/pom.xml -pl wealthview-app test` passes (seeder test green; existing tests unaffected).
- [ ] No real-looking secrets committed (loadtest JWT/passwords are obvious fakes); `loadtest/results/`, `dist/`, `node_modules/`, `pyroscope.jar` are gitignored.
- [ ] All work committed on `main`; nothing pushed unless the user asks.
```
