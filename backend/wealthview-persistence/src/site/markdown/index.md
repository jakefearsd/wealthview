# wealthview-persistence

The leaf module in the dependency graph — it depends on no other WealthView module. Owns
everything related to data persistence: JPA entities, Spring Data repositories, and the Flyway
migration history.

No business logic lives here. Entities are **never** passed beyond `wealthview-core` boundaries —
service methods map them to DTOs (records) before returning.

Runs on Hibernate 7 via the Spring Boot 4.1 BOM, against PostgreSQL 16 with Flyway 13.
`ddl-auto` is `validate` and `open-in-view` is `false`: Flyway owns the schema outright.

---

## JPA Entities (42)

All entities use UUID primary keys (except `prices`, which has a composite key), `timestamptz`
timestamps, and `numeric(19,4)` for money. Tenant-owned entities carry a `tenant` association
for row-level isolation; the shared reference tables (prices, tax and IRMAA/LTCG brackets,
asset-class returns, mortality rates, system config) deliberately do not.

Common bases in the same package: `@MappedSuperclass` `Auditable`, `UuidAuditable`,
`CreatedAtEntity`, `UuidCreatedAtEntity`, `AbstractTaxBracketEntity`, and
`AbstractPropertyCashFlowEntity`; `@Embeddable` `LoanDetails` and `DepreciationSettings`.

### Tenancy & Auth
| Entity | Table | Notes |
|---|---|---|
| `TenantEntity` | `tenants` | Top-level isolation boundary |
| `UserEntity` | `users` | bcrypt password hash, role enum |
| `InviteCodeEntity` | `invite_codes` | Single-use; consumption preserved for audit |
| `RefreshTokenEntity` | `refresh_tokens` | Rotating refresh tokens |
| `UserSessionEntity` | `user_sessions` | Per-device session listing and revocation |
| `MfaChallengeEntity` | `mfa_challenges` | Short-lived TOTP challenge state |
| `MfaRecoveryCodeEntity` | `mfa_recovery_codes` | Single-use recovery codes |
| `LoginActivityEntity` | `login_activity` | Login audit trail |

### Portfolio / Investments
| Entity | Table | Notes |
|---|---|---|
| `AccountEntity` | `accounts` | 5 account types (`brokerage`, `ira`, `401k`, `roth`, `bank`); per-account `currency` since V053 |
| `TransactionEntity` | `transactions` | `import_hash` SHA-256 unique constraint for deduplication; `TransactionType` enum via `TransactionTypeConverter` |
| `HoldingEntity` | `holdings` | `(account_id, symbol)` unique; `is_manual_override` flag |
| `PriceEntity` | `prices` | Composite PK `(symbol, date)` via `PriceId`; no tenant FK — shared. `source` allows `manual`, `finnhub`, `yahoo` |
| `ImportJobEntity` | `import_jobs` | Import run status and row counts |
| `ExchangeRateEntity` | `exchange_rates` | Tenant-scoped manual currency rates |
| `StockSplitEntity` | `stock_splits` | Detected or manually entered split events |
| `StockSplitAdjustmentEntity` | `stock_split_adjustments` | Audit of what a split adjusted, so it can be un-applied |
| `SecurityAssetClassEntity` | `security_asset_class` | Shared symbol → asset-class mapping |
| `SecurityClassOverrideEntity` | `security_class_override` | Per-tenant override of that mapping |

### Rental Properties
| Entity | Table | Notes |
|---|---|---|
| `PropertyEntity` | `properties` | Zillow ZPID for automated valuation; embeds `LoanDetails` and `DepreciationSettings` |
| `PropertyIncomeEntity` | `property_income` | Extends `AbstractPropertyCashFlowEntity` |
| `PropertyExpenseEntity` | `property_expenses` | Extends `AbstractPropertyCashFlowEntity` |
| `PropertyValuationEntity` | `property_valuations` | Historical snapshots |
| `PropertyDepreciationScheduleEntity` | `property_depreciation_schedule` | Straight-line and cost-segregation params |

### Retirement Projections
| Entity | Table | Notes |
|---|---|---|
| `ProjectionScenarioEntity` | `projection_scenarios` | `spending_profile_id` and `guardrail_profile_id` are mutually exclusive — the entity's `activateSpendingProfile()` / `activateGuardrailProfile()` mutators clear the other side. `params_json` is `jsonb` (`@JdbcTypeCode(SqlTypes.JSON)`) |
| `ProjectionAccountEntity` | `projection_accounts` | Linked or hypothetical accounts; `account_type` constrained to `traditional` / `roth` / `taxable`; carries allocation, cost basis, and `owner` |
| `SpendingProfileEntity` | `spending_profiles` | Age-banded `spending_tiers` stored as `jsonb` |
| `GuardrailSpendingProfileEntity` | `guardrail_spending_profiles` | MC-optimized output; `gate_on_adaptive_rules` flag |
| `IncomeSourceEntity` | `income_sources` | SS, pension, part-time templates; `owner` and `survivor_percent` |
| `ScenarioIncomeSourceEntity` | `scenario_income_sources` | Join with overrides (start/end age, amount) |
| `MortalityRateEntity` | `mortality_rates` | SSA `qx` by age and sex — lives in `com.wealthview.persistence.projection`, not the `entity` package |

### Reference Data (no tenant)
| Entity | Table | Notes |
|---|---|---|
| `TaxBracketEntity` | `tax_brackets` | Federal marginal brackets; inflation-projected beyond the seeded years |
| `StandardDeductionEntity` | `standard_deductions` | Federal standard deduction plus the age-65 addition (V074) |
| `StateTaxBracketEntity` | `state_tax_brackets` | State brackets |
| `StateStandardDeductionEntity` | `state_standard_deductions` | |
| `StateTaxSurchargeEntity` | `state_tax_surcharges` | CA SDI and similar flat surcharges |
| `LtcgBracketEntity` | `ltcg_brackets` | Long-term capital-gains brackets (V071) |
| `IrmaaTierEntity` | `irmaa_tiers` | Medicare IRMAA tiers (V075) |
| `AssetClassReturnEntity` | `asset_class_returns` | Annual real returns per asset class, the Monte Carlo bootstrap source |

### System
| Entity | Table | Notes |
|---|---|---|
| `AuditLogEntity` | `audit_log` | Mutation events per tenant |
| `NotificationPreferenceEntity` | `notification_preferences` | Per-user alert settings |
| `SystemConfigEntity` | `system_config` | Runtime key/value config (e.g. `stock_splits.last_sync_at`, `stock_splits.backfill_completed`) |
| `MobileAppVersionEntity` | `mobile_app_versions` | Minimum supported version per platform |

---

## Spring Data Repositories (42 + 1 base)

Custom queries use `@Query` with JPQL or native SQL — no business logic, no service calls, no
transactions.

Repositories whose entity has a navigable `tenant` association extend the `@NoRepositoryBean`
`TenantScopedRepository<T>`, which declares the two finders that were previously duplicated
verbatim across several interfaces:

```java
@NoRepositoryBean
public interface TenantScopedRepository<T> extends JpaRepository<T, UUID> {
    Optional<T> findByTenant_IdAndId(UUID tenantId, UUID id);
    List<T> findByTenant_Id(UUID tenantId);
}
```

Everything else still takes `tenantId` explicitly:

```java
Optional<AccountEntity> findByTenantIdAndId(UUID tenantId, UUID id);
List<HoldingEntity> findByTenantIdAndAccountId(UUID tenantId, UUID accountId);
```

Repositories that need non-trivial queries (e.g. aggregation for dashboard net worth, or the
batch balance computation that replaced an N+1) use `@Query` with named bind parameters and
explicit result projections.

`MortalityRateRepository` lives alongside its entity in `com.wealthview.persistence.projection`.

A Hibernate `tenantFilter` is declared by `@FilterDef` in the `entity` package's
`package-info.java` and applied with `@Filter(name = "tenantFilter", condition =
"tenant_id = :tenantId")` on 24 tenant-owned entities. `TenantFilterAspect` in
`wealthview-core` switches it on per transaction. It applies to queries, not to
`EntityManager#find` primary-key loads, so it is a backstop rather than the primary defense;
SUPER_ADMIN sessions and unauthenticated contexts (login, scheduled jobs) leave it disabled.

---

## Flyway Migrations

**Location:** `src/main/resources/db/migration/`

**Versioned migrations (V001–V080, 80 files):** Immutable once merged to `main`. Each migration
is idempotent where possible (`IF NOT EXISTS`), and carries a comment at the top describing what
changed and why.

Recent milestones worth knowing:

| Range | Feature |
|---|---|
| V053–V054 | Multi-currency: `accounts.currency`, `exchange_rates` table |
| V064 | Stock split detection and adjustment tables |
| V066–V072 | Projection realism v2: asset-class returns, security asset class + override, projection-account allocation and cost basis, LTCG brackets |
| V073–V077 | Age-65 standard deduction, IRMAA tiers, gate-on-adaptive-rules |
| V078–V079 | Household modeling: `projection_accounts.owner`, income-source `owner` + `survivor_percent` |
| V080 | `mortality_rates` |

**Repeatable migrations (9 × `R__seed_*.sql`):**
`R__seed_stock_prices`, `R__seed_tax_brackets`, `R__seed_standard_deductions`,
`R__seed_state_tax_brackets`, `R__seed_ltcg_brackets`, `R__seed_irmaa_tiers`,
`R__seed_asset_class_returns`, `R__seed_security_asset_class`, `R__seed_mortality_rates`.

Repeatable migrations re-run whenever their checksum changes, making it safe to extend the
reference data sets.

---

## Test Setup

Integration tests use **Testcontainers** with a real PostgreSQL 16 container. H2 is never used.
Tests extend `com.wealthview.persistence.AbstractIntegrationTest` to share the container
instance across test classes in the same JVM, avoiding repeated startup overhead. Flyway runs
against the container, so every test also exercises migration correctness.

```java
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = NONE)
class HoldingRepositoryIntegrationTest extends AbstractIntegrationTest { ... }
```

This module has no JaCoCo coverage gate of its own — it is exercised through the gated modules
above it and through `wealthview-app`'s integration tests.
