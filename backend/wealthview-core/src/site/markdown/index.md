# wealthview-core

The business logic hub. Contains all domain services, DTOs, domain exceptions, the tax model,
the caching and tenant-isolation machinery, and the interfaces that decouple the projection
engines and import adapters from the rest of the application.

`wealthview-core` depends on `wealthview-persistence` (for repository interfaces and entities)
and nothing else. `wealthview-api`, `wealthview-import`, and `wealthview-projection` all
depend on `wealthview-core`.

---

## Package Layout

Each business domain gets a package with a `dto` sub-package:

```
com.wealthview.core
 ├── account, holding, transaction, price, portfolio, dashboard   (portfolio)
 ├── property                                                     (real estate)
 ├── projection ─ dto, strategy, tax, household, mortality        (retirement)
 ├── importservice, pricefeed, split, exchangerate                (ingestion + market data)
 ├── auth ─ mfa, dto                                              (authn/authz, sessions, MFA)
 ├── tenant, audit, notification, config, export, mobile, income  (platform)
 ├── common      (Money, CompoundGrowth, PageResponse, Entities)
 └── exception   (domain exceptions)
```

---

## Domain Services (46)

All services use constructor injection, are annotated `@Service`, and apply `@Transactional`
at the method level (not class level). Read-only methods use `@Transactional(readOnly = true)`.

The tables below also list a few closely-related `@Component` collaborators
(`StockSplitBackfillRunner`, `ExchangeRateResolver`, `CapitalMarketAssumptionsProvider`) and
the `TransactionHashUtil` static helper, because they are part of the same workflows; the
`@Service` count of 46 excludes them.

### Account & Portfolio

| Service | Responsibility |
|---|---|
| `AccountService` | CRUD for financial accounts; batch balance computation |
| `TransactionService` | Transaction CRUD; triggers holdings recomputation on every write |
| `HoldingService` | Manual holding CRUD and override management |
| `HoldingsComputationService` | Reaggregates all transactions for account+symbol to compute quantity and cost basis; warns on manual override conflicts |
| `PriceService` | Manual price entry; latest price lookup |
| `PriceSyncService` | Orchestrates the price feed's daily sync and historical backfill; owns the `app.finnhub.rate-limit-ms` (default 1100 ms) inter-call delay |
| `DashboardService` | Net worth aggregation; account balance summary; allocation breakdown |
| `SnapshotProjectionService` | Point-in-time portfolio snapshots for history charts |
| `CombinedPortfolioHistoryService` | Aggregates holding-level history across all accounts |
| `TheoreticalPortfolioService` | Reconstructs theoretical portfolio value from transaction history |

### Stock Splits & Currency

| Service | Responsibility |
|---|---|
| `StockSplitService` | Split CRUD; applies and un-applies splits via `SplitAdjustmentApplier` / `SplitMath` |
| `StockSplitSyncService` | Daily `@Scheduled` scan (02:00 America/New_York) of every held symbol with a 7-day overlap window; `@ConditionalOnBean(SplitDetectionClient.class)` |
| `StockSplitBackfillRunner` | One-time historical backfill, run asynchronously after `ContextRefreshedEvent`; idempotent via the `stock_splits.backfill_completed` flag in `system_config` |
| `ExchangeRateService` | Tenant-scoped manual exchange-rate CRUD |
| `ExchangeRateResolver` | Applies conversion at display/aggregation boundaries — stored amounts are never rewritten |

### Property & Real Estate

| Service | Responsibility |
|---|---|
| `PropertyService` | Property CRUD; income and expense line items |
| `PropertyAnalyticsService` | Cap rate, cash-on-cash return, equity growth, mortgage amortisation |
| `PropertyCashFlowService` | Period cash-flow rollups and detail breakdown |
| `PropertyDepreciationService` | Straight-line and cost-segregation depreciation schedules, bonus depreciation, 481(a) catch-up |
| `PropertyRoiService` | Hold-vs-sell ROI comparison including depreciation recapture and capital gains |
| `PropertyValuationService` | Manual and automated valuation entry |
| `PropertyValuationSyncService` | Zillow scraper integration; `app.zillow.sync-cron`, default Sunday 6 AM |

### Retirement Projections

| Service | Responsibility |
|---|---|
| `ProjectionService` | Orchestrates `ProjectionEngine.run()` and result assembly |
| `ScenarioCrudService` | Scenario CRUD; enforces the spending-plan XOR invariant and survivor-factor validation |
| `ProjectionInputBuilder` | Assembles `ProjectionInput` from scenario entity + linked accounts + income sources + `params_json` |
| `SpendingProfileService` | Spending profile CRUD; tier validation |
| `GuardrailProfileService` | MC optimization orchestration; persists result; maps risk tolerance to a target success probability (conservative 0.95, moderate 0.90, aggressive 0.80) |
| `SecurityClassificationService` | Per-symbol asset-class resolution and tenant override |
| `CapitalMarketAssumptionsProvider` | Loads and caches the multi-asset real-return matrix from `asset_class_returns` |
| `MortalityTableProvider` | Loads SSA `qx` rows from `mortality_rates` into a `MortalityTable` |
| `IncomeSourceService` | Income source CRUD; scenario linkage; owner and survivor percent |

### Auth, Tenancy & Platform

| Service | Responsibility |
|---|---|
| `AuthService` | Login, registration, bcrypt comparison; returns a sealed `LoginOutcome` (`Tokens` or `MfaRequired`) |
| `TokenService` | JWT issue/refresh; refresh-token rotation |
| `SessionService` / `SessionStateValidator` | Per-device session listing and revocation |
| `MfaService` / `MfaChallengeService` | TOTP enrolment, verification, recovery codes, challenge lifecycle |
| `LoginActivityService` / `LoginAttemptService` | Login audit trail and lockout throttling |
| `TenantService` | Tenant CRUD; invite code generation and validation |
| `UserManagementService` | Role changes; user activation and removal |
| `SystemConfigService` / `SystemStatsService` | Runtime key/value config and admin statistics |
| `MobileAppVersionService` | Minimum-version policy per mobile platform |

### Import, Export & Reporting

| Service | Responsibility |
|---|---|
| `ImportService` | Resolves an `ImportParser` bean by `{format}CsvParser` naming (falling back to the `@Primary` generic `CsvTransactionParser`), persists transactions, records the import job |
| `PositionImportService` | Handles positions/holdings CSV import |
| `TransactionHashUtil` | SHA-256 deduplication hash over `(date, type, symbol, quantity, amount)` |
| `DataExportService` | Full tenant data export (JSON + per-domain CSV) |
| `AuditLogService` | Records audit events |
| `NotificationPreferenceService` | Notification setting CRUD |

---

## DTOs (Java Records)

All request, response, and internal transfer objects are Java records. No MapStruct or
ModelMapper — each record has a static `from(Entity entity)` factory method where mapping
is needed.

**Counts:** ~51 `*Response` records, ~31 `*Request` records, 6 `*Dto` records; roughly 138
files across all `dto` sub-packages.

**Patterns:**
```java
// Factory method on response record
public record AccountResponse(UUID id, String name, String type, ...) {
    public static AccountResponse from(AccountEntity entity) {
        return new AccountResponse(entity.getId(), entity.getName(), ...);
    }
}

// Request record with validation annotations
public record CreateAccountRequest(
    @NotBlank String name,
    @NotNull AccountType type,
    String institution
) {}
```

JSON serialization is Jackson 3: runtime types come from `tools.jackson.*` while annotations
remain `com.fasterxml.jackson.annotation.*`. Field names are `snake_case` globally.

---

## Interfaces & Sealed Type Hierarchies

`wealthview-core` defines the contracts that let the other modules stay decoupled:

```java
// Implementations live in wealthview-projection
public interface ProjectionEngine  { ProjectionResultResponse run(ProjectionInput input); }
public interface SpendingOptimizer { GuardrailProfileResponse optimize(GuardrailOptimizationInput in); }

// Implementations live in wealthview-import
public interface ImportParser        { ImportParseResult parse(InputStream in) throws IOException; }
public interface PriceFeedClient     { QuoteResult getQuote(String symbol);
                                       Optional<CandleResponse> getCandles(String s, LocalDate f, LocalDate t); }
@FunctionalInterface
public interface SplitDetectionClient { List<DetectedSplit> fetch(String symbol, LocalDate f, LocalDate t); }
```

Sealed hierarchies (exhaustive `switch` enforced by the compiler):

```java
public sealed interface SpendingPlan
        permits TierBasedSpendingPlan, GuardrailSpendingInput {
    ResolvedYearSpending resolveYear(int year, int age, int yearsInRetirement,
                                     BigDecimal inflationRate, BigDecimal activeIncome);
    default Optional<Map<Integer, BigDecimal>> conversionSchedule() { return Optional.empty(); }
}

public sealed interface WithdrawalStrategy
        permits FixedPercentageWithdrawal, DynamicPercentageWithdrawal,
                VanguardDynamicSpendingWithdrawal { ... }

public sealed interface ProjectionAccountInput
        permits LinkedAccountInput, HypotheticalAccountInput { ... }

public sealed interface QuoteResult  { ... }   // price feed outcome
public sealed interface LoginOutcome permits LoginOutcome.Tokens, LoginOutcome.MfaRequired { ... }
```

**Spending plans are the same concept, twice.** `TierBasedSpendingPlan` wraps user-defined
age-banded tiers from a `SpendingProfileEntity`; `GuardrailSpendingInput` wraps the yearly
spending pre-computed by Monte Carlo optimization. A scenario has **at most one** active plan —
`ProjectionScenarioEntity.activateSpendingProfile()` clears the guardrail profile and
`activateGuardrailProfile()` clears the spending profile, so the XOR invariant holds at the
entity level. `ScenarioCrudService` and `GuardrailProfileService` drive those mutators.

**Accounts carry an allocation, not just a return.** `ProjectionAccountInput` exposes an
`AssetAllocation`, an optional `expectedReturnOverride`, a `costBasis` seed for per-lot FIFO
capital-gains tracking, an `accountType`, and an `owner` (`primary` / `spouse` / `joint`).
Without an override, the engine grows the account at its allocation blend of the real geometric
means served by `CapitalMarketAssumptionsProvider`.

---

## Tax Model

The full federal and state tax model lives in `com.wealthview.core.projection.tax`:

| Class | Role |
|---|---|
| `FederalTaxCalculator` | Marginal bracket computation; reads brackets and standard deductions from the DB; projects future years with inflation indexing |
| `CapitalGainsTaxCalculator` | Long-term capital gains brackets plus NIIT on taxable-account realizations |
| `IrmaaSurchargeCalculator` | Medicare IRMAA tiers, applied from age 65 |
| `SocialSecurityTaxCalculator` | Provisional-income inclusion rules |
| `SelfEmploymentTaxCalculator` | SE tax on self-employment / part-time income |
| `RentalLossCalculator` | Passive loss computation; MAGI phase-out for the $25k rental allowance |
| `StateTaxCalculatorFactory` | Returns the correct `StateTaxCalculator` by state code |
| `BracketBasedStateTaxCalculator` | Generic bracket calculator for most states |
| `CaliforniaStateTaxCalculator` | California-specific logic |
| `NullStateTaxCalculator` | No-op for states with no income tax |
| `CombinedTaxCalculator` / `CombinedTaxResult` | Composes federal + state |
| `TaxCalculationStrategy` / `FederalOnlyTaxStrategy` | Strategy seam consumed by the projection engines |

---

## Household & Mortality

`com.wealthview.core.projection.household` holds `HouseholdContext`, `PersonId`, and
`LifeExpectancy` — the value types that make both engines owner-aware (per-owner RMD streams,
owner-age income windows, per-person thresholds, and the first-death transition).

`com.wealthview.core.projection.mortality` holds `MortalityTable` and its `@Service`
`MortalityTableProvider`, which reads SSA `qx` rows from `mortality_rates`. Stochastic
mortality is an opt-in, success-probability-only Monte Carlo mode.

---

## Cross-Cutting Infrastructure

| Class | Role |
|---|---|
| `config.CacheConfig` | Registers five named Caffeine caches — `accountBalances` (5 min/200), `latestPrices` (10 min/500), `exchangeRates` (30 min/200), `exchangeRateConversions` (30 min/200), `mobileAppVersions` (5 min/10) — each Micrometer-instrumented. Also lowers the transaction interceptor order so `TenantFilterAspect` runs inside the transaction. |
| `config.EvictPriceDerivedCaches`, `config.EvictExchangeRateCaches` | Annotation-driven cache invalidation on mutating service methods |
| `auth.TenantContext` | Holds the request's tenant, sourced from the security context |
| `auth.TenantFilterAspect` / `auth.TenantFilterActivator` | Enable the Hibernate `tenantFilter` on the transaction-bound session for every `@Transactional` method — the query-level backstop behind explicit `tenantId` filtering |
| `auth.CrossTenantAspect` | Marks the deliberate super-admin exceptions to that filter |
| `common.Money`, `common.CompoundGrowth`, `common.PageResponse`, `common.Entities` | Shared value helpers; `PageResponse`'s payload field is `data`, not `content` |

---

## Domain Exceptions

All exceptions extend `RuntimeException` and are defined in `com.wealthview.core.exception`:

| Exception | HTTP Mapping |
|---|---|
| `EntityNotFoundException` | 404 |
| `InvalidSessionException` | 401 |
| `TenantAccessDeniedException` | 403 |
| `DuplicateEntityException` | 409 |
| `InvalidInviteCodeException` | 400 |
| `ServiceUnavailableException` | 503 |

Exceptions are never caught in controllers — they propagate to `GlobalExceptionHandler`
in `wealthview-api`.

---

## Test Utilities

`wealthview-core` exports a `test-jar` so its fixtures and builder helpers are reusable on
other modules' test classpaths without duplication. `com.wealthview.core.testutil` holds
`ScenarioMother`, `ScenarioRequestBuilder`, `TaxBracketFixtures`, and `TestEntityHelper`;
`wealthview-projection` consumes the jar directly.

The Testcontainers base classes live where they are used: `AbstractIntegrationTest` in
`wealthview-persistence`, `AbstractApiIntegrationTest` in `wealthview-app`.

Coverage gates: **90%** line, **0.83** branch. Pitest mutation testing is configured for this
module (advisory, not a build gate).
