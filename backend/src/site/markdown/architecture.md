# Architecture

## Module Dependency Graph

Dependencies flow strictly in one direction. No module may introduce a backwards dependency.

```
wealthview-app
 ├── wealthview-api          (REST layer)
 │    └── wealthview-core    (business logic)
 │         └── wealthview-persistence  (JPA / DB)
 ├── wealthview-import       (parsers, price feed)
 │    └── wealthview-core
 └── wealthview-projection   (projection engines)
      └── wealthview-core
```

`wealthview-api` **never** depends directly on `wealthview-persistence`, `wealthview-import`,
or `wealthview-projection`. All cross-cutting wiring lives in `wealthview-app`.

---

## Layer Responsibilities

### wealthview-persistence

The leaf module. Owns:

* **JPA entities** (`*Entity` classes) — the only classes that `@Entity` annotations may appear on
* **Spring Data repositories** — interface definitions only, no query logic beyond `@Query` annotations
* **Flyway migrations** — `V001` through `V036` versioned scripts plus four repeatable seed migrations

No business logic may live here. Entities are never exposed to the API layer.

### wealthview-core

The heart of the application. Owns:

* **Domain services** — 26 `@Service` classes covering accounts, holdings, transactions, properties,
  projections, pricing, auth, tenant management, and more
* **DTOs (Java Records)** — `*Request`, `*Response`, and `*Dto` records used as service contracts
* **Domain exceptions** — `EntityNotFoundException`, `TenantAccessDeniedException`, etc.
* **Projection interfaces** — `ProjectionEngine`, `SpendingOptimizer`, `SpendingPlan` sealed interface
* **Tax model** — `FederalTaxCalculator`, `StateTaxCalculatorFactory`, withdrawal strategies

Services call repositories (injected via constructor). They never reference entities from other
tenants. Every data access method accepts and filters by `tenantId`.

### wealthview-api

Owns the HTTP boundary:

* **REST controllers** — 17 `@RestController` classes; each maps to one resource domain
* **Spring Security configuration** — JWT filter chain, role-based method security
* **`@RestControllerAdvice`** — global exception-to-HTTP-status mapping
* **Request validation** — `@Valid` + Jakarta Bean Validation on all request DTOs

Controllers never contain business logic. They validate, call exactly one service method, and
return a DTO.

### wealthview-import

Owns external data ingestion:

* **CSV parsers** — `FidelityCsvParser`, `VanguardCsvParser`, `SchwabCsvParser` each extend
  `AbstractBrokerCsvParser`; format differences are fully encapsulated per parser
* **OFX/QFX parser** — `OfxTransactionParser` wraps OFX4J; covers most US bank/brokerage formats
* **Finnhub client** — `FinnhubClient` calls the free API at 60 req/min with a 1100 ms rate limit;
  used by the daily `@Scheduled` price sync job
* **Zillow scraper** — `ZillowScraperClient` uses jsoup to fetch property valuations; runs on a
  Sunday 6 AM cron

### wealthview-projection

The most computation-intensive module. Owns three major engines:

* **`DeterministicProjectionEngine`** — implements `ProjectionEngine`; year-by-year simulation
* **`MonteCarloSpendingOptimizer`** — implements `SpendingOptimizer`; block-bootstrap MC optimization
* **`RothConversionOptimizer`** — package-private; grid scan + ternary refinement

See the [Projection Engine](projection-engine.html) page for full algorithmic detail.

### wealthview-app

The assembly module. Owns:

* **`WealthViewApplication`** — Spring Boot `@SpringBootApplication` main class
* **Application profiles** — `application.yml`, `application-dev.yml`, `application-docker.yml`,
  `application-prod.yml`, `application-it.yml`
* **Initializers** — `SampleDataInitializer` (docker profile), `DevDataInitializer` (dev),
  `SuperAdminInitializer` (all profiles)
* **Scheduler config** — `SchedulingConfig`, `PriceSyncScheduler`
* **Integration tests** — `*IT.java` classes run by Maven Failsafe against Testcontainers

---

## Cross-Cutting Concerns

### Multi-Tenancy

Every HTTP request that reaches a protected endpoint passes through the JWT authentication filter.
The filter validates the token and places the `tenantId` into the Spring Security context.
Service methods extract the tenant via a `TenantContext` helper — they never accept `tenantId`
as a parameter from callers outside the security boundary. Repository methods always include
`tenantId` in their queries:

```java
Optional<AccountEntity> findByTenantIdAndId(UUID tenantId, UUID id);
List<AccountEntity> findAllByTenantId(UUID tenantId);
```

### Transaction Boundaries

`@Transactional` is applied at the **service layer only**. Controllers do not own transactions.
Repositories do not declare transactions. Read-only service methods use
`@Transactional(readOnly = true)`.

### Error Handling

All exceptions propagate to the global `@RestControllerAdvice`. Domain exceptions map to
specific HTTP status codes:

| Exception | HTTP Status |
|---|---|
| `EntityNotFoundException` | 404 Not Found |
| `TenantAccessDeniedException` | 403 Forbidden |
| `DuplicateImportException` | 409 Conflict |
| Jakarta validation failures | 400 Bad Request |

The response body always follows the standard envelope:
```json
{ "error": "NOT_FOUND", "message": "Account not found", "status": 404 }
```

### Dependency Injection

Constructor injection is used exclusively. With Spring Boot's single-constructor implicit wiring,
`@Autowired` is omitted. Field injection is prohibited.

### Java 21 Idioms in Use

* **Records** for all DTOs, request/response objects, and value objects
* **Sealed interfaces** for type hierarchies: `SpendingPlan`, `WithdrawalStrategy`, `PoolStrategy`
* **Pattern matching switch** for type dispatch in tax calculators and pool strategies
* **`var`** where the right-hand side makes the type obvious
* **`Optional<T>`** returned from all finder methods — `.get()` without a check is forbidden
