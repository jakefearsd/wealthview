# Data Model

WealthView's schema spans **42 JPA entities** across 8 business domains, managed by **89 Flyway
migrations** (V001–V080 versioned + 9 repeatable seed scripts) and served by 43 Spring Data
repositories.

This page is the **entity / ORM view**: the mapped classes, their base types, associations and fetch
strategies. For the SQL-schema view — columns, types, constraints and indexes as they exist in
PostgreSQL — see `docs/reference/data-model.md` in the repository root.

All but one entity live in `com.wealthview.persistence.entity` in the `wealthview-persistence`
module, which depends on nothing else in the reactor. The exception is `MortalityRateEntity`, which
sits in `com.wealthview.persistence.projection` alongside its repository. Entities are never exposed
in API responses.

---

## Schema Conventions

| Convention | Rule |
|---|---|
| Primary key | `id uuid NOT NULL DEFAULT gen_random_uuid()` |
| Tenant isolation | Tenant-owned tables carry `tenant_id uuid NOT NULL REFERENCES tenants(id)`; shared reference tables do not |
| Timestamps | `created_at timestamptz NOT NULL DEFAULT now()`, `updated_at timestamptz NOT NULL DEFAULT now()` |
| Money | `numeric(19,4)` — never `float` or `double` |
| Booleans | `boolean NOT NULL DEFAULT false` — never nullable booleans |
| Text | `text` unless a hard length limit is a business rule; no `varchar` padding |
| Flexible params | `jsonb` (not `json`) for semi-structured data |

---

## Mapped Superclasses

Four `@MappedSuperclass` bases carry the identity and audit columns, so no concrete entity redeclares
them:

```
CreatedAtEntity           created_at (@CreationTimestamp, updatable = false)
  ├── Auditable           + updated_at (@UpdateTimestamp)
  │     └── UuidAuditable + @Id UUID (@GeneratedValue(strategy = UUID))
  └── UuidCreatedAtEntity + @Id UUID (@GeneratedValue(strategy = UUID))
```

* **`UuidAuditable`** — mutable, UUID-keyed. The default for business entities.
* **`UuidCreatedAtEntity`** — append-only, UUID-keyed: audit rows, sessions, reference tables.
* **`Auditable`** — mutable but *not* UUID-keyed. Only `MobileAppVersionEntity` uses it (its primary
  key is the `platform` string).
* **`CreatedAtEntity`** — append-only, non-UUID key. Only `PriceEntity`.

Timestamps are maintained by Hibernate (`@CreationTimestamp` / `@UpdateTimestamp`), **not** by
service code — an update path cannot silently persist a stale `updated_at`. Field initialisers keep
both non-null before the first flush so unit tests that never touch the database still read a value.

Two entities opt out of the UUID bases entirely:

* **`PriceEntity`** — `@IdClass(PriceId.class)`, composite key `(symbol, date)`.
* **`SystemConfigEntity`** — string key, with a hand-managed `updated_at`.

Three further `@Embeddable` / `@MappedSuperclass` types share column groups:

* **`AbstractPropertyCashFlowEntity`** (`@MappedSuperclass`) — the shared tenant/property/date/amount
  shape behind `PropertyIncomeEntity` and `PropertyExpenseEntity`.
* **`AbstractTaxBracketEntity`** (`@MappedSuperclass`) — the shared bracket shape behind
  `TaxBracketEntity`, `StateTaxBracketEntity` and `LtcgBracketEntity`.
* **`LoanDetails`** and **`DepreciationSettings`** (`@Embeddable`) — embedded into `PropertyEntity`
  with `@AttributeOverrides` for the column names.

---

## Domain Map

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  TENANCY & AUTH                  │  PORTFOLIO / INVESTMENTS                 │
│  TenantEntity                    │  AccountEntity                           │
│  UserEntity                      │  TransactionEntity  (+ TransactionType)  │
│  InviteCodeEntity                │  HoldingEntity                           │
│  RefreshTokenEntity              │  PriceEntity        (shared, no tenant)  │
│  UserSessionEntity               │  ImportJobEntity                         │
│  MfaChallengeEntity              │  ExchangeRateEntity                      │
│  MfaRecoveryCodeEntity           │  StockSplitEntity                        │
│  LoginActivityEntity             │  StockSplitAdjustmentEntity              │
├─────────────────────────────────────────────────────────────────────────────┤
│  RENTAL PROPERTIES               │  RETIREMENT PROJECTIONS                  │
│  PropertyEntity                  │  ProjectionScenarioEntity                │
│    + LoanDetails (embedded)      │  ProjectionAccountEntity                 │
│    + DepreciationSettings        │  SpendingProfileEntity                   │
│  PropertyIncomeEntity            │  GuardrailSpendingProfileEntity          │
│  PropertyExpenseEntity           │  IncomeSourceEntity                      │
│  PropertyValuationEntity         │  ScenarioIncomeSourceEntity              │
│  PropertyDepreciationSchedule    │                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│  TAX & MARKET REFERENCE (shared) │  SYSTEM                                  │
│  TaxBracketEntity                │  AuditLogEntity                          │
│  StandardDeductionEntity         │  NotificationPreferenceEntity            │
│  StateTaxBracketEntity           │  SystemConfigEntity                      │
│  StateStandardDeductionEntity    │  MobileAppVersionEntity                  │
│  StateTaxSurchargeEntity         │                                          │
│  LtcgBracketEntity               │                                          │
│  IrmaaTierEntity                 │                                          │
│  AssetClassReturnEntity          │                                          │
│  SecurityAssetClassEntity        │                                          │
│  SecurityClassOverrideEntity     │  (tenant-scoped override)                │
│  MortalityRateEntity             │  (in ...persistence.projection)          │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Associations and Fetch Strategy

**Every association in the model is `FetchType.LAZY`.** There is not a single `EAGER` mapping — use
`JOIN FETCH` in a `@Query` when a repository method genuinely needs the graph. Repositories with a
`findWith...` prefix (e.g. `ScenarioIncomeSourceRepository.findWithIncomeSourceByScenarioId`) exist
precisely to fetch-join rather than trip N+1.

**Associations are unidirectional by default.** There is exactly **one** `@OneToMany` in the whole
model:

```java
// ProjectionScenarioEntity
@OneToMany(mappedBy = "scenario", cascade = CascadeType.ALL, orphanRemoval = true)
private List<ProjectionAccountEntity> accounts;
```

It earns its inverse side because scenario accounts are edited as a set (the update path calls
`accounts.clear()` and re-adds), which needs `orphanRemoval`. Everything else is a plain
`@ManyToOne(fetch = LAZY)` from the child side and is navigated through repositories.

Owning-side highlights:

| Entity | `@ManyToOne(LAZY)` targets |
|---|---|
| `AccountEntity` | tenant |
| `TransactionEntity` | tenant, account |
| `HoldingEntity` | tenant, account |
| `ImportJobEntity` | tenant, account |
| `PropertyEntity` | tenant |
| `AbstractPropertyCashFlowEntity` | tenant, property |
| `PropertyValuationEntity`, `PropertyDepreciationScheduleEntity` | tenant, property |
| `ProjectionScenarioEntity` | tenant, **spendingProfile**, **guardrailProfile** |
| `ProjectionAccountEntity` | scenario, linked account |
| `ScenarioIncomeSourceEntity` | scenario, income source |
| `IncomeSourceEntity` | tenant, property |
| `SpendingProfileEntity`, `GuardrailSpendingProfileEntity` | tenant (+ scenario on the guardrail side) |
| `InviteCodeEntity` | tenant, createdBy user, consumedBy user |
| `UserEntity` | tenant |
| `ExchangeRateEntity`, `NotificationPreferenceEntity`, `StockSplitAdjustmentEntity` | tenant |

The many-to-many between scenarios and income sources is modelled explicitly as
`ScenarioIncomeSourceEntity` (an association entity with extra columns), not with `@ManyToMany`.

---

## Tenant Isolation at the ORM Level

Beyond the `tenantId`-in-every-finder discipline, there is a **second, defence-in-depth layer** in the
mapping itself. `entity/package-info.java` declares a package-level Hibernate filter:

```java
@org.hibernate.annotations.FilterDef(
        name = "tenantFilter",
        parameters = @ParamDef(name = "tenantId", type = java.util.UUID.class))
package com.wealthview.persistence.entity;
```

22 entity classes (plus `AbstractPropertyCashFlowEntity`) then carry

```java
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
```

`TenantFilterActivator` — driven by `TenantFilterAspect` at the start of every `@Transactional`
service boundary — enables the filter on the current Hibernate `Session` for the authenticated
tenant. The effect is that **even a query whose hand-written `WHERE` clause forgot the tenant
predicate cannot return another tenant's rows.**

The filter is deliberately left **disabled** in three cases:

* **No SecurityContext** — pre-auth requests (login, register, refresh) and `@Scheduled` background
  jobs, which legitimately read across tenants.
* **`SUPER_ADMIN` principals** — platform administration spans every tenant.
* **Entities without the annotation** — `TenantEntity` itself, `PriceEntity`, all shared tax and
  market reference tables, and child rows reached only through an already-filtered parent
  (`ProjectionAccountEntity`, `ScenarioIncomeSourceEntity`). `PropertyIncomeEntity` and
  `PropertyExpenseEntity` inherit the annotation from `AbstractPropertyCashFlowEntity`.

This is a backstop, not a substitute: services still filter by `tenantId` explicitly, because the
filter is off for exactly the callers that most need review.

---

## jsonb Columns

Semi-structured data is stored as `jsonb` and mapped with
`@JdbcTypeCode(SqlTypes.JSON)` plus `@Column(columnDefinition = "jsonb")`. Without the
`@JdbcTypeCode`, Hibernate binds the field as `varchar` and PostgreSQL rejects the write.

| Entity | Field | Java type | Contents |
|---|---|---|---|
| `ProjectionScenarioEntity` | `paramsJson` | `String` | Overflow scenario parameters — birth year, filing status, withdrawal order, household/spouse fields, mortality toggles, yields, fee rate |
| `ProjectionAccountEntity` | `allocation` | `Map<String, BigDecimal>` | Asset-class weights (`us_stock`, `intl_stock`, `bond`, `cash`) |
| `SpendingProfileEntity` | `spendingTiers` | `String` | Age-tiered spending array (defaults to `"[]"`) |
| `SpendingProfileEntity` | `incomeStreams` | `String` | Per-profile income stream array (defaults to `"[]"`) |
| `GuardrailSpendingProfileEntity` | `phases` | `String` | Optimizer phase definitions (defaults to `"[]"`) |
| `GuardrailSpendingProfileEntity` | `yearlySpending` | `String` | Year-by-year optimized spending array (defaults to `"[]"`) |
| `GuardrailSpendingProfileEntity` | `conversionSchedule` | `String` | Roth conversion schedule; null when conversions were not optimized |
| `DepreciationSettings` (embedded in `PropertyEntity`) | `costSegAllocations` | `String` | Cost-segregation asset-class allocations |
| `AuditLogEntity` | `details` | `Map<String, Object>` | Free-form change detail |

Most of these are mapped as `String` and (de)serialised by the service layer. That is deliberate: the
service owns the DTO shape and its `ObjectMapper` configuration, and keeping the entity a plain
string avoids coupling the persistence module to those DTOs. `allocation` and `details` are the two
that map to a Java collection directly, because their shapes are stable primitives.

---

## Notable Entity Details

### Tenancy & Auth

**`TenantEntity`** — the isolation boundary; every tenant-owned entity holds a lazy `@ManyToOne` to it.

**`UserEntity`** — email unique within a tenant, bcrypt password hash, role string
(`viewer` / `member` / `admin`, plus the platform-level `super_admin`).

**`InviteCodeEntity`** — three lazy user/tenant associations (tenant, `createdBy`, `consumedBy`).
Consumed codes are retained, not deleted — they are the audit trail of who invited whom.

**`RefreshTokenEntity`, `UserSessionEntity`, `MfaChallengeEntity`, `MfaRecoveryCodeEntity`,
`LoginActivityEntity`** — the session/MFA surface. All append-only or short-lived;
`LoginActivityEntity` and `UserSessionEntity` extend `UuidCreatedAtEntity`.

### Portfolio / Investments

**`TransactionEntity`** — the immutable ledger. `type` maps through an explicit
`@Convert(converter = TransactionTypeConverter.class)`; the converter is deliberately **not**
`autoApply`, so it is wired per field rather than globally. `import_hash` (SHA-256) enforces import
deduplication.

**`HoldingEntity`** — computed position per `(account, symbol)`, recomputed whenever transactions
change, with an `is_manual_override` flag that protects hand-edited rows.

**`PriceEntity`** — the only composite-key entity: `@IdClass(PriceId.class)` over `(symbol, date)`.
**No `tenant_id`** — prices are shared across tenants.

**`StockSplitEntity` / `StockSplitAdjustmentEntity`** — auto-detected splits and the per-row
adjustment ledger that lets an application be un-applied.

**`ExchangeRateEntity`** — tenant-scoped manual FX rates; conversion is applied at display and
aggregation boundaries, not at storage.

### Rental Properties

**`PropertyEntity`** — carries two `@Embedded` value objects, each with `@AttributeOverrides` mapping
their fields onto the property table's columns:

* **`LoanDetails`** — loan amount, rate, term, start date.
* **`DepreciationSettings`** — useful life (default 27.5), in-service date, cost-segregation
  allocations (`jsonb`), bonus depreciation and the 481(a) catch-up study year.

`@Embedded` fields with a `jsonb` column keep their own `@JdbcTypeCode`/`@Column` on the embeddable's
field, since `@AttributeOverride` cannot express a JDBC type code.

**`PropertyIncomeEntity` / `PropertyExpenseEntity`** — both extend
`AbstractPropertyCashFlowEntity`, which owns the shared tenant + property associations.

### Retirement Projections

**`ProjectionScenarioEntity`** — the richest entity in the model. Beyond its tenant and account
associations it holds **two mutually exclusive spending-plan FKs**:

```java
@ManyToOne(fetch = FetchType.LAZY)   private SpendingProfileEntity spendingProfile;
@ManyToOne(fetch = FetchType.LAZY)   private GuardrailSpendingProfileEntity guardrailProfile;
```

The XOR invariant is enforced **on the entity**, through paired mutators rather than raw setters:

* `activateSpendingProfile(...)` — sets the tier-based profile and **clears** the guardrail profile.
* `activateGuardrailProfile(...)` — sets the guardrail profile and **clears** the spending profile.
* `clearSpendingProfile()` / `clearGuardrailProfile()` — clear one side only.

Raw setters survive for test-fixture setup, but any call site that also reasons about the other side
must use the paired mutators. `ScenarioCrudService.updateScenario()` and
`GuardrailProfileService.optimize()` are the production callers. With neither set, the projection
engine falls back to a withdrawal-rate strategy.

**`ProjectionAccountEntity`** — links a scenario to a real `AccountEntity` (nullable, for hypothetical
accounts), carrying `initial_balance`, `annual_contribution`, an **optional** `expected_return`
override, `cost_basis`, `owner` (`primary`/`spouse`/`joint`) and the `allocation` jsonb. Note
`expected_return` is nullable since V069 — null means "derive from the allocation", and V070/V073
removed the legacy `0.07` default and backfilled the rows that had inherited it.

**`SpendingProfileEntity`** — top-level essential/discretionary amounts plus the `spending_tiers`
jsonb array; both jsonb fields default to `"[]"` rather than null.

**`GuardrailSpendingProfileEntity`** — the persisted output of the Monte Carlo optimizer: the yearly
spending array, phases, conversion schedule, confidence level, trial count, risk tolerance, corridor
knobs, the `gate_on_adaptive_rules` toggle, a `scenario_hash` and a `stale` flag. It is scenario-scoped
(one per scenario), and editing the scenario marks it stale when the hash changes.

**`IncomeSourceEntity`** — reusable income definitions, with an optional lazy `@ManyToOne` to a
property (rental income sources), plus `owner` and `survivor_percent` for household modelling.

### Tax & Market Reference

These tables are tenant-independent and populated by repeatable Flyway migrations
(`R__seed_tax_brackets`, `R__seed_standard_deductions`, `R__seed_state_tax_brackets`,
`R__seed_ltcg_brackets`, `R__seed_irmaa_tiers`, `R__seed_asset_class_returns`,
`R__seed_security_asset_class`, `R__seed_mortality_rates`, `R__seed_stock_prices`).

| Entity | Purpose |
|---|---|
| `TaxBracketEntity` | Federal marginal brackets by filing status and year |
| `StandardDeductionEntity` | Federal standard deduction, plus `additional_age65` (V074) |
| `StateTaxBracketEntity`, `StateStandardDeductionEntity`, `StateTaxSurchargeEntity` | State brackets, deductions and flat surcharges (e.g. California SDI) |
| `LtcgBracketEntity` | 0/15/20% long-term capital gains brackets (V071) |
| `IrmaaTierEntity` | Medicare IRMAA MAGI tiers with monthly Part B/Part D surcharges (V075) |
| `AssetClassReturnEntity` | Real historical annual returns per asset class, 1928–2025 (V066) |
| `SecurityAssetClassEntity` | Global symbol → asset-class seed map (V067) |
| `SecurityClassOverrideEntity` | **Tenant-scoped** reclassification override, layered on top (V068) |

`TaxBracketEntity`, `StateTaxBracketEntity` and `LtcgBracketEntity` all extend
`AbstractTaxBracketEntity`. Calculators cache these in `ConcurrentHashMap`s keyed by
`(taxYear, filingStatus)` and fall back to the latest seeded year when a future year is requested.

**`MortalityRateEntity`** (`mortality_rates`, V080 + `R__seed_mortality_rates`) is the one entity
outside the `entity` package — it lives in `com.wealthview.persistence.projection` next to
`MortalityRateRepository`, and carries sex-specific SSA period-life `qx` values with no audit
columns mapped. `MortalityTableProvider` in `wealthview-core` folds the rows into a `MortalityTable`,
and `ProjectionInputBuilder` only loads it when a scenario opts into stochastic mortality — a
toggle-off scenario never touches the table.

---

## Key Relationships

```
TenantEntity ──< UserEntity
             ──< InviteCodeEntity
             ──< AccountEntity ──< TransactionEntity
                               ──< HoldingEntity
                               ──< ImportJobEntity
             ──< PropertyEntity ──< PropertyIncomeEntity
                                ──< PropertyExpenseEntity
                                ──< PropertyValuationEntity
                                ──< PropertyDepreciationScheduleEntity
             ──< ProjectionScenarioEntity ──< ProjectionAccountEntity   (@OneToMany, cascade ALL)
                                          ──< ScenarioIncomeSourceEntity ──> IncomeSourceEntity
                                          ──> SpendingProfileEntity      \  mutually
                                          ──> GuardrailSpendingProfileEntity  / exclusive
             ──< ExchangeRateEntity
             ──< SecurityClassOverrideEntity
```

Arrows point in the direction the association is *mapped*. Only the scenario → accounts edge is
navigable from the parent; every other `──<` is a child-owned `@ManyToOne` that the parent reaches
through a repository query.

---

## Migration History (Summary)

Migrations live in
`backend/wealthview-persistence/src/main/resources/db/migration/` and are **immutable once merged**.

| Range | Theme |
|---|---|
| V001–V010 | Baseline: tenants, users, RBAC, invite codes, accounts, transactions, holdings, prices, properties, import jobs, dedup hash |
| V011–V025 | Federal tax brackets (V013), spending profiles (V014–V015), loan details, property valuations, Zillow ZPID, audit log, notification preferences |
| V026–V045 | Standard deductions (V029), spending tiers jsonb (V030), income sources (V031), property depreciation + financial fields, guardrail profiles (V037–V040), cost segregation (V042), state tax tables (V043), Roth conversion optimizer fields (V044–V045) |
| V046–V065 | Yahoo price source, system config, login activity, multi-currency (V053–V054), refresh tokens + sessions (V058–V059), MFA (V060–V062), mobile app versions (V063), stock splits (V064) |
| **V066–V072** | **Projection realism v2:** asset-class returns, security asset-class map + tenant override, per-account allocation, expected-return made optional, LTCG brackets, per-account cost basis |
| **V073–V077** | Legacy expected-return backfill, age-65 standard deduction, IRMAA tiers, `gate_on_adaptive_rules` (V076 default false → **V077 default true**) |
| **V078–V080** | **Household modelling:** `projection_accounts.owner`, `income_sources.owner` + `survivor_percent`, and `mortality_rates` (SSA qx) |

Nine repeatable `R__seed_*.sql` scripts re-run whenever their checksum changes, so reference data is
edited in place rather than by adding a new versioned migration.
