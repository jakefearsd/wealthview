# wealthview-app

The assembly module. Pulls all other modules together into a deployable Spring Boot fat JAR.
This is where `@SpringBootApplication` lives, application profiles are defined, integration
config and schedulers are wired, and the Docker / production deployment is rooted.

It declares three module dependencies — `wealthview-api`, `wealthview-import`, and
`wealthview-projection` — plus the observability stack (Actuator, Micrometer/Prometheus,
OpenTelemetry tracing bridge, logstash-logback-encoder). `wealthview-core` and
`wealthview-persistence` arrive transitively.

---

## Main Class

`com.wealthview.app.WealthViewApplication` — the standard Spring Boot entry point.
No application logic; just `SpringApplication.run(...)`.

---

## Application Profiles

| Profile | Activation | Purpose |
|---|---|---|
| `dev` | `-Dspring.profiles.active=dev` | Local development; demo + dev seed data; verbose SQL logging |
| `docker` | `SPRING_PROFILES_ACTIVE: docker` in `docker-compose.yml` | Docker Compose deployment; demo data seed |
| `prod` | `SPRING_PROFILES_ACTIVE: prod` in `docker-compose.prod.yml` | Production; no demo seed; JSON logging; stricter settings |
| `loadtest` | Explicit activation | Throwaway synthetic-data stack; anonymous Prometheus scraping permitted |
| `it` | Set by Maven Failsafe (`spring.profiles.active=it`) | Integration tests; Testcontainers PostgreSQL; no seed data |

Configuration files in `src/main/resources`: `application.yml`, `application-dev.yml`,
`application-docker.yml`, `application-prod.yml`, `application-loadtest.yml`, plus
`logback-spring.xml`. `application-it.yml` lives in `src/test/resources`.

`application.yml` also holds the cross-cutting defaults: HikariCP (max pool 20, min idle 5,
10 s connection timeout, 5 min idle timeout, 10 min max lifetime), `ddl-auto: validate`,
`open-in-view: false`, and Jackson's global `property-naming-strategy: SNAKE_CASE`.

Secrets are always `${VAR}` references — production YAML carries no fallbacks, so a missing
variable fails loudly at startup. `ProductionConfigValidator` additionally refuses to boot
`prod` on known-default JWT secrets.

---

## Application Initializers

### SuperAdminInitializer (`dev`, `docker`, `prod`)

Checks whether the super-admin user (email from `app.super-admin.email`) exists. If not,
creates the super-admin tenant and user with `app.super-admin.password`. This guarantees a
usable entry point on first deployment without any manual setup.

### SystemConfigInitializer (all profiles)

A `CommandLineRunner` that seeds required `system_config` keys so runtime-configurable settings
have defined defaults.

### SampleDataInitializer (`dev` + `docker`)

Creates the demo tenant with `demo@wealthview.local` and three accounts — a Fidelity brokerage,
a Fidelity 401(k), and a Chase checking account — each with sample transactions.

### DevDataInitializer (`dev` only)

Creates a richer local dev tenant under `demo-admin@wealthview.local`: five accounts
(brokerage, IRA, 401k, Roth, bank) plus rental properties with income, expense, and valuation
history.

### DemoDataSeeder

Not an initializer — the shared seeding primitives (`TxnSpec`, `IncomeSpec`, `ExpenseSpec`)
that both initializers above build fixtures for. Neither one owns the mechanics of persisting
an account with its transactions or a property with its cash flows.

### LoadTestDataSeeder (`loadtest` only)

Bulk synthetic data generation for the isolated load-test stack.

---

## Integration Configuration

| Class | Responsibility |
|---|---|
| `FinnhubConfig` | Finnhub API key, base URL, rate-limit settings; the `PriceFeedClient` / `SplitDetectionClient` beans are conditional on a non-empty key |
| `YahooConfig` | Yahoo Finance historical-price client wiring |
| `ZillowConfig` | Zillow scraper enable flag, timeout, rate limit, sync cron |
| `HttpClientFactory` | Shared outbound HTTP client construction |
| `MetricsConfig` | Micrometer registry customisation and common tags |
| `ProductionConfigValidator` | Fails startup on unsafe production configuration |

---

## Scheduling

`SchedulingConfig` enables Spring's `@Scheduled` task execution with a configurable thread pool.

| Job | Schedule | Owner |
|---|---|---|
| Daily price sync | `app.finnhub.sync-cron`, default `0 0 18 * * MON-FRI` America/New_York | `PriceSyncService` (`wealthview-core`) — owns its own `@Scheduled`; `PriceSyncSchedulingTest` (here) fails the build if a second bean schedules it too |
| Historical price backfill | Event-driven on `NewHoldingCreatedEvent`; 2-year window | `PriceSyncService` (`wealthview-core`) |
| Stock split sync | `app.stock-splits.sync-cron`, default `0 0 2 * * *` America/New_York | `StockSplitSyncService` (`wealthview-core`) |
| Stock split backfill | Once, async after `ContextRefreshedEvent` | `StockSplitBackfillRunner` (`wealthview-core`) |
| Zillow valuation sync | `app.zillow.sync-cron`, default `0 0 6 * * SUN` | `PropertyValuationSyncService` (`wealthview-core`) |

---

## Health & Observability

`com.wealthview.app.health` contributes `FinnhubHealthIndicator` and `ZillowHealthIndicator`,
surfacing integration reachability through Actuator.

`/actuator/health` is public; every other Actuator endpoint requires `SUPER_ADMIN` (the sole
exception is the `loadtest` profile, which permits anonymous scraping of
`/actuator/prometheus` and `/actuator/metrics` on that isolated stack). Metrics go to
Prometheus via `micrometer-registry-prometheus`; traces via the OpenTelemetry bridge and OTLP
exporter; production logs are JSON through `logstash-logback-encoder`.

One caveat worth remembering: `@Timed` and `@Observed` must not share a meter name on the same
method — `@Observed` always registers a meter, and the collision breaks the entire Prometheus
scrape, not just that one metric.

---

## Integration Tests (Maven Failsafe)

Maven Failsafe runs all `**/*IT.java` classes as post-package integration tests with
`spring.profiles.active=it`. There are roughly **50** of them.

```bash
cd backend
mvn verify -pl wealthview-app
```

IT classes extend `AbstractApiIntegrationTest`, which owns a singleton Testcontainers
PostgreSQL container, and drive real HTTP against a fully started application. Apache
HttpClient5 is used as the request factory to sidestep a JDK HTTP client streaming bug on 401
responses. `DatabaseCleaner` truncates all tenant tables `CASCADE` in `@BeforeEach`, and
`AuthHelper` separates transactional data setup from the HTTP login call (setup must commit
before login).

Coverage spans the full request-to-database round trip: auth (cookie and bearer transports),
MFA, session management, tenant isolation (including the Hibernate-filter backstop and
cross-tenant fuzzing), rate-limit headers, stock split sync and backfill, and Flyway migration
correctness. Shared harness classes live in `com.wealthview.app.it`
(`AbstractApiIntegrationTest`, `AuthHelper`, `DatabaseCleaner`); reusable fixtures live in
`com.wealthview.app.it.testutil` (`ApiClient`, `TestDataHelper`, `HttpFixtures`,
`SplitTestSupport`, and the queueing split-detection stub).

---

## JaCoCo Aggregate Coverage

`wealthview-app` generates the cross-module aggregate coverage report via the
`report-aggregate` goal. After running `mvn verify`, the report is at:

```
target/site/jacoco-aggregate/index.html
```

This report spans all source modules and gives the truest picture of end-to-end test coverage,
including code paths exercised only through integration tests. The per-module gates that
actually fail the build live in `wealthview-core` (90% line / 0.83 branch),
`wealthview-projection` (90% / 0.84), `wealthview-api` (80% / 0.85), and `wealthview-import`
(80% / 0.71).

---

## Docker Deployment

The `Dockerfile` at the repository root performs a multi-stage build (all base images pinned by
digest):

1. **Stage 1 (frontend):** `node:24-alpine` — `npm ci` across the `frontend` and `shared`
   workspaces, then `npm run build --workspace=frontend` → `dist/`
2. **Stage 2 (backend):** `maven:3.9-eclipse-temurin-25` — packages the fat JAR
3. **Stage 3 (runtime):** `eclipse-temurin:25-jre-alpine` — copies the JAR + `dist/` into a
   single image, exposing port 8080

The fat JAR serves the React `dist/` as static resources from `/` and the API under `/api/v1/`.

Day-to-day operation goes through the `./wv` command surface rather than raw compose:

```bash
./wv up          # build & start; waits for health
./wv status      # container status + health probe
./wv logs app    # tail logs
./wv down        # stop, preserving data
```

---

## Static Resource Serving

Spring Boot serves the compiled React SPA from `classpath:static/`, with `SpaWebConfig` and
`SpaPathResourceResolver` (in `wealthview-api`) forwarding unknown non-API paths to
`index.html` so client-side routing survives a page refresh. The final container image has no
Nginx — the Spring Boot app itself serves both the API and the frontend from port 8080, with
Nginx or a load balancer optionally placed in front for TLS termination.
