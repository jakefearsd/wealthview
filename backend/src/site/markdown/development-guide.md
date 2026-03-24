# Development Guide

## Prerequisites

| Tool | Minimum Version |
|---|---|
| Java (JDK) | 21 |
| Maven | 3.9 |
| Node.js | 20 |
| Docker | 24 (with Compose v2) |

---

## Quick Start (Recommended — Docker Compose)

The fastest path to a running app. Builds both frontend and backend into a single Docker image,
runs Flyway migrations, and seeds demo data automatically.

```bash
cd /path/to/wealthview
docker compose up --build -d
```

* **URL:** http://localhost:80
* **Credentials:** `demo@wealthview.local` / `demo123`

```bash
docker compose logs -f app     # tail application logs
docker compose down            # stop everything
docker compose exec db psql -U wv_app wealthview   # psql session
```

---

## Local Development (Hot-Reload)

For iterative backend or frontend work, run each tier separately.

**1. Start the database only:**

```bash
docker compose up -d db
```

**2. Backend (Spring Boot DevTools hot-swap):**

```bash
cd backend
mvn clean install -DskipTests   # first-time dependency download
mvn -pl wealthview-app spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile activates `DevDataInitializer`, which seeds a minimal data set with one tenant
and one admin user (`admin@wealthview.local` / `admin123`).

**3. Frontend (Vite HMR):**

```bash
cd frontend
npm install
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
    holdingsComputationService.recompute(TENANT_ID, ACCOUNT_ID, "AAPL");

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

```java
@WebMvcTest(AccountController.class)
class AccountControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean  AccountService accountService;

    @Test
    void createAccount_withValidBody_returns201() throws Exception {
        when(accountService.create(any(), any())).thenReturn(sampleResponse());
        mockMvc.perform(post("/api/v1/accounts")
                    .contentType(APPLICATION_JSON)
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

Testcontainers spins up a real PostgreSQL 16 container. Flyway migrations run automatically.
H2 is never used — it masks PostgreSQL-specific behaviour.

```java
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = NONE)
class AccountRepositoryIntegrationTest extends AbstractIntegrationTest {
    @Autowired AccountRepository repository;

    @Test
    void findByTenantIdAndId_returnsOnlyTenantData() {
        // ...
    }
}
```

`AbstractIntegrationTest` holds the shared `@Container` definition so the PostgreSQL container
is reused across test classes in the same JVM.

### End-to-End Integration Tests (wealthview-app)

```bash
cd backend
mvn verify -pl wealthview-app   # runs *IT.java via maven-failsafe-plugin
```

IT classes use `HttpClient5` + a fully started Spring Boot container (`@SpringBootTest(webEnvironment = RANDOM_PORT)`).
The `it` profile connects to a Testcontainers PostgreSQL instance and seeds minimal data.

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

## Code Quality Checks

```bash
# SpotBugs static analysis
cd backend && mvn spotbugs:check

# Checkstyle (Google style, 4-space indent override)
cd backend && mvn checkstyle:check

# PMD + CPD (copy-paste detection)
cd backend && mvn pmd:check pmd:cpd-check

# Mutation testing (projection + core)
cd backend && mvn -pl wealthview-projection pitest:mutationCoverage
```

---

## Maven Site Generation

Generate the full project documentation site:

```bash
cd backend

# Generate per-module sites + aggregate Javadoc
mvn site

# Stage all modules into a single navigable tree
mvn site:stage -DstagingDirectory=../target/site-deploy

# Open in browser
open ../target/site-deploy/index.html
```

The staged site includes:
* Custom overview pages (Architecture, Data Model, API Guide, Projection Engine, etc.)
* Per-module Javadoc (plus aggregate at root)
* JaCoCo coverage reports (per module + aggregate via wealthview-app)
* Surefire test result reports
* SpotBugs, Checkstyle, PMD reports
* Dependency and plugin version currency reports

---

## Frontend Development

```bash
cd frontend
npm run dev       # Vite dev server at :5173 with HMR
npm run build     # Production build → dist/
npm run test      # Vitest unit + component tests
npm run lint      # ESLint
```

TypeScript is enforced everywhere. No `any` types — define interfaces for all API responses.

---

## Adding a New Feature (Full Vertical Slice)

1. **Read PROJECT.md** to understand affected domains.
2. **Write a failing controller test** (`@WebMvcTest`) asserting the expected HTTP response.
3. **Create the controller method** — it fails because the service doesn't exist yet.
4. **Write a failing service unit test**, then implement the service.
5. **Write a repository integration test** (Testcontainers) if a new query is needed.
6. **Implement the repository method.**
7. **Wire everything** — controller test now passes.
8. **Write a Flyway migration** if the schema changed (`V<NNN+1>__description.sql`).
9. **Commit:** one logical change per commit, conventional commit format, body explaining why.

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

Push to GitHub only with explicit confirmation — do not push after every commit.
