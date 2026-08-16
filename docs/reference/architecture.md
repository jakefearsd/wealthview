[← Back to README](../../README.md)

# Architecture

WealthView is a self-hosted, multi-tenant personal finance application built as a monorepo with a Java/Spring Boot backend and a React frontend, backed by PostgreSQL.

## High-Level Component Diagram

```
React SPA  <-->  Spring Boot REST API  <-->  PostgreSQL
                      |
              Finnhub    Yahoo Finance    Zillow
             (quotes,    (price backfill) (property
              splits)                      valuations)
```

## Tech Stack

| Layer     | Technology                                                |
|-----------|-----------------------------------------------------------|
| Frontend  | React 19, TypeScript, Vite, React Router 8, Recharts, Axios |
| Backend   | Java 25, Spring Boot 4.1.0, Spring Security, Spring Data JPA / Hibernate 7 |
| JSON      | Jackson 3 (`tools.jackson.*`; annotations remain `com.fasterxml.jackson.annotation`) |
| Database  | PostgreSQL 16 with Flyway 13 migrations                   |
| Build     | Maven multi-module (backend), npm workspaces (frontend, mobile, shared) |
| Testing   | JUnit 5, Mockito, AssertJ, Testcontainers, Vitest, React Testing Library |
| Analysis  | Checkstyle, SpotBugs, PMD, CPD, JaCoCo                    |
| Deploy    | Docker Compose (multi-stage build)                        |

## Maven Multi-Module Structure

The backend is organized as a Maven multi-module project:

| Module                   | Responsibility                                           |
|--------------------------|----------------------------------------------------------|
| `wealthview-api`         | REST controllers, security config, filters, exception handler |
| `wealthview-core`        | Services, business logic, domain DTOs, tax model, caching |
| `wealthview-persistence` | JPA entities, repositories, Flyway migrations            |
| `wealthview-import`      | CSV/OFX parsers, Finnhub and Yahoo price clients, Zillow scraper |
| `wealthview-projection`  | Deterministic and Monte Carlo retirement engines          |
| `wealthview-app`         | Spring Boot main class, profile configs, schedulers, JAR packaging |

## Module Dependency Rules

```
wealthview-app  -->  wealthview-api, wealthview-import, wealthview-projection
wealthview-api  -->  wealthview-core
wealthview-core -->  wealthview-persistence
wealthview-import      -->  wealthview-core
wealthview-projection  -->  wealthview-core
wealthview-persistence -->  nothing (leaf module)
```

`wealthview-app` declares only the three top-level modules; `wealthview-core` and
`wealthview-persistence` arrive transitively. `wealthview-api` never depends directly on
`wealthview-persistence`, `wealthview-import`, or `wealthview-projection`. Controllers call
services; services call repositories.

Integrations that live in `wealthview-import` are reached through interfaces declared in
`wealthview-core` — `PriceFeedClient`, `SplitDetectionClient`, `ImportParser` — so the
business layer never compiles against the parser or HTTP-client implementations.

## Layer Responsibilities

- **Controller (wealthview-api):** HTTP mapping, request validation (`@Valid`), response DTO assembly. No business logic. Controllers call services, never repositories.
- **Service (wealthview-core):** Business logic, orchestration, transaction boundaries (`@Transactional`). Services call repositories and other services.
- **Repository (wealthview-persistence):** Data access only. Custom queries via `@Query` or Spring Data method naming. No business logic.

## Request Flow

```
HTTP request
  → JwtAuthenticationFilter        (validates bearer token or auth cookie,
  |                                 builds a TenantUserPrincipal)
  → Spring Security filter chain   (SecurityConfig; CSRF for cookie auth,
  |                                 PathPatternRequestMatcher route rules)
  → RateLimitFilter / RequestLoggingFilter
  → @RestController                (@Valid on the request record)
  → @Service in wealthview-core    (@Transactional opens the tx;
  |                                 TenantFilterAspect activates the Hibernate
  |                                 tenant filter inside it)
  → Spring Data repository         (query includes tenantId)
  → response record built by a static from(...) factory
```

Uncaught exceptions unwind to `GlobalExceptionHandler` (`@RestControllerAdvice`), which emits
the standard envelope `{ "error": ..., "message": ..., "status": ... }`. `AccessDeniedException`
and authentication failures raised inside the security filter chain never reach the advice, so
`SecurityConfig` serializes the same envelope from its own entry point and access-denied
handlers.

JSON field names are `snake_case` — set globally via
`spring.jackson.property-naming-strategy: SNAKE_CASE` in `application.yml`. Paged responses use
the `PageResponse` record, whose payload field is `data` (not `content`).

## Tenant Isolation

Isolation is enforced in two layers.

**Layer 1 — explicit `tenantId` filtering (primary).** The tenant is read from the
JWT-authenticated security context, never from a request parameter. Every repository finder
takes it as a parameter; repositories whose entity has a navigable `tenant` association extend
`TenantScopedRepository<T>`, which declares the two shared finders:

```java
Optional<T> findByTenant_IdAndId(UUID tenantId, UUID id);
List<T> findByTenant_Id(UUID tenantId);
```

**Layer 2 — Hibernate filter backstop.** `TenantFilterAspect` wraps every `@Transactional`
service method and enables a `tenantFilter` on the transaction-bound Hibernate `Session` via
`TenantFilterActivator`, using the tenant held in `TenantContext`. Because Hibernate filters
apply to queries (HQL/Criteria/JPQL) but not to `EntityManager#find` primary-key loads,
Layer 1 remains the primary defense; the filter catches query-shaped IDOR mistakes. Ordering
matters: `CacheConfig` lowers the transaction interceptor to
`Ordered.LOWEST_PRECEDENCE - 100` so the aspect runs *inside* the open transaction.
`CrossTenantAspect` marks the deliberate super-admin exceptions.

## Caching

`CacheConfig` in `wealthview-core` registers a `SimpleCacheManager` over five named Caffeine
caches, each with `recordStats()` and a Micrometer binder so hit/miss rates are exported to
Prometheus:

| Cache | TTL (expire after write) | Max size |
|---|---|---|
| `accountBalances` | 5 minutes | 200 |
| `latestPrices` | 10 minutes | 500 |
| `exchangeRates` | 30 minutes | 200 |
| `exchangeRateConversions` | 30 minutes | 200 |
| `mobileAppVersions` | 5 minutes | 10 |

Invalidation is annotation-driven: `@EvictPriceDerivedCaches` and `@EvictExchangeRateCaches`
(both in `com.wealthview.core.config`) are applied to the mutating service methods.

## Connection Pool

HikariCP settings live in `wealthview-app/src/main/resources/application.yml`:

| Setting | Value |
|---|---|
| `maximum-pool-size` | 20 |
| `minimum-idle` | 5 |
| `connection-timeout` | 10 000 ms |
| `idle-timeout` | 300 000 ms |
| `max-lifetime` | 600 000 ms |

`spring.jpa.open-in-view` is `false` and `ddl-auto` is `validate` — the schema is owned
entirely by Flyway.

## Retirement Projection Architecture

The projection stack follows the same interface-in-core / implementation-in-module pattern as
imports. `wealthview-core` declares `ProjectionEngine` and `SpendingOptimizer`;
`wealthview-projection` provides `DeterministicProjectionEngine` and
`MonteCarloSpendingOptimizer` (the module's only two `@Component` beans).

**Spending plans.** `SpendingPlan` is a sealed interface in
`com.wealthview.core.projection.dto` permitting exactly two implementations:

```java
public sealed interface SpendingPlan
        permits TierBasedSpendingPlan, GuardrailSpendingInput {
    ResolvedYearSpending resolveYear(int year, int age, int yearsInRetirement,
                                     BigDecimal inflationRate, BigDecimal activeIncome);
    default Optional<Map<Integer, BigDecimal>> conversionSchedule() { ... }
}
```

`TierBasedSpendingPlan` wraps user-defined age-banded spending tiers from a
`SpendingProfileEntity`; `GuardrailSpendingInput` wraps the pre-computed yearly spending
produced by Monte Carlo optimization. A scenario has **at most one** active plan:
`projection_scenarios.spending_profile_id` and `.guardrail_profile_id` are mutually exclusive,
enforced on the entity itself — `activateSpendingProfile()` clears the guardrail profile,
`activateGuardrailProfile()` clears the spending profile — and driven from
`ScenarioCrudService` and `GuardrailProfileService`. Clearing both falls back to a
withdrawal-rate strategy (`WithdrawalStrategy`, a sealed interface permitting
`FixedPercentageWithdrawal`, `DynamicPercentageWithdrawal`, and
`VanguardDynamicSpendingWithdrawal`; the engine default is 4%).

**Allocation-driven real returns.** `ProjectionAccountInput` (sealed; permits
`LinkedAccountInput` and `HypotheticalAccountInput`) carries an `AssetAllocation` and an
optional `expectedReturnOverride`. When no override is present the account grows at its
allocation blend of capital-market geometric means loaded from `asset_class_returns` by
`CapitalMarketAssumptionsProvider`. The whole projection runs in real (today's-dollars) terms,
so a nominal override is converted to real using the scenario's inflation rate.

**RMDs and capital gains in the main projection.** `RmdCalculator` and
`RmdStreamCalculator` compute per-owner required distributions inside the deterministic run;
`TaxableLots` tracks per-lot FIFO cost basis so `CapitalGainsTaxCalculator` can apply LTCG
brackets and NIIT to taxable-account withdrawals. `IrmaaSurchargeCalculator` annotates years
from the Medicare age of 65.

**Pools.** `PoolStrategy` is a sealed interface permitting a single implementation,
`PoolStrategy.MultiPool`, which tracks taxable / traditional / Roth sub-pools separately.
`WithdrawalOrderStrategy` (sealed; `DynamicSequencingOrder`, `ProRataOrder`,
`OrderedWithdrawalOrder`) allocates each withdrawal across them.

**Household and survivor modeling.** `com.wealthview.core.projection.household` supplies
`HouseholdContext`, `PersonId`, and `LifeExpectancy`. Accounts and income sources carry an
`owner` (`primary`, `spouse`, or `joint`). `HouseholdTransition`, `OwnerPool`,
`SurvivorIncomeAdjuster`, and `HouseholdMcResolver` implement the first-death transition —
keep-larger Social Security, rollover, basis step-up, a survivor spending factor (default
0.75, clamped to [0.5, 1.0]) and an MFJ→single filing-status flip.

**Stochastic mortality** is opt-in. `MortalityTable`/`MortalityTableProvider` in
`wealthview-core` read the `mortality_rates` table (SSA qx, seeded by
`R__seed_mortality_rates`); `MortalitySampler`, `MortalityDrawGenerator`, and
`StochasticMortalityEvaluator` draw per-trial deaths on a separate RNG stream and report
longevity-conditional metrics.

**Monte Carlo.** `MonteCarloSpendingOptimizer` delegates its searches to focused
collaborators: `SustainabilitySearch` (30-iteration spending binary search),
`FractionSearch` (50-point grid + 20 refinement iterations for a single conversion
fraction), and `JointConversionSearch` (20×20 spending × conversion grid, 500 trials per
cell, 10 refinement iterations). Return paths come from `BlockBootstrapReturnGenerator`
(block bootstrap over the multi-asset real-return matrix, preserving autocorrelation) via
`PortfolioPathGenerator`, which reuses one index sequence per trial across all accounts so
cross-asset correlation survives. Risk tolerance maps directly to a target success
probability in `GuardrailProfileService`: conservative 0.95, moderate 0.90, aggressive 0.80.

## Stock Splits

Splits are auto-detected. `StockSplitSyncService` (`wealthview-core`, `@Scheduled` daily at
02:00 America/New_York) scans every distinct held symbol, fetches new splits from Finnhub
through the `SplitDetectionClient` interface (implemented by `FinnhubSplitClient` in
`wealthview-import`) with a 7-day overlap window, and applies them via `StockSplitService` /
`SplitAdjustmentApplier`. `StockSplitBackfillRunner` performs the one-time historical
backfill asynchronously after `ContextRefreshedEvent`, guarded by the
`stock_splits.backfill_completed` flag in `system_config`. Both beans are
`@ConditionalOnBean(SplitDetectionClient.class)`, which itself requires a configured Finnhub
API key. Manual entry and un-apply live under `/api/v1/admin/stock-splits`.

## Multi-Currency

Accounts carry their own currency. `ExchangeRateService` manages tenant-scoped rows in
`exchange_rates` (manual single-rate entries), and `ExchangeRateResolver` performs conversion
at display and aggregation boundaries — stored amounts are never rewritten. Conversions are
cached (`exchangeRates`, `exchangeRateConversions`) and evicted by
`@EvictExchangeRateCaches`.

## Project Directory Tree

```
wealthview/
├── backend/
│   ├── pom.xml                            (parent POM)
│   ├── wealthview-api/                    (controllers, security, filters)
│   ├── wealthview-core/                   (services, business logic, DTOs)
│   │   └── projection/
│   │       ├── strategy/                  (WithdrawalStrategy sealed interface + 3 implementations)
│   │       ├── tax/                       (federal/state/LTCG/IRMAA/SS/SE calculators)
│   │       ├── household/                 (HouseholdContext, PersonId, LifeExpectancy)
│   │       ├── mortality/                 (MortalityTable, MortalityTableProvider)
│   │       └── dto/                       (SpendingPlan, ProjectionInput, ProjectionYearDto, ...)
│   ├── wealthview-persistence/            (entities, repos, migrations)
│   ├── wealthview-import/                 (CSV/OFX parsers, Finnhub, Yahoo, Zillow)
│   ├── wealthview-projection/             (deterministic + Monte Carlo engines)
│   └── wealthview-app/                    (Spring Boot main, configs, schedulers, ITs)
├── frontend/                              (React SPA — npm workspace)
│   ├── src/
│   │   ├── api/                           (Axios client, API call functions)
│   │   ├── components/                    (plus components/admin/, components/scenario/)
│   │   ├── context/                       (AuthContext, ProjectionCacheContext)
│   │   ├── hooks/                         (useApiQuery, useApiMutation, useCrudForm, ...)
│   │   ├── pages/                         (route-level views, lazy-loaded)
│   │   ├── types/                         (TypeScript interfaces)
│   │   └── utils/                         (formatting, projection calculations, styles, permissions)
│   └── package.json
├── mobile/                                (React Native — npm workspace)
├── shared/                                (@wealthview/shared — npm workspace)
├── bin/wv, bin/wv-lib/                    (operator command surface; ./wv shim at root)
├── docker-compose.yml
├── Dockerfile
├── CLAUDE.md                              (AI coding conventions)
└── PROJECT.md                             (full architecture spec)
```

## Testing Conventions Worth Knowing

- Controller slice tests use `@WebMvcTest` with **`@MockitoBean`** — `@MockBean` was removed in Spring Boot 4 and appears nowhere in the tree.
- Repository and API integration tests use Testcontainers with PostgreSQL 16; H2 is never used.
- `wealthview-app` holds ~50 `*IT.java` Failsafe integration tests that drive real HTTP against a fully started app.

## Further Reading

- [PROJECT.md](../../PROJECT.md) — Full architectural specification, data model goals, and feature roadmap
- [Development Guide](../development.md) — Local setup, build commands, and testing
- [Data Model Reference](data-model.md) — Entity definitions and migration inventory
