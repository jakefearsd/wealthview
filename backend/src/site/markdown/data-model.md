# Data Model

WealthView's schema spans **27 JPA entities** across 8 business domains, managed by **50 Flyway
migrations** (V001–V036 versioned + 4 repeatable seed scripts). All tables follow a consistent
set of conventions.

---

## Schema Conventions

| Convention | Rule |
|---|---|
| Primary key | `id uuid NOT NULL DEFAULT gen_random_uuid()` |
| Tenant isolation | Every table except `tenants` and `prices` carries `tenant_id uuid NOT NULL REFERENCES tenants(id)` |
| Timestamps | `created_at timestamptz NOT NULL DEFAULT now()`, `updated_at timestamptz NOT NULL DEFAULT now()` |
| Money | `numeric(19,4)` — never `float` or `double` |
| Booleans | `boolean NOT NULL DEFAULT false` — never nullable booleans |
| Text | `text` unless a hard length limit is a business rule; no `varchar` padding |
| Flexible params | `jsonb` (not `json`) for semi-structured data |

---

## Domain Map

```
┌─────────────────────────────────────────────────────────────────────────┐
│  TENANCY & AUTH          │  PORTFOLIO / INVESTMENTS                      │
│  tenants                 │  accounts          prices (shared, no tenant) │
│  users                   │  transactions      import_jobs                │
│  invite_codes            │  holdings                                     │
├─────────────────────────────────────────────────────────────────────────┤
│  RENTAL PROPERTIES       │  RETIREMENT PROJECTIONS                       │
│  properties              │  projection_scenarios                         │
│  property_income         │  projection_accounts                          │
│  property_expenses       │  spending_profiles  spending_tiers            │
│  property_valuations     │  guardrail_spending_profiles                  │
│  property_depreciation_  │  income_sources                               │
│  schedules               │  scenario_income_sources                      │
├─────────────────────────────────────────────────────────────────────────┤
│  TAX REFERENCE (shared)  │  SYSTEM                                       │
│  tax_brackets            │  audit_logs                                   │
│  standard_deductions     │  notification_preferences                     │
│  state_tax_brackets      │                                               │
│  state_standard_deducts  │                                               │
│  state_tax_surcharges    │                                               │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Entity Details by Domain

### Tenancy & Auth

**`tenants`** — top-level isolation boundary.
Columns: `id`, `name`, `created_at`.

**`users`** — members of a tenant.
Columns: `id`, `tenant_id`, `email` (unique within tenant), `password_hash` (bcrypt, cost 12+),
`role` (enum: `admin`, `member`, `viewer`), `created_at`.

**`invite_codes`** — single-use tokens gating user registration.
Columns: `id`, `tenant_id`, `code` (globally unique), `created_by` (user FK), `consumed_by`
(user FK, nullable), `consumed_at`, `expires_at`. Consumed codes are never deleted — they form
an audit trail of who was invited by whom.

---

### Portfolio / Investments

**`accounts`** — financial accounts within a tenant.
`type` enum: `brokerage`, `ira`, `traditional_401k`, `roth_401k`, `roth`, `bank`, `hsa`, `pension`.
Columns include `name`, `institution`, `type`, `currency` (default `USD`).

**`transactions`** — the immutable ledger of financial events.
`type` enum: `buy`, `sell`, `dividend`, `deposit`, `withdrawal`, `transfer_in`, `transfer_out`.
Columns: `account_id`, `date`, `type`, `symbol`, `quantity`, `price_per_unit`, `amount`,
`description`, `import_hash` (SHA-256 for deduplication). The `import_hash` uniqueness constraint
prevents the same transaction from being imported twice.

**`holdings`** — computed position summary per account+symbol.
Auto-recomputed by `HoldingsComputationService` whenever transactions change.
Columns: `account_id`, `symbol`, `quantity`, `cost_basis`, `is_manual_override` (boolean),
`as_of_date`.
Unique constraint: `(account_id, symbol)`.

**`prices`** — daily close price cache.
Composite primary key: `(symbol, date)`.
`source` enum: `manual`, `finnhub`.
No `tenant_id` — prices are shared across all tenants for the same symbol.

**`import_jobs`** — audit log of all file import operations.
`source` enum: `csv`, `ofx`, `manual`.
`status` enum: `pending`, `processing`, `completed`, `failed`.
Captures `started_at`, `completed_at`, `rows_imported`, `rows_skipped`, `error_message`.

---

### Rental Properties

**`properties`** — real estate owned within a tenant.
Key columns: `address`, `purchase_price`, `purchase_date`, `current_value`, `mortgage_balance`,
`loan_interest_rate`, `loan_term_years`, `loan_start_date`, `zillow_zpid` (nullable, for automated
valuation syncs).

**`property_income`** — income line items per property.
`category` enum: `rent`, `other`.
Columns: `property_id`, `date`, `amount`, `category`, `description`.

**`property_expenses`** — expense line items per property.
`category` enum: `mortgage`, `tax`, `insurance`, `maintenance`, `capex`, `hoa`, `mgmt_fee`, `other`.

**`property_valuations`** — historical valuation snapshots.
`source` enum: `manual`, `zillow`.
Used to compute equity growth over time on the analytics page.

**`property_depreciation_schedules`** — straight-line depreciation parameters.
Tracks `depreciable_basis`, `recovery_years`, `annual_depreciation`, `start_date`, `end_date`.
Depreciation is integrated into the Roth conversion optimizer as a tax-deductible passive loss.

---

### Retirement Projections

**`projection_scenarios`** — a named retirement simulation scenario.
Columns: `name`, `birth_year`, `retirement_age`, `end_age`, `inflation_rate`, `withdrawal_rate`,
`withdrawal_order`, `filing_status`, `state_code`, `roth_conversion_bracket_rate`,
`rmd_target_bracket_rate`, `risk_tolerance`, `dynamic_sequencing_bracket_rate`,
`spending_profile_id` (nullable FK), `guardrail_profile_id` (nullable FK),
`params_json` (jsonb for overflow params).

The `spending_profile_id` and `guardrail_profile_id` columns are **mutually exclusive** — setting
one clears the other. This is enforced in `ProjectionService.updateScenario()`.

**`projection_accounts`** — accounts linked into a scenario.
Columns: `scenario_id`, `linked_account_id` (nullable FK to `accounts`), `account_type`,
`initial_balance`, `annual_contribution`, `expected_return`, `is_hypothetical`.

**`spending_profiles`** — user-defined age-tiered spending plans.
Each profile has a name and a set of `spending_tiers` (age range → base amount + inflation config).

**`guardrail_spending_profiles`** — MC-optimized spending schedules.
Output of `MonteCarloSpendingOptimizer.optimize()`. Stores the year-by-year spending array,
phase definitions (accumulation, transition, distribution), confidence level, and
portfolio corridor guardrails.

**`income_sources`** — reusable income source definitions (Social Security, pension, part-time work).
Linked to scenarios via `scenario_income_sources` (many-to-many with extra fields: start/end age,
amount override, is_taxable).

---

### Tax Reference Tables

These tables are populated by repeatable Flyway migrations (`R__seed_tax_brackets`,
`R__seed_standard_deductions`, `R__seed_state_tax_brackets`) and cover years 2022–2025.

**`tax_brackets`** — federal marginal brackets by filing status and year.
**`standard_deductions`** — federal standard deduction by filing status and year.
**`state_tax_brackets`** — state brackets for all 50 states + DC.
**`state_standard_deductions`** — per-state standard deduction amounts.
**`state_tax_surcharges`** — California SDI and similar flat surcharges.

Tax data is projected forward beyond 2025 by the `FederalTaxCalculator` using configurable
inflation-indexing of bracket thresholds.

---

## Key Relationships

```
tenants ──< users
tenants ──< invite_codes
tenants ──< accounts ──< transactions
                     ──< holdings
                     ──< import_jobs
tenants ──< properties ──< property_income
                       ──< property_expenses
                       ──< property_valuations
                       ──< property_depreciation_schedules
tenants ──< projection_scenarios ──< projection_accounts
                                 ──< scenario_income_sources ──> income_sources
```

---

## Migration History (Summary)

| Migration | Description |
|---|---|
| V001 | Baseline: tenants, users, RBAC |
| V002 | Invite codes |
| V003 | Accounts |
| V004 | Transactions |
| V005 | Holdings with manual override flag |
| V006 | Prices composite PK (symbol, date) |
| V007 | Properties, income, expenses |
| V008 | Import jobs |
| V009 | Projection scenarios and accounts |
| V010 | Transaction deduplication hash |
| V013 | Federal tax brackets |
| V014 | Spending profiles with tier support |
| V016–V020 | Loan details, property valuations, depreciation |
| V021–V025 | Income sources, state taxes |
| V026–V036 | Guardrail profiles, Dynamic Sequencing params, IRMAA fields |
