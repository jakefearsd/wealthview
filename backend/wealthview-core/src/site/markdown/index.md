# wealthview-core

The business logic hub. Contains all domain services, DTOs, domain exceptions, and the
interfaces that decouple the projection engines and API layer from each other.

`wealthview-core` depends on `wealthview-persistence` (for repository interfaces and entities)
and nothing else. `wealthview-api`, `wealthview-import`, and `wealthview-projection` all
depend on `wealthview-core`.

---

## Domain Services (26)

All services use constructor injection, are annotated `@Service`, and apply `@Transactional`
at the method level (not class level). Read-only methods use `@Transactional(readOnly = true)`.

### Account & Portfolio

| Service | Responsibility |
|---|---|
| `AccountService` | CRUD for financial accounts; balance calculation |
| `TransactionService` | Transaction CRUD; triggers holdings recomputation on every write |
| `HoldingService` | Manual holding CRUD and override management |
| `HoldingsComputationService` | Reaggregates all transactions for account+symbol to compute quantity and cost_basis; warns on manual override conflicts |
| `PriceService` | Manual price entry; latest price lookup |
| `PriceSyncService` | Orchestrates Finnhub daily sync and historical backfill (invoked by `@Scheduled` job) |

### Property & Real Estate

| Service | Responsibility |
|---|---|
| `PropertyService` | Property CRUD; income and expense line items |
| `PropertyAnalyticsService` | Cap rate, cash-on-cash return, equity growth, mortgage amortisation |
| `PropertyValuationService` | Manual and automated valuation entry |
| `PropertyValuationSyncService` | Zillow scraper integration; Sunday 6 AM cron |

### Retirement Projections

| Service | Responsibility |
|---|---|
| `ProjectionService` | Scenario CRUD; orchestrates `ProjectionEngine.run()` |
| `ProjectionInputBuilder` | Assembles `ProjectionInput` from scenario entity + linked accounts + income sources |
| `SpendingProfileService` | Spending profile CRUD; tier validation |
| `GuardrailProfileService` | MC optimization orchestration; persists result; mutual-exclusivity enforcement |
| `IncomeSourceService` | Income source CRUD; scenario linkage |

### Auth & Tenant

| Service | Responsibility |
|---|---|
| `AuthService` | Login, JWT generation, refresh token; bcrypt password comparison |
| `TenantService` | Tenant CRUD; invite code generation and validation |
| `UserManagementService` | Role changes; user removal |

### Dashboard & Reporting

| Service | Responsibility |
|---|---|
| `DashboardService` | Net worth aggregation; account balance summary; allocation breakdown |
| `SnapshotProjectionService` | Point-in-time portfolio snapshots for history charts |
| `CombinedPortfolioHistoryService` | Aggregates holding-level history across all accounts |
| `TheoreticalPortfolioService` | Reconstructs theoretical portfolio value from transaction history |

### Other

| Service | Responsibility |
|---|---|
| `ImportService` | Delegates to import parsers; persists transactions; records import job |
| `PositionImportService` | Handles positions/holdings CSV import |
| `DataExportService` | Full tenant data export (JSON) |
| `AuditLogService` | Records audit events |
| `NotificationPreferenceService` | Notification setting CRUD |

---

## DTOs (Java Records)

All request, response, and internal transfer objects are Java records. No MapStruct or
ModelMapper — each record has a static `from(Entity entity)` factory method where mapping
is needed.

**Counts:** ~34 `*Response` records, ~25 `*Request` records, ~5 `*Dto` records.

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

---

## Projection Interfaces & Strategy Types

`wealthview-core` defines the contracts that allow `wealthview-api` to call projection logic
without depending on the `wealthview-projection` module:

```java
// Both implementations live in wealthview-projection
interface ProjectionEngine {
    ProjectionResultResponse run(ProjectionInput input);
}

interface SpendingOptimizer {
    GuardrailProfileResponse optimize(GuardrailOptimizationInput input);
}

// Sealed type hierarchy — exhaustive switch enforced by compiler
sealed interface SpendingPlan permits TierBasedSpendingPlan, GuardrailSpendingInput {
    ResolvedYearSpending resolveYear(...);
}

sealed interface WithdrawalStrategy permits FixedPercentageWithdrawal,
                                             DynamicPercentageWithdrawal,
                                             VanguardDynamicSpendingWithdrawal {
    BigDecimal computeWithdrawal(WithdrawalContext ctx);
}
```

---

## Tax Model

The full federal and state tax model lives in `com.wealthview.core.projection.tax`:

| Class | Role |
|---|---|
| `FederalTaxCalculator` | Marginal bracket computation; reads from DB via `TaxBracketRepository`; projects future years with inflation indexing |
| `StateTaxCalculatorFactory` | Returns the correct state calculator by state code |
| `BracketBasedStateTaxCalculator` | Generic bracket calculator for most states |
| `CaliforniaStateTaxCalculator` | California-specific logic (SDI, surcharges, AGI phase-outs) |
| `NullStateTaxCalculator` | No-op for states with no income tax |
| `CombinedTaxCalculator` | Composes federal + state |
| `SocialSecurityTaxCalculator` | 85% inclusion rule |
| `SelfEmploymentTaxCalculator` | SE tax on self-employment / part-time income |
| `RentalLossCalculator` | Passive loss computation; MAGI phase-out for $25k rental allowance |

---

## Domain Exceptions

All exceptions extend `RuntimeException` and are defined in `com.wealthview.core.common`:

| Exception | HTTP Mapping |
|---|---|
| `EntityNotFoundException` | 404 |
| `TenantAccessDeniedException` | 403 |
| `DuplicateImportException` | 409 |

Exceptions are never caught in controllers — they propagate to the global `@RestControllerAdvice`
in `wealthview-api`.

---

## Test Utilities

`wealthview-core` exports a `test-jar` so that its test fixtures, builder helpers, and the
shared `AbstractIntegrationTest` base class are reusable in other modules' test classpaths
without duplication.
