# Architecture

## Module Dependency Graph

Dependencies flow strictly in one direction. No module may introduce a backwards dependency.

```
wealthview-app
 ├── wealthview-api          (REST layer)
 │    └── wealthview-core    (business logic)
 │         └── wealthview-persistence  (JPA / DB)
 ├── wealthview-import       (parsers, price feeds, scraper)
 │    └── wealthview-core
 └── wealthview-projection   (projection engines)
      └── wealthview-core
```

`wealthview-app` declares exactly these three module dependencies; `wealthview-core` and
`wealthview-persistence` reach it transitively.

`wealthview-api` **never** depends directly on `wealthview-persistence`, `wealthview-import`,
or `wealthview-projection`. All cross-cutting wiring lives in `wealthview-app`.

Integrations invert the dependency: `wealthview-core` declares the contracts
(`ImportParser`, `PriceFeedClient`, `SplitDetectionClient`, `ProjectionEngine`,
`SpendingOptimizer`) and `wealthview-import` / `wealthview-projection` supply the
implementations, so business code never compiles against an HTTP client or a parser.

---

## Layer Responsibilities

### wealthview-persistence

The leaf module. Owns:

* **JPA entities** — 42 `@Entity` classes plus 6 `@MappedSuperclass` bases
  (`Auditable`, `UuidAuditable`, `CreatedAtEntity`, `UuidCreatedAtEntity`,
  `AbstractTaxBracketEntity`, `AbstractPropertyCashFlowEntity`) and 2 `@Embeddable`
  value types (`LoanDetails`, `DepreciationSettings`)
* **Spring Data repositories** — 42 interfaces, plus the `@NoRepositoryBean`
  `TenantScopedRepository<T>` base that declares the shared tenant-scoped finders
* **Flyway migrations** — `V001` through `V080` versioned scripts plus nine repeatable
  seed migrations

No business logic may live here. Entities are never exposed to the API layer.

### wealthview-core

The heart of the application. Owns:

* **Domain services** — 46 `@Service` classes covering accounts, holdings, transactions,
  properties, projections, pricing, stock splits, exchange rates, auth/MFA/sessions,
  tenant management, and more
* **DTOs (Java Records)** — `*Request`, `*Response`, and `*Dto` records used as service
  contracts, grouped in a `dto` sub-package per domain
* **Domain exceptions** — `com.wealthview.core.exception`: `EntityNotFoundException`,
  `TenantAccessDeniedException`, `DuplicateEntityException`, `InvalidInviteCodeException`,
  `InvalidSessionException`, `ServiceUnavailableException`
* **Projection interfaces** — `ProjectionEngine`, `SpendingOptimizer`, the `SpendingPlan`
  sealed interface, and `CapitalMarketAssumptionsProvider`
* **Tax model** — `FederalTaxCalculator`, `StateTaxCalculatorFactory`,
  `CapitalGainsTaxCalculator`, `IrmaaSurchargeCalculator`, withdrawal strategies
* **Cross-cutting config** — `CacheConfig` (five named Caffeine caches), the
  `TenantFilterAspect` / `TenantContext` / `CrossTenantAspect` tenant machinery

Services call repositories (injected via constructor). They never reference entities from other
tenants. Every data access method accepts and filters by `tenantId`.

### wealthview-api

Owns the HTTP boundary:

* **REST controllers** — 28 `@RestController` classes; each maps to one resource domain
* **Spring Security configuration** — `SecurityConfig`, `JwtAuthenticationFilter`,
  `TenantUserPrincipal`; stateless JWT plus a CSRF-protected cookie transport
* **Servlet filters** — `RateLimitFilter`, `RequestLoggingFilter`
* **`@RestControllerAdvice`** — `GlobalExceptionHandler`, mapping exceptions to HTTP status
* **Request validation** — `@Valid` + Jakarta Bean Validation on all request DTOs

Controllers never contain business logic. They validate, call exactly one service method, and
return a DTO.

### wealthview-import

Owns external data ingestion:

* **CSV parsers** — `FidelityCsvParser`, `VanguardCsvParser`, `SchwabCsvParser` each extend
  `AbstractBrokerCsvParser`; format differences are fully encapsulated per parser
* **OFX/QFX parser** — `OfxTransactionParser` wraps OFX4J 1.39; covers most US bank/brokerage
  formats
* **Finnhub clients** — `FinnhubClient` implements the core `PriceFeedClient`;
  `FinnhubSplitClient` implements `SplitDetectionClient` for split auto-detection
* **Yahoo Finance client** — `YahooFinanceClient`, used for historical price backfill
* **Zillow scraper** — `ZillowScraperClient` uses jsoup to fetch property valuations; driven
  by a Sunday 6 AM cron in `wealthview-core`

### wealthview-projection

The most computation-intensive module — 71 classes, of which exactly two are Spring beans:

* **`DeterministicProjectionEngine`** — `@Component`; implements `ProjectionEngine`;
  year-by-year simulation
* **`MonteCarloSpendingOptimizer`** — `@Component`; implements `SpendingOptimizer`;
  block-bootstrap MC optimization
* **`RothConversionOptimizer`** — package-private, not a bean; grid scan + ternary refinement
* Everything else is a package-private collaborator: `PoolStrategy`,
  `WithdrawalOrderStrategy`, `RmdCalculator`/`RmdStreamCalculator`, `TaxableLots`,
  `HouseholdTransition`, `MortalitySampler`, `SustainabilitySearch`, `FractionSearch`,
  `JointConversionSearch`, and friends

See the [Projection Engine](projection-engine.html) page for full algorithmic detail.

### wealthview-app

The assembly module. Owns:

* **`WealthViewApplication`** — Spring Boot `@SpringBootApplication` main class
* **Application profiles** — `application.yml`, `application-dev.yml`,
  `application-docker.yml`, `application-prod.yml`, `application-loadtest.yml`
  (`application-it.yml` lives in `src/test/resources`)
* **Initializers** — `SampleDataInitializer`, `DevDataInitializer`, `DemoDataSeeder`,
  `LoadTestDataSeeder`, `SuperAdminInitializer`, `SystemConfigInitializer`
* **Integration config** — `FinnhubConfig`, `YahooConfig`, `ZillowConfig`,
  `HttpClientFactory`, `MetricsConfig`, `ProductionConfigValidator`
* **Scheduler config** — `SchedulingConfig` (the jobs themselves carry their own
  `@Scheduled` in `wealthview-core`)
* **Health indicators** — `FinnhubHealthIndicator`, `ZillowHealthIndicator`
* **Integration tests** — ~50 `*IT.java` classes run by Maven Failsafe against Testcontainers

---

## Cross-Cutting Concerns

### Multi-Tenancy

Isolation is enforced in two layers.

**Layer 1 (primary).** Every HTTP request that reaches a protected endpoint passes through
`JwtAuthenticationFilter`. The filter validates the token and places a `TenantUserPrincipal`
into the Spring Security context. Service methods extract the tenant via `TenantContext` —
they never accept `tenantId` as a parameter from callers outside the security boundary.
Repository methods always include `tenantId` in their queries; repositories with a navigable
`tenant` association inherit the shared finders from `TenantScopedRepository<T>`:

```java
Optional<T> findByTenant_IdAndId(UUID tenantId, UUID id);
List<T> findByTenant_Id(UUID tenantId);
```

**Layer 2 (backstop).** `TenantFilterAspect` wraps every `@Transactional` service method and
enables a Hibernate `tenantFilter` on the transaction-bound `Session`. Hibernate filters apply
to queries (HQL/Criteria/JPQL) but not to `EntityManager#find` primary-key loads, so Layer 1
stays the primary defense and the filter catches query-shaped IDORs. `CacheConfig` lowers the
transaction interceptor to `Ordered.LOWEST_PRECEDENCE - 100` so the aspect runs *inside* the
open transaction; `CrossTenantAspect` marks the deliberate super-admin exceptions.

### Transaction Boundaries

`@Transactional` is applied at the **service layer only**. Controllers do not own transactions.
Repositories do not declare transactions. Read-only service methods use
`@Transactional(readOnly = true)`.

### Caching

`CacheConfig` registers a `SimpleCacheManager` over five named Caffeine caches, each with
`recordStats()` and a Micrometer binder:

| Cache | Expire after write | Max size |
|---|---|---|
| `accountBalances` | 5 min | 200 |
| `latestPrices` | 10 min | 500 |
| `exchangeRates` | 30 min | 200 |
| `exchangeRateConversions` | 30 min | 200 |
| `mobileAppVersions` | 5 min | 10 |

Invalidation is annotation-driven via `@EvictPriceDerivedCaches` and
`@EvictExchangeRateCaches`.

### Error Handling

All exceptions propagate to the global `@RestControllerAdvice`. Domain exceptions map to
specific HTTP status codes:

| Exception | HTTP Status |
|---|---|
| `EntityNotFoundException`, `NoResourceFoundException` | 404 Not Found |
| `TenantAccessDeniedException`, `AccessDeniedException` | 403 Forbidden |
| `InvalidSessionException`, `BadCredentialsException` | 401 Unauthorized |
| `DuplicateEntityException`, `IllegalStateException` | 409 Conflict |
| `InvalidInviteCodeException`, `IllegalArgumentException`, Jakarta validation failures | 400 Bad Request |
| `MaxUploadSizeExceededException` | 413 Payload Too Large |
| `ServiceUnavailableException` | 503 Service Unavailable |

The response body always follows the standard envelope:
```json
{ "error": "NOT_FOUND", "message": "Account not found", "status": 404 }
```

Failures raised inside the Spring Security filter chain never reach `@RestControllerAdvice`,
so `SecurityConfig` serializes the same envelope from its `authenticationEntryPoint` and
`accessDeniedHandler`.

### Dependency Injection

Constructor injection is used exclusively. With Spring Boot's single-constructor implicit wiring,
`@Autowired` is omitted. Field injection is prohibited.

### Java 25 Idioms in Use

* **Records** for all DTOs, request/response objects, and value objects
* **Sealed interfaces** for type hierarchies: `SpendingPlan`, `WithdrawalStrategy`,
  `ProjectionAccountInput`, `PoolStrategy`, `WithdrawalOrderStrategy`, `QuoteResult`,
  `LoginOutcome`
* **Pattern matching switch** for type dispatch in tax calculators and pool strategies
* **`var`** where the right-hand side makes the type obvious
* **Text blocks** for SQL fragments, JSON literals, and test fixtures
* **`Optional<T>`** returned from all finder methods — `.get()` without a check is forbidden

### Framework Notes (Spring Boot 4.1 / Jackson 3)

* JSON is Jackson 3: runtime types are imported from `tools.jackson.*`, while annotations
  remain `com.fasterxml.jackson.annotation.*`.
* Test doubles in slice tests use **`@MockitoBean`** — `@MockBean` was removed in Boot 4 and
  appears nowhere in the tree.
* Spring Security route matchers use **`PathPatternRequestMatcher`**, not
  `AntPathRequestMatcher`.
* Persistence is Hibernate 7 via the Boot 4.1 BOM; `ddl-auto` is `validate` and
  `open-in-view` is `false` — Flyway 13 owns the schema.
