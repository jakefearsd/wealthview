[← Back to README](../../README.md)

# Data Model Reference

WealthView's data model comprises 23 JPA entities across 8 domains. All primary keys are UUID with `DEFAULT gen_random_uuid()`. Timestamps use `timestamptz`. Monetary amounts use `numeric(19,4)`.

## Entity Relationship Diagram

```
                              ┌──────────────┐
                              │    Tenant     │
                              │──────────────│
                              │ id, name,     │
                              │ is_active      │
                              └──────┬───────┘
                                     │ 1
              ┌──────────┬───────────┼───────────┬──────────────┐
              │          │           │           │              │
              ▼ *        ▼ *         ▼ *         ▼ *            ▼ *
        ┌──────────┐ ┌────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐
        │   User   │ │Account │ │ Property │ │Projection│ │IncomeSource  │
        │──────────│ │────────│ │──────────│ │ Scenario │ │──────────────│
        │email,role│ │name,   │ │address,  │ │──────────│ │name, type,   │
        │is_super  │ │type,   │ │purchase, │ │name,     │ │annual_amount,│
        │_admin    │ │instit. │ │loan,     │ │params,   │ │tax_treatment,│
        └────┬─────┘ └───┬────┘ │deprec.  │ │spending  │ │start/end_age │
             │            │      └────┬─────┘ │_profile  │ └───────┬──────┘
             │            │           │       └────┬─────┘         │
             ▼ *          │     ┌─────┼──────┐     │          ┌────┴──────┐
        ┌──────────┐      │     │     │      │     │          │ Scenario  │
        │InviteCode│      │     ▼ *   ▼ *    ▼ *   │          │ Income    │
        └──────────┘      │  ┌─────┐┌─────┐┌─────┐ │          │ Source    │
                          │  │Inc. ││Exp. ││Valu.│ │          │(join)     │
        ┌──────────┐      │  │     ││     ││ation│ │          └───────────┘
        │Notif.    │      │  └─────┘└─────┘└─────┘ │
        │Preference│      │           │             │
        └──────────┘      │           ▼ *           ▼ *
                          │     ┌──────────┐  ┌──────────┐
        ┌──────────┐      │     │Deprec.   │  │Projection│
        │AuditLog  │      │     │Schedule  │  │ Account  │
        │(standalone)│    │     └──────────┘  └──────────┘
        └──────────┘      │
                     ┌────┼──────┐
                     │    │      │
                     ▼ *  ▼ *    ▼ *
               ┌──────┐┌──────┐┌──────┐    ┌──────────┐
               │Trans.││Hold. ││Import│    │  Price    │
               │      ││      ││ Job  │    │(global)  │
               └──────┘└──────┘└──────┘    └──────────┘

               ┌──────────┐  ┌──────────────┐
               │TaxBracket│  │Standard      │
               │(global)  │  │Deduction     │
               └──────────┘  │(global)      │
                             └──────────────┘
        ┌──────────────┐
        │Spending      │
        │Profile       │
        │(tenant-scoped)│
        └──────────────┘
```

---

## Multi-Tenancy & Authentication

### TenantEntity (`tenants`)

Organization container for data isolation. Every tenant-scoped entity has a `tenant_id` FK.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | Auto-generated |
| name | text | Tenant display name |
| is_active | boolean NOT NULL DEFAULT true | Soft-disable flag; inactive tenants cannot authenticate |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Lifecycle:** Created by super-admin via `/api/v1/admin/tenants` or automatically on first boot. Can be deactivated (soft disable) but not deleted.

### UserEntity (`users`)

Individual user account within a tenant.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| tenant_id | UUID FK → tenants | |
| email | text UNIQUE | Login identifier |
| password_hash | text | BCrypt encoded |
| role | text | `admin`, `member`, or `viewer` |
| is_super_admin | boolean DEFAULT false | Cross-tenant system administrator |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Lifecycle:** Created via registration with invite code, by `SampleDataInitializer`/`DevDataInitializer` on startup, or by super-admin. Role updated by tenant admin. Super-admin flag is set only at initialization.

### InviteCodeEntity (`invite_codes`)

Registration token that allows new users to join a tenant.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| tenant_id | UUID FK → tenants | |
| code | text UNIQUE | 8-character alphanumeric code |
| created_by | UUID FK → users | Admin who generated it |
| expires_at | timestamptz | 7 days after creation |
| consumed_by | UUID FK → users | Nullable; set on registration |
| consumed_at | timestamptz | Nullable; set on registration |
| created_at | timestamptz | |

**Lifecycle:** Generated by tenant admin → shared with invitee → consumed during registration (sets `consumed_by` + `consumed_at`) → immutable. Expired codes are rejected at registration time.

---

## Investment Accounts

### AccountEntity (`accounts`)

Container for holdings and transactions representing a financial account.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| tenant_id | UUID FK → tenants | |
| name | text | User-facing account name |
| type | text | `brokerage`, `ira`, `401k`, `roth`, `bank` |
| institution | text | Brokerage/bank name (e.g., "Fidelity") |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Lifecycle:** CRUD by user. Deletion cascades to transactions, holdings, and import jobs.

### TransactionEntity (`transactions`)

Individual financial event within an account.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| account_id | UUID FK → accounts | CASCADE DELETE |
| tenant_id | UUID FK → tenants | |
| date | date | Transaction date |
| type | text | `buy`, `sell`, `dividend`, `deposit`, `withdrawal`, `opening_balance` |
| symbol | text | Ticker symbol (nullable for deposits/withdrawals) |
| quantity | numeric(19,4) | Shares transacted |
| amount | numeric(19,4) NOT NULL | Dollar amount |
| import_hash | text | SHA-256 content hash for deduplication |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Lifecycle:** Created manually, via CSV/OFX import, or as opening balances. Duplicate imports are rejected by `import_hash` matching. On create/update/delete, the `HoldingsService` recomputes holdings for the affected account + symbol.

### HoldingEntity (`holdings`)

Aggregated position for a single symbol within an account.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| account_id | UUID FK → accounts | CASCADE DELETE |
| tenant_id | UUID FK → tenants | |
| symbol | text | Ticker symbol |
| quantity | numeric(19,4) DEFAULT 0 | Net shares held |
| cost_basis | numeric(19,4) DEFAULT 0 | Total cost basis |
| is_manual_override | boolean DEFAULT false | When true, auto-recomputation is skipped |
| is_money_market | boolean DEFAULT false | Money market fund flag |
| money_market_rate | numeric(7,4) | Annual yield (e.g., 0.0497 = 4.97%) |
| as_of_date | date | Last computation date |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Constraints:** Unique (account_id, symbol).

**Lifecycle:** Automatically computed by aggregating all buy/sell transactions for the account + symbol pair. Setting `is_manual_override = true` preserves the holding values and skips recomputation. Money market holdings use `money_market_rate` for valuation instead of price lookups.

### ImportJobEntity (`import_jobs`)

Tracks the status and results of a data import operation.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| tenant_id | UUID FK → tenants | |
| account_id | UUID FK → accounts | |
| source | text | `csv`, `ofx`, `manual`, `positions` |
| status | text | `pending`, `processing`, `completed`, `failed` |
| total_rows | integer | Total rows in source file |
| successful_rows | integer | Rows successfully imported |
| failed_rows | integer | Rows that failed (duplicates or parse errors) |
| error_message | text | Error details if status = failed |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Lifecycle:** Created as `pending` when import starts → transitions to `processing` → ends as `completed` (with row counts) or `failed` (with error message). Import jobs are immutable after completion.

---

## Prices

### PriceEntity (`prices`)

Daily closing price for a ticker symbol. This is global reference data, not tenant-scoped.

| Field | Type | Notes |
|-------|------|-------|
| symbol | text | Composite PK part 1 (via `@IdClass(PriceId)`) |
| date | date | Composite PK part 2 |
| close_price | numeric(19,4) | Closing price |
| source | text | `manual` or `finnhub` |
| created_at | timestamptz | |

**Lifecycle:** Seeded by `R__seed_stock_prices` repeatable migration on startup. Updated daily by `PriceSyncScheduler` (weekdays at 4:30 PM) via Finnhub API. Can also be entered manually via the API. Price data is append-only; existing prices are not overwritten.

---

## Properties

### PropertyEntity (`properties`)

Real estate asset with optional loan and depreciation details.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| tenant_id | UUID FK → tenants | |
| address | text | Property address |
| purchase_price | numeric(19,4) | Original purchase price |
| purchase_date | date | Date of purchase |
| current_value | numeric(19,4) | Current market value |
| mortgage_balance | numeric(19,4) | Manual mortgage balance |
| property_type | text DEFAULT 'primary_residence' | `primary_residence`, `investment`, `vacation` |
| loan_amount | numeric(19,4) | Original loan amount (nullable) |
| annual_interest_rate | numeric(19,4) | Annual rate as decimal (nullable) |
| loan_term_months | integer | Loan term in months (nullable) |
| loan_start_date | date | Loan origination date (nullable) |
| use_computed_balance | boolean DEFAULT false | Use amortization-computed balance instead of manual |
| zillow_zpid | text | Zillow property ID for automated valuation |
| in_service_date | date | Date placed in service for depreciation |
| land_value | numeric(19,4) | Non-depreciable land value |
| depreciation_method | text DEFAULT 'none' | `none`, `straight_line`, `cost_segregation` |
| useful_life_years | numeric(4,1) | Depreciation period (e.g., 27.5 for residential) |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Lifecycle:** CRUD by user. Loan fields must be provided in full or not at all (partial loan details return 400). When `use_computed_balance` is true, mortgage balance is calculated via amortization formula instead of using the manual value. Zillow sync updates `current_value` and creates a `PropertyValuationEntity`. Helper methods: `hasLoanDetails()`, `getEquity()`.

### PropertyIncomeEntity (`property_income`)

Rental or other income associated with a property.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| property_id | UUID FK → properties | CASCADE DELETE |
| tenant_id | UUID FK → tenants | |
| date | date | Income date |
| amount | numeric(19,4) | Income amount |
| category | text | `rent`, `other` |
| description | text | Optional description |
| frequency | text DEFAULT 'monthly' | `monthly`, `annual` |
| created_at | timestamptz | |
| updated_at | timestamptz | |

### PropertyExpenseEntity (`property_expenses`)

Cost associated with a property.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| property_id | UUID FK → properties | CASCADE DELETE |
| tenant_id | UUID FK → tenants | |
| date | date | Expense date |
| amount | numeric(19,4) | Expense amount |
| category | text | `mortgage`, `tax`, `insurance`, `maintenance`, `capex`, `hoa`, `mgmt_fee` |
| description | text | Optional description |
| frequency | text DEFAULT 'monthly' | `monthly`, `annual` |
| created_at | timestamptz | |
| updated_at | timestamptz | |

### PropertyValuationEntity (`property_valuations`)

Historical property value assessment from various sources.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| property_id | UUID FK → properties | CASCADE DELETE |
| tenant_id | UUID FK → tenants | |
| valuation_date | date | Date of assessment |
| value | numeric(19,4) | Assessed value |
| source | text | `manual`, `zillow`, `appraisal` |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Constraints:** Unique (property_id, source, valuation_date).

**Lifecycle:** Created manually, via Zillow automated scraping (Sunday 6 AM weekly job), or imported as appraisal data. Zillow valuations also update the property's `current_value`.

### PropertyDepreciationScheduleEntity (`property_depreciation_schedule`)

Year-by-year depreciation amounts for cost segregation studies.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| property_id | UUID FK → properties | CASCADE DELETE |
| tenant_id | UUID FK → tenants | |
| tax_year | integer | Calendar year |
| depreciation_amount | numeric(19,4) | Annual depreciation for that year |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Constraints:** Unique (property_id, tax_year).

**Lifecycle:** Created when cost segregation study results are entered for an investment property. Used by the projection engine to compute rental income tax deductions. For straight-line depreciation, amounts are computed dynamically (no schedule entries needed).

---

## Retirement Projections

### ProjectionScenarioEntity (`projection_scenarios`)

Named retirement projection configuration with parameters and linked accounts.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| tenant_id | UUID FK → tenants | |
| name | text | Scenario display name |
| retirement_date | date | Planned retirement date |
| end_age | integer | Age at which projection ends |
| inflation_rate | numeric(5,4) | Annual inflation rate (e.g., 0.0300) |
| params_json | jsonb | Withdrawal strategy, filing status, Roth conversion params, other income |
| spending_profile_id | UUID FK → spending_profiles | Optional link to spending profile |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Relationships:** OneToMany → `ProjectionAccountEntity` (CascadeType.ALL, orphanRemoval). ManyToMany → `IncomeSourceEntity` via `ScenarioIncomeSourceEntity` join table.

**Lifecycle:** Created by user with parameters and accounts → optionally linked to spending profile and income sources → run on demand via `/compute/{id}` → results returned (not persisted). Scenarios can be edited and re-run repeatedly. Deletion cascades to projection accounts and scenario-income-source links.

### ProjectionAccountEntity (`projection_accounts`)

An investment pool within a projection scenario.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| scenario_id | UUID FK → projection_scenarios | CASCADE ALL |
| linked_account_id | UUID FK → accounts | Optional; resolves initial balance at runtime |
| initial_balance | numeric(19,4) | Nullable when linked to a real account |
| annual_contribution | numeric(19,4) | Pre-retirement annual contribution |
| expected_return | numeric(5,4) DEFAULT 0.07 | Expected annual return rate |
| account_type | text DEFAULT 'taxable' | `traditional`, `roth`, `taxable` |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Lifecycle:** Created as part of a scenario. When `linked_account_id` is set, the engine resolves `initial_balance` from the linked account's current holdings value at projection run time. Orphan removal ensures accounts are deleted when removed from a scenario.

### SpendingProfileEntity (`spending_profiles`)

Retirement spending definition with optional age-based tiers.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| tenant_id | UUID FK → tenants | |
| name | text | Profile display name |
| essential_expenses | numeric(19,4) | Annual essential spending |
| discretionary_expenses | numeric(19,4) | Annual discretionary spending |
| income_streams | jsonb DEFAULT '[]' | **Deprecated** -- legacy income streams, migrated to `IncomeSourceEntity` via V033 |
| spending_tiers | jsonb DEFAULT '[]' | Age-based spending phases (array of `{label, startAge, essentialExpenses, discretionaryExpenses}`) |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Lifecycle:** Created by user → linked to one or more projection scenarios. Spending tiers define age-based phases (e.g., "Active Retirement" at 65 with $50k spending, "Quiet Years" at 80 with $30k). When a scenario runs, the engine uses tiers to adjust spending at the appropriate ages. Per-tier inflation compounds from the later of the tier's start age or the retirement start age.

---

## Income Sources

### IncomeSourceEntity (`income_sources`)

First-class income definition reusable across projection scenarios.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| tenant_id | UUID FK → tenants | |
| name | text | Display name (e.g., "Social Security - Primary") |
| income_type | text | `rental_property`, `social_security`, `pension`, `part_time_work`, `annuity`, `other` |
| annual_amount | numeric(19,4) | Base annual income |
| start_age | integer | Age when income begins |
| end_age | integer | Age when income ends (nullable = lifetime) |
| inflation_rate | numeric(7,5) DEFAULT 0 | Annual inflation adjustment rate |
| one_time | boolean DEFAULT false | One-time payment vs recurring |
| tax_treatment | text DEFAULT 'taxable' | `taxable`, `partially_taxable`, `tax_free`, `rental_passive`, `rental_active_reps`, `rental_active_str`, `self_employment` |
| property_id | UUID FK → properties | Optional; links rental income to a specific property for depreciation deductions |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Tax treatment details:**
- `taxable` -- fully taxable as ordinary income
- `partially_taxable` -- Social Security 85% provisional income rule
- `tax_free` -- Roth distributions, municipal bond interest
- `rental_passive` -- passive activity loss rules ($25k max deduction, phases out at $100k-$150k AGI)
- `rental_active_reps` -- real estate professional status (no passive loss limit)
- `rental_active_str` -- short-term rental material participation (no passive loss limit)
- `self_employment` -- subject to 15.3% SE tax (12.4% Social Security up to wage base + 2.9% Medicare)

**Lifecycle:** Created by user → linked to scenarios via `ScenarioIncomeSourceEntity` → income applied during projection runs with appropriate tax calculations. Legacy `income_streams` JSON in spending profiles was migrated to these entities via V033.

### ScenarioIncomeSourceEntity (`scenario_income_sources`)

Join table linking income sources to projection scenarios with optional per-scenario overrides.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| scenario_id | UUID FK → projection_scenarios | |
| income_source_id | UUID FK → income_sources | |
| override_annual_amount | numeric(19,4) | Nullable; overrides the income source's base amount for this scenario |
| created_at | timestamptz | |

**Constraints:** Unique (scenario_id, income_source_id).

**Lifecycle:** Created when a user links an income source to a scenario. The override amount allows the same income source (e.g., "Social Security") to be used in multiple scenarios with different assumed amounts.

---

## Tax Reference Data

### TaxBracketEntity (`tax_brackets`)

Federal income tax brackets by year and filing status. Global reference data (not tenant-scoped).

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| tax_year | integer | Calendar year |
| filing_status | text | `single`, `married_filing_jointly` |
| bracket_floor | numeric(19,4) | Income threshold (lower bound) |
| bracket_ceiling | numeric(19,4) | Income threshold (upper bound; nullable for top bracket) |
| rate | numeric(5,4) | Marginal tax rate (e.g., 0.2200 = 22%) |
| created_at | timestamptz | |

**Lifecycle:** Seeded by `R__seed_tax_brackets` repeatable migration (2022-2025 data). The `FederalTaxCalculator` looks up brackets by year with fallback to the latest available year. Used during projection runs to compute tax on Roth conversions, traditional withdrawals, and taxable income sources.

### StandardDeductionEntity (`standard_deductions`)

Federal standard deduction amounts by year and filing status. Global reference data.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| tax_year | integer | Calendar year |
| filing_status | text | `single`, `married_filing_jointly` |
| amount | numeric(19,4) | Standard deduction amount |
| created_at | timestamptz | |

**Lifecycle:** Seeded by `R__seed_standard_deductions` repeatable migration (2022-2025). The `FederalTaxCalculator` subtracts the standard deduction from gross income before applying bracket math. Year fallback works the same as tax brackets.

---

## System

### AuditLogEntity (`audit_log`)

Immutable event log for tracking user actions across the system.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| tenant_id | UUID | Raw UUID (no FK for durability) |
| user_id | UUID | Raw UUID (no FK for durability) |
| action | text | Action performed (e.g., "CREATE", "UPDATE", "DELETE", "LOGIN") |
| entity_type | text | Type of entity affected (e.g., "Account", "Property") |
| entity_id | UUID | ID of affected entity |
| details | jsonb | Additional context (before/after values, metadata) |
| ip_address | text | Client IP address |
| created_at | timestamptz | Event timestamp |

**Lifecycle:** Append-only. Events are written by service methods and never updated or deleted. Uses raw UUIDs (not foreign keys) so that audit records survive even if the referenced tenant, user, or entity is deleted.

### NotificationPreferenceEntity (`notification_preferences`)

Per-user notification settings.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID PK | |
| user_id | UUID FK → users | |
| notification_type | text | Type identifier (e.g., "price_alert", "import_complete") |
| enabled | boolean DEFAULT true | Whether notifications of this type are enabled |
| created_at | timestamptz | |
| updated_at | timestamptz | |

**Constraints:** Unique (user_id, notification_type).

---

## Entity Lifecycle Patterns

| Pattern | Entities | Description |
|---------|----------|-------------|
| **CRUD** | Account, Property, ProjectionScenario, SpendingProfile, IncomeSource | Standard create-read-update-delete with tenant isolation |
| **Cascade delete** | Transaction, Holding, ImportJob, PropertyIncome, PropertyExpense, PropertyValuation, PropertyDepreciationSchedule, ProjectionAccount, ScenarioIncomeSource | Automatically deleted when parent entity is removed |
| **State machine** | ImportJob (`pending` → `processing` → `completed`/`failed`) | Transitions through defined states; immutable after terminal state |
| **Consume-once token** | InviteCode (generated → consumed → immutable) | Created active, consumed exactly once, then frozen |
| **Append-only** | AuditLog, Price | Written once, never updated or deleted |
| **Auto-computed** | Holding (from transactions), PropertyValuation (from Zillow sync) | Derived from source data; manual override available for Holdings |
| **Reference data** | TaxBracket, StandardDeduction, Price | Global (not tenant-scoped), seeded by repeatable migrations |
| **Projection workflow** | Scenario → configure accounts + income → run → compare | Configuration is persisted; results are computed on-demand (not stored) |

---

## Flyway Migration Inventory

Flyway migrations are in `backend/wealthview-persistence/src/main/resources/db/migration/`. They run automatically on application startup.

### Versioned Migrations

| Migration | Description                                              |
|-----------|----------------------------------------------------------|
| V001      | Baseline tenants and users tables                        |
| V002      | Invite codes table                                       |
| V003      | Accounts table                                           |
| V004      | Transactions table                                       |
| V005      | Holdings table with manual override flag                 |
| V006      | Prices table (composite PK: symbol + date)               |
| V007      | Properties tables (properties, income, expenses)         |
| V008      | Import jobs table                                        |
| V009      | Projection tables (scenarios, accounts)                  |
| V010      | Transaction hash column for deduplication                |
| V011      | Opening balance transaction type                         |
| V012      | Account type column on projection accounts               |
| V013      | Tax brackets table                                       |
| V014      | Spending profiles table (essential/discretionary/income streams) |
| V015      | Add spending_profile_id FK to projection scenarios       |
| V016      | Loan detail columns on properties (amortization support) |
| V017      | Property valuations history table                        |
| V018      | Add property_type column to properties (primary_residence, investment, vacation) |
| V019      | Add frequency column to property income and expenses (monthly/annual) |
| V020      | Add zillow_zpid column to properties for Zillow API lookups |
| V021      | Add money market fields to holdings (is_money_market, money_market_rate) |
| V022      | Add positions source type to import jobs                 |
| V023      | Add is_active column to tenants for admin enable/disable |
| V024      | Create audit log table (action, entity tracking, JSONB details) |
| V025      | Create notification preferences table                    |
| V026      | Add income_inflation_rate to spending profiles           |
| V027      | Migrate income_inflation_rate into per-stream JSON elements |
| V028      | Make projection account initial_balance nullable for linked accounts |
| V029      | Create standard deductions table                         |
| V030      | Add spending_tiers JSONB column for age-based spending phases |
| V031      | Create income sources and scenario income sources tables |
| V032      | Add property depreciation fields and schedule table      |
| V033      | Migrate legacy income_streams JSON to income_sources entities |

### Repeatable Migrations

| Migration | Description |
|-----------|-------------|
| R__seed_stock_prices | Historical stock prices for AAPL, AMZN, BND, FXAIX, GOOG, MSFT, NVDA, SCHD, VOO, VTI, VUG, VXUS (5000+ data points per symbol, 2006-present) |
| R__seed_tax_brackets | Federal tax brackets for 2022-2025 (single + married filing jointly) |
| R__seed_standard_deductions | Federal standard deduction amounts for 2022-2025 by filing status |

**Important:** Versioned migrations are immutable once released. Never edit a committed migration file -- create a new one instead.

---

## Related Docs

- [Architecture](architecture.md) — Module structure and dependency rules
- [API Reference](api-reference.md) — Full endpoint documentation
