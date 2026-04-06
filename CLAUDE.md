# CLAUDE.md — WealthView

## Project Overview

WealthView is a self-hosted, multi-tenant personal finance app (investments, rental properties, retirement projections). See PROJECT.md for full architecture, data model, and feature roadmap.

**Tech stack:** Java 21+ / Spring Boot 3.3+ / Maven multi-module backend, React 18+ / Vite frontend, PostgreSQL 16+, Docker Compose deployment.

**Monorepo layout:**
- `backend/` — Maven multi-module: wealthview-api, wealthview-core, wealthview-persistence, wealthview-import, wealthview-projection, wealthview-app
- `frontend/` — React + Vite SPA

---

## Development Workflow — TDD First

Every backend feature follows Red-Green-Refactor. No exceptions.

### Sequence for a new feature:
1. **Understand** — Read the relevant section of PROJECT.md. Identify affected modules.
2. **Write failing tests first** — Start with a unit test in the appropriate module. The test MUST fail (red) before writing any implementation.
3. **Implement the minimum** — Write only enough production code to make the test pass (green).
4. **Refactor** — Clean up duplication, improve naming, extract methods. All tests must still pass.
5. **Expand** — Add edge-case tests, error-path tests, then implement those paths.
6. **Integration test** — For controller or repository work, add a Testcontainers-backed integration test.
7. **Commit** — One logical change per commit. See Git Conventions below.

### When adding a new endpoint (full vertical slice):
1. Write a controller test (MockMvc) asserting the expected HTTP response — it will fail.
2. Create the controller method with the correct mapping — it will fail because the service doesn't exist.
3. Write a service unit test — it will fail.
4. Implement the service — tests pass.
5. Write a repository integration test (Testcontainers) if new queries are needed.
6. Implement the repository method.
7. Wire everything together; the controller test passes.
8. Add a Flyway migration if the schema changed.

### IMPORTANT: Never write implementation code without a corresponding failing test first.
If you catch yourself writing production code first, stop, delete it, write the test, then re-implement.

---

## Java Coding Conventions (Java 21+)

### Modern Idioms — Use Them
- **Records** for all DTOs, request/response objects, and value objects. Only use classes when you need mutability or inheritance.
- **Sealed interfaces** for type hierarchies with a known set of subtypes (e.g., `sealed interface ImportSource permits CsvSource, OfxSource`).
- **Pattern matching** with `instanceof` and switch expressions. Prefer `switch` over if-else chains for type dispatch.
- **`var`** when the type is obvious from the right-hand side: `var accounts = accountRepository.findAll()`. Do NOT use `var` when the type is ambiguous.
- **Text blocks** (`"""`) for multi-line strings (SQL fragments, test fixtures, error messages).
- **Stream API** when it improves readability. Prefer simple for-loops over deeply nested streams with multiple flatMaps.
- **Optional** — Return `Optional<T>` from finder methods. Never pass `Optional` as a method parameter. Never call `.get()` without checking; use `.orElseThrow()` with a meaningful exception.

### Null Handling
- **Never return null** from a public method. Return `Optional<T>`, an empty collection, or throw a domain exception.
- Use `@Nullable` / `@NonNull` annotations (from `org.springframework.lang`) on method parameters when interfacing with framework code.
- Validate inputs at service boundaries with `Objects.requireNonNull()` or Spring's `@Valid`.

### Naming
- **Packages:** `com.wealthview.<module>.<layer>` (e.g., `com.wealthview.core.account`).
- **Classes:** PascalCase. Suffix controllers with `Controller`, services with `Service`, repositories with `Repository`, DTOs with `Request`/`Response`/`Dto`.
- **Methods:** camelCase. Prefix boolean methods with `is`, `has`, `can`. Repository finders: `findByTenantIdAndSymbol`.
- **Constants:** `UPPER_SNAKE_CASE` as `static final` fields.
- **Test classes:** `<ClassUnderTest>Test` for unit tests, `<ClassUnderTest>IntegrationTest` for integration tests.

### Formatting
- 4-space indentation (no tabs).
- Max line length: 120 characters.
- Braces on same line (K&R style).
- One blank line between methods; no multiple consecutive blank lines.
- Imports: no wildcard imports. Organize: java.*, jakarta.*, org.*, com.*, static imports last.

### Records vs Classes Decision Guide
| Use a Record when... | Use a Class when... |
|---|---|
| Immutable data carrier (DTO, value object) | Mutable state required |
| No inheritance needed | Part of a JPA @Entity hierarchy |
| All fields set at construction time | Needs builder pattern with many optional fields |
| Equality defined by all fields | Custom equals/hashCode logic |

### Exception Handling
- Define domain exceptions in `wealthview-core`: `EntityNotFoundException`, `DuplicateImportException`, `TenantAccessDeniedException`, etc.
- Controllers should NOT catch exceptions directly. Use a `@RestControllerAdvice` global exception handler in `wealthview-api`.
- Return the standard error envelope: `{ "error": "NOT_FOUND", "message": "...", "status": 404 }`.

### Dependency Injection
- **Constructor injection only.** Never use `@Autowired` on fields.
- With a single constructor, `@Autowired` is implicit — omit it.

### Logging
- Use SLF4J with Logback (Spring Boot default).
- Use parameterized messages: `log.info("Account {} created for tenant {}", accountId, tenantId)`. Never concatenate strings in log statements.
- Log levels: `ERROR` for failures requiring attention, `WARN` for recoverable issues, `INFO` for significant business events (user login, import complete), `DEBUG` for diagnostic detail.
- Structured JSON logging in production via Logback configuration. Plain text in dev.
- Never log sensitive data (passwords, JWT tokens, full account numbers).

---

## Testing Standards

### Unit Tests
- **Framework:** JUnit 5 + Mockito.
- **Naming:** `methodUnderTest_stateOrInput_expectedResult` — e.g., `recomputeHoldings_withBuyAndSell_calculatesNetQuantity()`.
- **Structure:** Arrange-Act-Assert with blank lines separating each section.
- **What to mock:** External dependencies (repositories, API clients, other services). NEVER mock the class under test.
- **What NOT to mock:** Simple value objects, records, DTOs.
- **Assertions:** Use AssertJ (`assertThat(...)`) for all assertions. Do not use JUnit's `assertEquals` / `assertTrue`.
- **One logical assertion per test** — Multiple `assertThat` calls are fine when verifying properties of a single result object. Separate tests for separate behaviors.

### Controller Tests
- Use `@WebMvcTest(FooController.class)` + `MockMvc`.
- Mock the service layer with `@MockBean`.
- Verify HTTP status codes, response body structure, and content type.
- Test both happy path and error paths (400, 401, 403, 404).

### Repository / Integration Tests
- Use **Testcontainers** with PostgreSQL 16. Never use H2 — it masks PostgreSQL-specific behavior.
- Annotate with `@DataJpaTest` + `@Testcontainers` + `@AutoConfigureTestDatabase(replace = NONE)`.
- Flyway migrations run automatically against the Testcontainers instance.
- Test custom queries, not Spring Data's auto-generated CRUD.
- Create an abstract `AbstractIntegrationTest` with the shared container definition so containers are reused across test classes.

### Service Tests
- Pure unit tests with Mockito mocks for all repository dependencies.
- Verify business logic, computation correctness, and exception throwing.
- For the retirement projection engine, use parameterized tests (`@ParameterizedTest`) with known input/output fixtures.

### Coverage Expectations
- Target: 90%+ line coverage on `wealthview-core` and `wealthview-projection`.
- Target: 80%+ on `wealthview-api` (controllers) and `wealthview-import`.
- Do NOT write tests solely to increase coverage numbers. Every test should verify meaningful behavior.

### Test Organization
- Test files mirror the main source tree: `src/test/java/com/wealthview/core/account/AccountServiceTest.java`.
- Shared test fixtures go in `src/test/java/com/wealthview/<module>/testutil/`.

---

## Git & Commit Conventions

### Branching Policy
- **Commit directly on the current branch (usually `main`).** Do NOT create feature branches, topic branches, or git worktrees unless the user explicitly requests one.
- This is a solo developer project — feature branches add merge overhead and state confusion with no benefit.
- If you find yourself thinking about `git checkout -b` or `git worktree add`, stop and commit on `main` instead.

### Conventional Commits Format
```
<type>(<scope>): <short summary>

<body — what changed and WHY>

<footer — breaking changes, issue refs>
```

### Types
| Type | When to use |
|---|---|
| `feat` | New feature or endpoint |
| `fix` | Bug fix |
| `test` | Adding or updating tests (no production code change) |
| `refactor` | Code restructuring without behavior change |
| `docs` | Documentation only |
| `chore` | Build config, dependencies, tooling |
| `db` | Flyway migration scripts |
| `style` | Formatting, import organization (no logic change) |

### Scope
Use the Maven module name without the `wealthview-` prefix: `core`, `api`, `persistence`, `import`, `projection`, `app`, `frontend`.

### Examples
```
feat(core): add holdings recomputation on transaction create

When a transaction is created, the HoldingsService now recalculates
quantity and cost_basis for the affected account + symbol pair by
aggregating all buy/sell transactions. Manual overrides are preserved
and a warning is logged when new transactions conflict.

Closes #42
```

```
test(api): add AccountController tests for CRUD endpoints

Tests cover: create with valid input (201), create with missing fields
(400), get by ID (200), get nonexistent (404), delete by non-admin
(403). Uses @WebMvcTest with MockMvc.
```

```
db(persistence): add holdings table with manual override flag

V003__create_holdings_table.sql adds the holdings table with columns
for quantity, cost_basis, is_manual_override, and as_of_date.
UUID primary key, foreign key to accounts.
```

### Commit Discipline
- **One logical change per commit.** Do not mix a feature, a refactor, and a migration in one commit.
- **TDD commits:** It is acceptable to commit the test and implementation together once green.
- **Commit message body is mandatory** for `feat`, `fix`, `refactor`, and `db` types. Explain what changed and why.
- **Never commit commented-out code, TODOs without issue references, or `System.out.println` debugging.**

### Pushing to GitHub
- **NEVER run `git push` unless the user explicitly tells you to push.** Do not push proactively, do not push as part of a workflow, and do not push even if a feature is complete and all tests pass. Wait for a direct instruction like "push" or "push to remote".

---

## Backend Architecture Rules

### Module Dependency Direction (STRICT)
```
wealthview-app → depends on ALL modules (assembles the application)
wealthview-api → depends on wealthview-core
wealthview-core → depends on wealthview-persistence
wealthview-import → depends on wealthview-core
wealthview-projection → depends on wealthview-core
wealthview-persistence → depends on nothing (leaf module)
```

wealthview-api NEVER depends directly on wealthview-persistence, wealthview-import, or wealthview-projection.

### Layer Responsibilities
- **Controller (wealthview-api):** HTTP mapping, request validation (`@Valid`), response DTO assembly. No business logic. Controllers call services, never repositories.
- **Service (wealthview-core):** Business logic, orchestration, transaction boundaries (`@Transactional`). Services call repositories and other services.
- **Repository (wealthview-persistence):** Data access only. Custom queries via `@Query` or Spring Data method naming. No business logic.

### DTO vs Entity Separation
- **JPA entities** live in `wealthview-persistence`. They are NEVER exposed in API responses.
- **DTOs (records)** live in `wealthview-core` (for service-layer DTOs) or `wealthview-api` (for request/response objects).
- Map between entities and DTOs using a static factory method on the DTO record (e.g., `AccountResponse.from(AccountEntity entity)`).
- Do NOT use MapStruct or ModelMapper — simple static factory methods on records are sufficient and more transparent.

### Spending Plan Hierarchy (CRITICAL — read before touching projections or spending)
**Guardrail profiles ARE spending profiles.** Both represent the same concept: "how much to spend per
retirement year." They are NOT separate features — they are two implementations of one sealed interface.

**Type system:** `SpendingPlan` sealed interface in `wealthview-core/.../projection/dto/`:
- `TierBasedSpendingPlan` — wraps user-defined spending tiers from a SpendingProfile entity.
  Resolves spending by matching age to tier ranges, handles overlaps/gaps, applies per-tier inflation.
- `GuardrailSpendingInput` — wraps pre-computed yearly spending from Monte Carlo optimization
  (GuardrailSpendingProfile entity). Simple year-based lookup.

**Data model:** A scenario has AT MOST ONE active spending plan at a time. The `projection_scenarios`
table has two FK columns (`spending_profile_id` and `guardrail_profile_id`) that are mutually exclusive:
- Setting a spending profile clears the guardrail profile, and vice versa.
- Setting "None" clears both — the engine falls back to a withdrawal-rate strategy.
- This mutual exclusivity is enforced in `ProjectionService.updateScenario()` and
  `GuardrailProfileService.optimize()`. The UI presents a unified "Spending Plan" dropdown.

**Frontend:** `ScenarioForm` shows a single "Spending Plan" dropdown containing regular spending profiles
AND any guardrail profile that exists for the scenario. `ScenarioResponse` includes both `spending_profile`
and `guardrail_profile` (summary) so the UI can display whichever is active.

### Tenant Isolation
- Every service method that queries data MUST filter by `tenantId`.
- The `tenantId` comes from the JWT-authenticated security context, NEVER from a request parameter.
- Repository methods include `tenantId` in their query: `findByTenantIdAndId(UUID tenantId, UUID id)`.

### Validation
- **Request validation:** `@Valid` + Jakarta Bean Validation annotations on request DTOs in controllers.
- **Business validation:** In service methods (e.g., "account belongs to tenant", "invite code not expired").
- **Database constraints:** Final safety net — not the primary validation mechanism.

### Transaction Management
- `@Transactional` on service methods, NOT on controllers or repositories.
- Use `@Transactional(readOnly = true)` for read-only operations.

---

## Database Conventions (PostgreSQL 16+)

### Naming
- **Tables:** `snake_case`, plural: `accounts`, `holdings`, `property_expenses`.
- **Columns:** `snake_case`: `tenant_id`, `cost_basis`, `is_manual_override`.
- **Primary keys:** Always `id` of type `uuid`, with `DEFAULT gen_random_uuid()`.
- **Foreign keys:** `<referenced_table_singular>_id`: `account_id`, `tenant_id`.
- **Indexes:** `idx_<table>_<columns>`: `idx_holdings_account_id_symbol`.
- **Constraints:** `fk_<table>_<referenced_table>`, `uq_<table>_<columns>`, `chk_<table>_<description>`.

### Flyway Migrations
- **Location:** `backend/wealthview-persistence/src/main/resources/db/migration/`
- **Naming:** `V<NNN>__<description>.sql` — three-digit version, double underscore, lowercase_snake_case description.
- **Rules:**
  - Migrations are IMMUTABLE once committed to main. Never edit a released migration.
  - Each migration should be idempotent where possible (use `IF NOT EXISTS`).
  - Include a comment at the top explaining what the migration does.
  - Always specify explicit column types.

### PostgreSQL-Specific Practices
- Use `uuid` type for primary keys (not `varchar(36)`).
- Use `timestamptz` for all timestamps (not `timestamp`).
- Use `text` instead of `varchar` unless a hard length limit is a business rule.
- Use `numeric(19,4)` for monetary amounts — never `float` or `double`.
- Use `jsonb` (not `json`) for flexible structured data.
- Use `boolean NOT NULL DEFAULT false` — never allow nullable booleans.
- Add `created_at timestamptz NOT NULL DEFAULT now()` and `updated_at timestamptz NOT NULL DEFAULT now()` to every table.

### JSON Field Naming
- API responses use `snake_case` for JSON field names, matching PostgreSQL column names.
- Configure Jackson's `ObjectMapper` with `PropertyNamingStrategies.SNAKE_CASE` globally.

---

## Frontend Conventions (React 18+ / Vite)

### Project Structure
```
frontend/src/
  components/       (reusable UI components)
  pages/            (route-level components, one per route)
  hooks/            (custom React hooks)
  context/          (React context providers)
  api/              (Axios client, API call functions)
  utils/            (pure utility functions)
  types/            (TypeScript interfaces/types)
```

### Patterns
- Functional components only. No class components.
- TypeScript for all files. No `any` types — define interfaces for all API responses.
- Custom hooks for data fetching and shared stateful logic: `useAccounts()`, `useAuth()`.
- Context API + useReducer for global state (auth, tenant).
- Axios instance with interceptors for JWT attachment and 401 redirect.

### Frontend Testing (Lighter Approach)
- **Vitest** as the test runner. **React Testing Library** for component tests.
- Test files co-located: `AccountList.tsx` + `AccountList.test.tsx`.
- **Must test:** Page components, custom hooks, complex logic/utilities.
- **May skip:** Simple presentational components with no logic.
- TDD encouraged but not mandatory on the frontend. Focus test energy on the backend.

---

## Build & Run Commands

### Running the Application (Docker Compose — default for manual testing)

This is the **preferred way** to start the app for manual testing. It builds both frontend and backend into a single Docker image, runs Flyway migrations, and seeds demo data automatically.

```bash
docker compose up --build -d                       # Build & start (http://localhost:80)
docker compose down                                # Stop everything
docker compose logs -f app                         # Tail application logs
docker compose exec db psql -U wv_app wealthview   # Connect to database
```

- **URL:** http://localhost:80
- **Profile:** `docker` (seeds demo data via SampleDataInitializer)
- **Credentials:** `demo@wealthview.local` / `demo123`
- When the user asks to "start the app", "rebuild and relaunch", or "run it for testing", use `docker compose up --build -d`.
- To stop, use `docker compose down`. Always stop existing instances before starting new ones.

### Backend (development / tests only)
```bash
cd backend
mvn clean install                                  # Full build + tests
mvn clean install -DskipTests                      # Build without tests
mvn test                                           # Run all tests
mvn -pl wealthview-core test                       # Run tests for one module
mvn test -Dtest=AccountServiceTest                 # Run a single test class
mvn test -Dtest="AccountServiceTest#methodName"    # Run a single test method
```

### Code Coverage (JaCoCo)
```bash
cd backend
mvn clean test && mvn jacoco:report -pl wealthview-core,wealthview-api,wealthview-projection,wealthview-import
```
- HTML reports: `<module>/target/site/jacoco/index.html`
- CSV data: `<module>/target/site/jacoco/jacoco.csv`
- Coverage targets: core 90%+, projection 90%+, api 80%+, import 80%+

### Frontend (development only)
```bash
cd frontend
npm install                                        # Install dependencies
npm run dev                                        # Dev server (hot reload)
npm run build                                      # Production build
npm run test                                       # Run tests (Vitest)
npm run lint                                       # ESLint check
```

---

## Pre-Commit Checklist

Before committing, verify:

- [ ] New/changed behavior has corresponding tests
- [ ] Tests were written BEFORE the implementation (TDD)
- [ ] All tests pass locally (`mvn test` / `npm run test`)
- [ ] No `System.out.println` or `console.log` debugging statements
- [ ] No commented-out code or TODOs without issue references
- [ ] No wildcard imports
- [ ] Records used for DTOs; JPA entities not exposed in API responses
- [ ] `Optional` returned from finders; null never returned from public methods
- [ ] Monetary values use `BigDecimal` / `numeric(19,4)`, never floating point
- [ ] Every data-access query filters by `tenantId`
- [ ] `tenantId` sourced from security context, not request parameters
- [ ] New migration follows naming convention and uses correct PostgreSQL types
- [ ] No existing migration was modified
- [ ] Commit message follows conventional commits format with body for feat/fix/refactor/db

---

## Common Pitfalls — Do NOT Do These

1. **Do not use H2 for tests.** Always use Testcontainers with PostgreSQL.
2. **Do not put business logic in controllers.** Controllers validate, call a service, return a response.
3. **Do not create bidirectional JPA relationships by default.** Start unidirectional; add inverse only when needed.
4. **Do not use `FetchType.EAGER`.** All relationships should be `LAZY`. Use `JOIN FETCH` when needed.
5. **Do not use `float` or `double` for money.** Use `BigDecimal` in Java and `numeric(19,4)` in PostgreSQL.
6. **Do not catch generic `Exception`.** Catch specific types or let them propagate to the global handler.
7. **Do not skip writing the migration.** If you change an entity, there MUST be a corresponding Flyway migration.
8. **Do not use `@Autowired` on fields.** Use constructor injection only.
9. **Do not push to GitHub without explicit user confirmation.**
10. **Do not assume test failures are pre-existing.** When you encounter a failing test — unit, integration, or end-to-end — investigate and fix it immediately. Never skip a failing test with the rationale that "it was already broken." Every red test is your responsibility to resolve before moving on.
