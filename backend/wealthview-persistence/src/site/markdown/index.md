# wealthview-persistence

The leaf module in the dependency graph. Owns everything related to data persistence:
JPA entities, Spring Data repositories, and the Flyway migration history.

No business logic lives here. Entities are **never** passed beyond `wealthview-core` boundaries —
service methods map them to DTOs (records) before returning.

---

## JPA Entities (27)

All entities use UUID primary keys, `timestamptz` timestamps, and `numeric(19,4)` for money.
Every entity except `TenantEntity` and `PriceEntity` carries a `tenant_id` for row-level
isolation.

### Tenancy & Auth
| Entity | Table | Notes |
|---|---|---|
| `TenantEntity` | `tenants` | Top-level isolation boundary |
| `UserEntity` | `users` | bcrypt password hash, role enum |
| `InviteCodeEntity` | `invite_codes` | Single-use; `consumed_at` preserved for audit |

### Portfolio / Investments
| Entity | Table | Notes |
|---|---|---|
| `AccountEntity` | `accounts` | 8 account types (brokerage, IRA, 401k, Roth, bank, HSA, pension, Roth 401k) |
| `TransactionEntity` | `transactions` | `import_hash` SHA-256 unique constraint for deduplication |
| `HoldingEntity` | `holdings` | `(account_id, symbol)` unique; `is_manual_override` flag |
| `PriceEntity` | `prices` | Composite PK `(symbol, date)`; no tenant FK — shared |
| `ImportJobEntity` | `import_jobs` | Status lifecycle: pending → processing → completed/failed |

### Rental Properties
| Entity | Table | Notes |
|---|---|---|
| `PropertyEntity` | `properties` | Zillow ZPID for automated valuation |
| `PropertyIncomeEntity` | `property_income` | |
| `PropertyExpenseEntity` | `property_expenses` | 8 expense categories |
| `PropertyValuationEntity` | `property_valuations` | Historical snapshots |
| `PropertyDepreciationScheduleEntity` | `property_depreciation_schedules` | Straight-line params |

### Retirement Projections
| Entity | Table | Notes |
|---|---|---|
| `ProjectionScenarioEntity` | `projection_scenarios` | `spending_profile_id` and `guardrail_profile_id` are mutually exclusive |
| `ProjectionAccountEntity` | `projection_accounts` | Linked or hypothetical accounts |
| `SpendingProfileEntity` | `spending_profiles` | Has child `spending_tiers` |
| `GuardrailSpendingProfileEntity` | `guardrail_spending_profiles` | MC-optimized output |
| `IncomeSourceEntity` | `income_sources` | SS, pension, part-time templates |
| `ScenarioIncomeSourceEntity` | `scenario_income_sources` | Join with overrides (start/end age, amount) |

### Tax Reference
| Entity | Table | Notes |
|---|---|---|
| `TaxBracketEntity` | `tax_brackets` | Federal 2022–2025; inflation-projected beyond |
| `StandardDeductionEntity` | `standard_deductions` | Federal 2022–2025 |
| `StateTaxBracketEntity` | `state_tax_brackets` | All 50 states + DC |
| `StateStandardDeductionEntity` | `state_standard_deductions` | |
| `StateTaxSurchargeEntity` | `state_tax_surcharges` | CA SDI and similar flat surcharges |

### System
| Entity | Table | Notes |
|---|---|---|
| `AuditLogEntity` | `audit_logs` | Mutation events per tenant |
| `NotificationPreferenceEntity` | `notification_preferences` | Per-user alert settings |

---

## Spring Data Repositories (25)

One repository interface per entity. Custom queries use `@Query` with JPQL or native SQL —
no business logic, no service calls, no transactions.

All finder methods include `tenantId` as the first parameter:

```java
Optional<AccountEntity> findByTenantIdAndId(UUID tenantId, UUID id);
List<HoldingEntity> findByTenantIdAndAccountId(UUID tenantId, UUID accountId);
```

Repositories that need non-trivial queries (e.g., aggregation for dashboard net worth) use
`@Query` with named bind parameters and explicit result projections.

---

## Flyway Migrations

**Location:** `src/main/resources/db/migration/`

**Versioned migrations (V001–V036):** Immutable once merged to `main`. Each migration is
idempotent where possible (`IF NOT EXISTS`). The migration comment at the top of each file
describes what changed and why.

**Repeatable migrations (R__seed_*.sql):**
* `R__seed_stock_prices` — 5,000+ historical daily close prices for common symbols
* `R__seed_tax_brackets` — Federal marginal brackets 2022–2025
* `R__seed_standard_deductions` — Federal standard deductions 2022–2025
* `R__seed_state_tax_brackets` — State brackets for all jurisdictions

Repeatable migrations re-run whenever their checksum changes, making it safe to extend the
reference data sets.

---

## Test Setup

Integration tests use **Testcontainers** with a real PostgreSQL 16 container. H2 is never used.
All tests extend `AbstractIntegrationTest` to share the container instance across test classes
in the same JVM, avoiding repeated container startup overhead.

```java
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = NONE)
class HoldingRepositoryIntegrationTest extends AbstractIntegrationTest { ... }
```
