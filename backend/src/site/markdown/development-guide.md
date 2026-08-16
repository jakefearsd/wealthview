# Development Guide

## Prerequisites

| Tool | Minimum Version |
|---|---|
| Java (JDK) | 25 — `backend/.sdkmanrc` pins `25.0.3-tem` |
| Maven | 3.9 |
| Node.js | 20.19 (CI runs 22; the release image builds on 24) |
| Docker | 24 (with Compose v2) |
| gitleaks | any — required by the `.githooks/pre-commit` hook |

The `mobile/` workspace stays on JDK 17 (`mobile/.sdkmanrc`); the pin above is backend-only.

Both Docker Compose and the local `dev` profile read credentials from a gitignored `.env` at the
repository root. Create it before anything else:

```bash
cp .env.example .env
```

---

## Quick Start (Recommended — `./wv up`)

The fastest path to a running app. Builds both frontend and backend into a single Docker image,
runs Flyway migrations, seeds demo data, and waits for the health check.

```bash
cd /path/to/wealthview
./wv up
```

* **URL:** http://localhost:80
* **Credentials:** `demo@wealthview.local` / `demo123`

```bash
./wv logs app     # tail application logs
./wv status       # container status + health probe
./wv down         # stop everything (data preserved)
./wv psql         # psql session
./wv help         # all subcommands
```

`./wv up` wraps `docker compose up --build -d`; the raw compose commands still work if you prefer
them. Either way the `db` service fails fast with `DB_PASSWORD must be set in .env` when `.env` is
missing, and the app additionally needs `JWT_SECRET`, `SUPER_ADMIN_PASSWORD` and
`MFA_ENCRYPTION_KEY`.

---

## Local Development (Hot-Reload)

For iterative backend or frontend work, run each tier separately.

**1. Start the database only:**

```bash
docker compose up -d db
```

PostgreSQL is published on **`localhost:5433`** (not 5432, so it does not collide with a native
install). That is exactly the URL baked into `application.yml`, so no extra configuration is needed.

**2. Backend (Spring Boot DevTools hot-swap):**

```bash
cd backend
mvn clean install -DskipTests   # first-time dependency download
mvn -pl wealthview-app spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile is **required** for a local run: the default profile declares `${DB_PASSWORD}` and
`${JWT_SECRET}` with no fallback (fail-loud, by design), and only `application-dev.yml` supplies the
`LOCAL_DEV_*` sentinels that let the app boot without exported environment variables.

`dev` also enables SQL logging, sets `com.wealthview` to `DEBUG`, and activates three initializers:

| Initializer | Profiles | What it seeds |
|---|---|---|
| `SuperAdminInitializer` | `dev`, `docker`, `prod` | `admin@wealthview.local` / `admin123` |
| `SampleDataInitializer` | `dev`, `docker` | demo tenant + `demo@wealthview.local` / `demo123` |
| `DevDataInitializer` | `dev` | `demo-admin@wealthview.local` / `demo123` and dev fixtures |

**3. Frontend (Vite HMR):**

The repository is an **npm workspaces monorepo** (`shared/`, `frontend/`, `mobile/`). Run
`npm install` **once at the repository root** — that is what creates the
`node_modules/@wealthview/shared` symlink both apps import from. Installing inside `frontend/` alone
will not wire the workspace dependency.

```bash
npm install    # at the REPOSITORY ROOT

cd frontend
npm run dev    # http://localhost:5173 — proxies /api to Spring Boot on :8080
```

---

## Test-Driven Development Workflow

WealthView follows strict Red-Green-Refactor. **Never write implementation code before a failing
test exists.**

### Unit Tests (JUnit 5 + Mockito + AssertJ)

```bash
cd backend
mvn -pl wealthview-core test
mvn test -Dtest=AccountServiceTest
mvn test -Dtest="HoldingsComputationServiceTest#recomputeHoldings_withBuyAndSell_calculatesNetQuantity"
```

Test class naming: `<ClassUnderTest>Test`.
Method naming: `methodUnderTest_stateOrInput_expectedResult`.

Structure with Arrange–Act–Assert separated by blank lines:

```java
@Test
void recomputeHoldings_withBuyAndSell_calculatesNetQuantity() {
    // Arrange
    var buy  = transactionOf(BUY,  10, new BigDecimal("100.00"));
    var sell = transactionOf(SELL,  3, new BigDecimal("150.00"));
    when(transactionRepository.findByAccountIdAndSymbol(ACCOUNT_ID, "AAPL"))
        .thenReturn(List.of(buy, sell));

    // Act
    holdingsComputationService.recomputeForAccountAndSymbol(account, tenant, "AAPL");

    // Assert
    var captor = ArgumentCaptor.forClass(HoldingEntity.class);
    verify(holdingRepository).save(captor.capture());
    assertThat(captor.getValue().getQuantity()).isEqualByComparingTo("7");
}
```

### Controller Tests (`@WebMvcTest` + MockMvc)

```bash
mvn -pl wealthview-api test -Dtest=AccountControllerTest
```

Controller tests use the composed annotation `@WealthViewControllerTest`
(`com.wealthview.api.testutil.WealthViewControllerTest`), which bundles `@WebMvcTest` with the
`SecurityConfig` / `GlobalExceptionHandler` / `JwtAuthenticationFilter` / `TestMetricsConfig`
imports and the placeholder security mocks that all 30-odd controller tests otherwise repeat
verbatim.

Note that on Spring Boot 4 the mock-bean annotation is **`@MockitoBean`**
(`org.springframework.test.context.bean.override.mockito.MockitoBean`) — `@MockBean` has been
removed. Jackson is Jackson 3, so `ObjectMapper` is imported from `tools.jackson.databind`.

```java
@WealthViewControllerTest(AccountController.class)
class AccountControllerTest {
    @Autowired    MockMvc mockMvc;
    @MockitoBean  AccountService accountService;

    @Test
    void createAccount_withValidBody_returns201() throws Exception {
        when(accountService.create(any(), any())).thenReturn(sampleResponse());
        mockMvc.perform(post("/api/v1/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        { "name": "Brokerage", "type": "brokerage", "institution": "Fidelity" }
                    """))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.type").value("brokerage"));
    }
}
```

### Repository / Integration Tests (Testcontainers)

```bash
mvn -pl wealthview-persistence test -Dtest=AccountRepositoryIntegrationTest
```

Testcontainers spins up a real PostgreSQL 16 container. H2 is never used — it masks
PostgreSQL-specific behaviour. Extend `com.wealthview.persistence.AbstractIntegrationTest`, which
owns the shared container (started once in a static initialiser, so it is reused across every test
class in the same JVM) and wires it in via `@DynamicPropertySource`:

```java
class AccountRepositoryIntegrationTest extends AbstractIntegrationTest {
    @Autowired AccountRepository repository;

    @Test
    void findByTenantIdAndId_returnsOnlyTenantData() {
        // ...
    }
}
```

The base class carries `@DataJpaTest`, `@AutoConfigureTestDatabase(replace = NONE)` and
`@ActiveProfiles("test")`. It also has to `@ImportAutoConfiguration(FlywayAutoConfiguration.class)`
explicitly: Boot 4's `@DataJpaTest` slice no longer pulls Flyway in the way Boot 3 did, and
migrations must build the schema before Hibernate's `ddl-auto=validate` runs.

### End-to-End Integration Tests (wealthview-app)

```bash
cd backend
mvn verify -pl wealthview-app   # runs *IT.java via maven-failsafe-plugin
```

Failsafe is configured with `<include>**/*IT.java</include>` and forces
`spring.profiles.active=it`. The 50 IT classes use HttpClient5 against a fully started Spring Boot
container (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) backed by a Testcontainers PostgreSQL
instance; `AbstractApiIntegrationTest` is the shared base and `DatabaseCleaner` truncates tenant
tables between tests.

To run everything except the Failsafe suite:

```bash
mvn verify -DskipITs
```

---

## Building

```bash
# Full build with tests
cd backend && mvn clean install

# Skip tests (e.g. before deploying)
cd backend && mvn clean install -DskipTests

# Single module
cd backend && mvn -pl wealthview-projection clean install

# Parallel build (fastest)
cd backend && mvn clean install -T 1C
```

---

## Code Quality Gates

All five tools below are bound to the **`verify`** phase and **fail the build**. They are gates, not
advisories — see [Code Quality](code-quality.html) for thresholds, config layout and the rules for
suppressing a finding.

```bash
cd backend
mvn verify -DskipITs          # all five gates, no Docker-backed Failsafe suite
mvn verify                    # gates + integration tests
```

Individual goals, when you want to iterate on one tool:

```bash
cd backend
mvn spotbugs:check            # config: backend/spotbugs-exclude.xml
mvn checkstyle:check          # config: backend/wealthview_checks.xml (4-space indent, 120 cols)
mvn pmd:check pmd:cpd-check   # config: backend/pmd-ruleset.xml
mvn jacoco:report             # coverage HTML (report is also bound to the test phase)
```

Note that `-Dpmd.rulesets` and `-Dspotbugs.excludeFilterFile` on the command line are silently
ignored — the POM `<configuration>` wins. Edit the tracked config file instead.

Mutation testing with PIT is **advisory** and is not bound to any phase:

```bash
cd backend
mvn -q test-compile org.pitest:pitest-maven:mutationCoverage -pl wealthview-core,wealthview-projection
```

Reports land in `<module>/target/pit-reports/`.

---

## Maven Site Generation

Generate the full project documentation site:

```bash
cd backend

# Generate per-module sites + aggregate Javadoc
mvn site

# Stage all modules into a single navigable tree
mvn site:stage

# Open in browser
xdg-open target/site-deploy/index.html    # Linux
open target/site-deploy/index.html        # macOS
```

`distributionManagement` already points the staging directory at
`${maven.multiModuleProjectDirectory}/target/site-deploy` (i.e. `backend/target/site-deploy`), so no
`-DstagingDirectory` flag is needed.

The staged site includes:

* Custom overview pages (Architecture, Data Model, API Guide, Projection Engine, Code Quality)
* Per-module Javadoc, plus an aggregate at the parent
* JaCoCo coverage reports per module
* Surefire test result reports
* SpotBugs, Checkstyle, PMD and CPD reports (non-failing in the `<reporting>` section)
* Dependency, plugin and property version-currency reports

---

## Frontend Development

```bash
npm install             # ONCE, at the repository root — wires the workspace symlinks

cd frontend
npm run dev             # Vite dev server at :5173 with HMR
npm run build           # tsc && vite build → dist/
npm run test            # Vitest unit + component tests
npm run test:coverage   # Vitest + v8 coverage, enforces the ratchet thresholds
npm run lint            # ESLint
npm run typecheck       # tsc --noEmit
```

Cross-workspace scripts from the repository root: `npm run test:shared`, `test:frontend`,
`test:mobile`, `test:all`, `typecheck:all`, `build:frontend`.

TypeScript is enforced everywhere. No `any` types — define interfaces for all API responses. Shared
wire types live in the `shared/` workspace and are re-exported through `frontend/src/types/`.

---

## Adding a New Feature (Full Vertical Slice)

1. **Read PROJECT.md** to understand affected domains.
2. **Write a failing controller test** (`@WealthViewControllerTest`) asserting the expected HTTP
   response.
3. **Create the controller method** — it fails because the service doesn't exist yet.
4. **Write a failing service unit test**, then implement the service.
5. **Write a repository integration test** (Testcontainers) if a new query is needed.
6. **Implement the repository method.**
7. **Wire everything** — controller test now passes.
8. **Write a Flyway migration** if the schema changed (`V<NNN+1>__description.sql` in
   `wealthview-persistence/src/main/resources/db/migration/`). Migrations are immutable once merged.
9. **Run `mvn verify -DskipITs`** so the quality gates catch style/complexity/coverage regressions
   before the commit rather than at tag time.
10. **Commit:** one logical change per commit, conventional commit format, body explaining why.

---

## Git Conventions

Commit format: `<type>(<scope>): <short summary>`

```
feat(core): add holdings recomputation on transaction create

When a transaction is created, HoldingsComputationService now recalculates
quantity and cost_basis for the affected account + symbol pair.
Manual overrides are preserved; a warning is logged when they conflict.
```

Scopes match Maven module names without `wealthview-`: `core`, `api`, `persistence`,
`import`, `projection`, `app`, `frontend`.

Every commit is scanned by the `.githooks/pre-commit` gitleaks hook. Enable it once per clone with
`./scripts/install-hooks.sh` (or `git config core.hooksPath .githooks`).

Push to GitHub only with explicit confirmation — do not push after every commit. CI
(`.github/workflows/`) runs only on version tags (`push: tags: ['v*']`) plus manual
`workflow_dispatch`, so a tag is what triggers the full verification pipeline.
</content>
