[← Back to README](../../README.md)

# Data Model Reference

WealthView's data model comprises 42 JPA entities mapped onto 42 PostgreSQL tables across 9 domains. Almost all primary keys are UUID with `DEFAULT gen_random_uuid()`; the exceptions are `prices` (composite `(symbol, date)`), `system_config` (text `key`), and `mobile_app_versions` (text `platform`). Timestamps use `timestamptz`. Monetary amounts use `numeric(19,4)`.

The schema is at **V080**, built from **89 migration files** — 80 versioned (`V001`–`V080`) plus 9 repeatable seeds (`R__…`) — in `backend/wealthview-persistence/src/main/resources/db/migration/`.

## Entity Relationship Diagram

```
                                    ┌──────────────┐
                                    │    Tenant     │
                                    │──────────────│
                                    │ id, name,     │
                                    │ is_active     │
                                    └──────┬───────┘
                                           │ 1
        ┌──────────┬──────────┬────────────┼────────────┬───────────┬──────────┐
        ▼ *        ▼ *        ▼ *          ▼ *          ▼ *         ▼ *        ▼ *
   ┌────────┐ ┌────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────┐ ┌──────────┐
   │  User  │ │Account │ │ Property │ │Projection│ │  Income  │ │Spending│ │ Exchange │
   │────────│ │────────│ │──────────│ │ Scenario │ │  Source  │ │Profile │ │   Rate   │
   │email,  │ │name,   │ │address,  │ │──────────│ │──────────│ │────────│ └──────────┘
   │role,   │ │type,   │ │purchase, │ │name,     │ │type,     │ │essent.,│
   │mfa_*,  │ │instit.,│ │loan,     │ │params,   │ │amount,   │ │discr., │ ┌──────────┐
   │version │ │currency│ │deprec.   │ │spending_ │ │owner,    │ │tiers   │ │SecurityCl│
   └───┬────┘ └───┬────┘ └────┬─────┘ │profile / │ │survivor% │ └───┬────┘ │ Override │
       │          │           │       │guardrail │ └────┬─────┘     │      └──────────┘
       │          │           │       │(XOR)     │      │           │
       │          │           │       └────┬─────┘      │           │
       │          │           │            │            │           │
  ┌────┴───┐      │     ┌─────┼──────┬─────┼──┐    ┌────┴─────┐     │
  ▼ *      ▼ *    │     ▼ *   ▼ *    ▼ *   │  │    │ Scenario │     │
┌──────┐┌───────┐ │  ┌─────┐┌─────┐┌─────┐ │  │    │  Income  │◄────┘ (scenario
│Invite││Refresh│ │  │Prop.││Prop.││Prop.│ │  │    │  Source  │        links only)
│ Code ││ Token │ │  │Inc. ││Exp. ││Val. │ │  │    │  (join)  │
└──────┘└───────┘ │  └─────┘└─────┘└─────┘ │  │    └──────────┘
┌──────┐┌───────┐ │         ┌──────────┐   │  │
│ User ││  MFA  │ │         │  Prop.   │   │  └──►┌──────────────┐
│Sessn.││Recov./│ │         │ Deprec.  │   │      │  Guardrail   │
└──────┘│Challg.│ │         │ Schedule │   │      │  Spending    │
        └───────┘ │         └──────────┘   │      │  Profile     │
                  │                         │     └──────────────┘
       ┌──────────┼──────────┐              ▼ *
       ▼ *        ▼ *        ▼ *      ┌──────────┐
  ┌────────┐ ┌────────┐ ┌────────┐    │Projection│
  │ Trans- │ │Holding │ │ Import │    │ Account  │
  │ action │ │        │ │  Job   │    │(alloc.,  │
  └────────┘ └────────┘ └────────┘    │ owner)   │
                                       └──────────┘

  Global reference data (NOT tenant-scoped)
  ┌──────────┐ ┌───────────┐ ┌──────────┐ ┌────────────┐ ┌────────────┐
  │  Price   │ │StockSplit │ │TaxBracket│ │  Standard  │ │   Ltcg     │
  │(sym,date)│ │           │ │          │ │ Deduction  │ │  Bracket   │
  └──────────┘ └─────┬─────┘ └──────────┘ └────────────┘ └────────────┘
                     │ 1                  ┌────────────┐ ┌────────────┐
                     ▼ *                  │ IrmaaTier  │ │ Mortality  │
              ┌──────────────┐            └────────────┘ │    Rate    │
              │ StockSplit   │                           └────────────┘
              │ Adjustment   │  ┌────────────┐ ┌────────────┐ ┌────────────┐
              │(tenant_id)   │  │ AssetClass │ │  Security  │ │ StateTax   │
              └──────────────┘  │  Return    │ │ AssetClass │ │Bracket/Ded │
                                └────────────┘ └────────────┘ │ /Surcharge │
                                                              └────────────┘

  Standalone / system
  ┌──────────┐ ┌──────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐
  │ AuditLog │ │Notification  │ │  System    │ │ MobileApp  │ │   Login    │
  │          │ │ Preference   │ │  Config    │ │  Version   │ │  Activity  │
  └──────────┘ └──────────────┘ └────────────┘ └────────────┘ └────────────┘
```

Two tables carry a `tenant_id` column with **no** foreign key: `login_activity` (an attempt may
not resolve to a real tenant) and `security_class_override`. `stock_split_adjustments.tenant_id`
and `audit_log.tenant_id`/`user_id` *are* real FKs but are mapped as raw `UUID` fields in JPA
rather than as associations.

---

## Multi-Tenancy & Authentication

### TenantEntity (`tenants`)

Organization container for data isolation. Every tenant-scoped entity has a `tenant_id` FK.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | `DEFAULT gen_random_uuid()` |
| name | text NOT NULL | Tenant display name |
| is_active | boolean NOT NULL DEFAULT true | Soft-disable flag; inactive tenants cannot authenticate (V023) |
| created_at | timestamptz NOT NULL DEFAULT now() | |
| updated_at | timestamptz NOT NULL DEFAULT now() | |

**Lifecycle:** Created by super-admin via `/api/v1/admin/tenants` or automatically on first boot. Can be deactivated (soft disable) but not deleted.

### UserEntity (`users`)

Individual user account within a tenant.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants | |
| email | text NOT NULL UNIQUE | Login identifier |
| password_hash | text NOT NULL | BCrypt encoded |
| role | text NOT NULL | CHECK IN (`admin`, `member`, `viewer`) |
| is_super_admin | boolean NOT NULL DEFAULT false | Cross-tenant system administrator |
| is_active | boolean NOT NULL DEFAULT true | Disable an account without deleting it (V048) |
| token_generation | integer NOT NULL DEFAULT 0 | Bumping this revokes every refresh token for the user (V052) |
| version | bigint NOT NULL DEFAULT 0 | JPA `@Version` optimistic lock; racing refreshes fail loudly (V057) |
| mfa_enabled | boolean NOT NULL DEFAULT false | Flips true only after a setup code is verified (V060) |
| mfa_secret_encrypted | text | AES-GCM encrypted Base32 TOTP shared secret |
| mfa_setup_at | timestamptz | When MFA was confirmed |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_users_tenant_id`, `idx_users_email`.

**Lifecycle:** Created via registration with an invite code, by `SampleDataInitializer`/`DevDataInitializer` on startup, or by super-admin. Role updated by tenant admin. Super-admin flag is set only at initialization.

### InviteCodeEntity (`invite_codes`)

Registration token that allows new users to join a tenant.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants | |
| code | text NOT NULL UNIQUE | 24 characters from a Crockford-style base32 alphabet (`0-9A-Z` minus I/L/O/U), `SecureRandom` |
| created_by | uuid NOT NULL FK → users | Admin who generated it |
| consumed_by | uuid FK → users | Nullable; set on registration |
| consumed_at | timestamptz | Nullable; set on registration |
| expires_at | timestamptz NOT NULL | Caller-supplied expiry; the API defaults to 7 days |
| is_revoked | boolean NOT NULL DEFAULT false | Admin revocation of an outstanding invite (V049) |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_invite_codes_tenant_id`, `idx_invite_codes_code`.

**Lifecycle:** Generated by tenant admin → shared with invitee → consumed during registration (sets `consumed_by` + `consumed_at`) → immutable. Expired or revoked codes are rejected at registration time. Used codes can be bulk-deleted via `DELETE /api/v1/tenant/invite-codes/used`.

### RefreshTokenEntity (`refresh_tokens`)

One row per issued refresh token, keyed by JTI, so each token can be consumed exactly once.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants ON DELETE CASCADE | Mapped as a raw UUID column in JPA |
| user_id | uuid NOT NULL FK → users ON DELETE CASCADE | Mapped as a raw UUID column in JPA |
| session_id | uuid | Links the token to a `user_sessions` row |
| jti | uuid NOT NULL UNIQUE | JWT ID of the refresh token |
| issued_at | timestamptz NOT NULL | |
| expires_at | timestamptz NOT NULL | |
| used_at | timestamptz | Set when the token is exchanged |
| replaced_by_jti | uuid | Successor token in the rotation chain |
| revoked_at | timestamptz | |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_refresh_tokens_user_used (user_id, used_at)`.

**Lifecycle:** Issued at login, consumed exactly once at refresh, and replaced by a successor. Reuse of an already-consumed JTI is treated as compromise: `AuthService` increments the user's `token_generation`, revoking every outstanding token (V058).

### UserSessionEntity (`user_sessions`)

Per-device session record. Access tokens carry the session id in a `sid` claim.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants ON DELETE CASCADE | Raw UUID column in JPA |
| user_id | uuid NOT NULL FK → users ON DELETE CASCADE | Raw UUID column in JPA |
| device_label | text | Optional friendly name |
| transport | text NOT NULL | CHECK IN (`cookie`, `bearer`) — web vs. mobile |
| ip_address | text | |
| user_agent | text | |
| created_at | timestamptz NOT NULL DEFAULT now() | |
| last_used_at | timestamptz NOT NULL DEFAULT now() | |
| revoked_at | timestamptz | Set when the session is revoked |

**Indexes:** `idx_user_sessions_user_revoked (user_id, revoked_at)`.

**Lifecycle:** Created on each successful login. Users list and revoke sessions via `/api/v1/auth/sessions`; revoking one session leaves other devices signed in, unlike `token_generation` which is a single per-user counter (V059).

### MfaRecoveryCodeEntity (`mfa_recovery_codes`)

BCrypt-hashed single-use MFA recovery codes.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants ON DELETE CASCADE | |
| user_id | uuid NOT NULL FK → users ON DELETE CASCADE | |
| code_hash | text NOT NULL | BCrypt hash; plaintext is shown once and never stored |
| used_at | timestamptz | Set when redeemed |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_mfa_recovery_codes_user_used (user_id, used_at)`.

### MfaChallengeEntity (`mfa_challenges`)

Single-use MFA challenge tokens issued between a successful password check and TOTP verification.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants ON DELETE CASCADE | |
| user_id | uuid NOT NULL FK → users ON DELETE CASCADE | |
| jti | uuid NOT NULL UNIQUE | JTI of the short-lived (5 min) challenge JWT |
| transport | text NOT NULL | CHECK IN (`cookie`, `bearer`) |
| expires_at | timestamptz NOT NULL | |
| used_at | timestamptz | Reuse of the same JTI returns 401 |
| created_at | timestamptz NOT NULL | |

**Indexes:** `idx_mfa_challenges_user (user_id)`.

### LoginActivityEntity (`login_activity`)

Authentication attempt log backing the admin audit view.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| user_email | text NOT NULL | Email as submitted (may not match a real user) |
| tenant_id | uuid | Nullable, **no FK** — an attempt may not resolve to a tenant |
| success | boolean NOT NULL | |
| ip_address | text | |
| created_at | timestamptz NOT NULL DEFAULT now() | |

**Indexes:** `idx_login_activity_created_at (created_at DESC)`, `idx_login_activity_tenant_created_at (tenant_id, created_at DESC)` (V056).

---

## Investment Accounts & Currency

### AccountEntity (`accounts`)

Container for holdings and transactions representing a financial account.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants | |
| name | text NOT NULL | User-facing account name |
| type | text NOT NULL | CHECK IN (`brokerage`, `ira`, `401k`, `roth`, `bank`) |
| institution | text | Brokerage/bank name (e.g., "Fidelity") |
| currency | text NOT NULL DEFAULT 'USD' | ISO 4217 code (V053) |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_accounts_tenant_id`.

**Lifecycle:** CRUD by user. Deletion cascades at the database level to `transactions` and `holdings` (both declared `ON DELETE CASCADE`); `import_jobs.account_id` and `projection_accounts.linked_account_id` are plain FKs with no cascade. Non-USD balances are converted at display/aggregation boundaries using the tenant's `exchange_rates`.

### TransactionEntity (`transactions`)

Individual financial event within an account.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| account_id | uuid NOT NULL FK → accounts ON DELETE CASCADE | |
| tenant_id | uuid NOT NULL FK → tenants | |
| date | date NOT NULL | Transaction date |
| type | text NOT NULL | CHECK IN (`buy`, `sell`, `dividend`, `deposit`, `withdrawal`, `opening_balance`); mapped to the `TransactionType` enum via `TransactionTypeConverter` |
| symbol | text | Ticker symbol (nullable for deposits/withdrawals) |
| quantity | numeric(19,4) | Shares transacted |
| amount | numeric(19,4) NOT NULL | Dollar amount |
| import_hash | text | SHA-256 content hash for deduplication (V010) |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_transactions_account_id`, `idx_transactions_tenant_id`, `idx_transactions_account_id_symbol`, `idx_transactions_import_hash (tenant_id, account_id, import_hash)`, `idx_transactions_tenant_id_symbol` (V065, added for the stock-split recompute path).

**Lifecycle:** Created manually, via CSV/OFX import, or as opening balances. Duplicate imports are rejected by `import_hash` matching. On create/update/delete, the `HoldingsService` recomputes holdings for the affected account + symbol. Stock split application rewrites `quantity` (and records the before/after in `stock_split_adjustments`).

### HoldingEntity (`holdings`)

Aggregated position for a single symbol within an account.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| account_id | uuid NOT NULL FK → accounts ON DELETE CASCADE | |
| tenant_id | uuid NOT NULL FK → tenants | |
| symbol | text NOT NULL | Ticker symbol |
| quantity | numeric(19,4) NOT NULL DEFAULT 0 | Net shares held |
| cost_basis | numeric(19,4) NOT NULL DEFAULT 0 | Total cost basis |
| is_manual_override | boolean NOT NULL DEFAULT false | When true, auto-recomputation is skipped |
| is_money_market | boolean NOT NULL DEFAULT false | Money market fund flag (V021) |
| money_market_rate | numeric(7,4) | Annual yield (e.g., 0.0497 = 4.97%) |
| as_of_date | date NOT NULL DEFAULT CURRENT_DATE | Last computation date |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_holdings_account_symbol UNIQUE (account_id, symbol)`. **Indexes:** `idx_holdings_account_id`, `idx_holdings_tenant_id`.

**Lifecycle:** Automatically computed by aggregating all buy/sell transactions for the account + symbol pair. Setting `is_manual_override = true` preserves the holding values and skips recomputation. Money market holdings use `money_market_rate` for valuation instead of price lookups.

### ImportJobEntity (`import_jobs`)

Tracks the status and results of a data import operation.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants | |
| account_id | uuid NOT NULL FK → accounts | No cascade |
| source | text NOT NULL | CHECK IN (`csv`, `ofx`, `manual`, `positions`) (V022) |
| status | text NOT NULL | CHECK IN (`pending`, `processing`, `completed`, `failed`) |
| total_rows | integer NOT NULL DEFAULT 0 | Total rows in source file |
| successful_rows | integer NOT NULL DEFAULT 0 | Rows successfully imported |
| failed_rows | integer NOT NULL DEFAULT 0 | Rows that failed (duplicates or parse errors) |
| error_message | text | Error details if status = `failed` |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_import_jobs_tenant_id`.

**Lifecycle:** Created as `pending` when import starts → transitions to `processing` → ends as `completed` (with row counts) or `failed` (with error message). Import jobs are immutable after completion.

### ExchangeRateEntity (`exchange_rates`)

Tenant-scoped manual currency conversion rates (V054).

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants | |
| currency_code | text NOT NULL | ISO 4217 code |
| rate_to_usd | numeric(19,8) NOT NULL | Multiply an amount in this currency to get USD |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_exchange_rates_tenant_currency UNIQUE (tenant_id, currency_code)`. **Indexes:** `idx_exchange_rates_tenant_id`.

**Lifecycle:** Entered and maintained by the user via `/api/v1/exchange-rates` — one rate per currency per tenant, no automated feed. Applied at display/aggregation time, never stored back onto account balances.

---

## Prices & Corporate Actions

### PriceEntity (`prices`)

Daily closing price for a ticker symbol. Global reference data, not tenant-scoped.

| Field | Type | Notes |
|-------|------|-------|
| symbol | text NOT NULL | Composite PK part 1 (via `@IdClass(PriceId)`) |
| date | date NOT NULL | Composite PK part 2 |
| close_price | numeric(19,4) NOT NULL | Closing price |
| source | text NOT NULL | CHECK IN (`manual`, `finnhub`, `yahoo`) — `yahoo` added by V047 |
| created_at | timestamptz NOT NULL DEFAULT now() | |

**Lifecycle:** Seeded by the `R__seed_stock_prices` repeatable migration (which deletes and re-inserts all rows whenever its checksum changes). Refreshed on weekdays by a single scheduled trigger — `PriceSyncService`'s own job at `app.finnhub.sync-cron` (default 6:00 PM ET) — plus manual entry and Yahoo import via `/api/v1/admin/prices/*`. Split application rewrites historical `close_price` values.

### StockSplitEntity (`stock_splits`)

Global source of truth for split events — a split applies to every tenant holding the symbol, so there is no `tenant_id` (V064).

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| symbol | text NOT NULL | Ticker symbol |
| effective_date | date NOT NULL | Split effective date |
| numerator | integer NOT NULL | CHECK > 0 — e.g., 4 for a 4-for-1 split |
| denominator | integer NOT NULL | CHECK > 0 |
| source | text NOT NULL | CHECK IN (`finnhub`, `yahoo`, `manual`, `backfill`) |
| applied_at | timestamptz NOT NULL DEFAULT now() | When the adjustment was applied |
| notes | text | |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_stock_splits_symbol_date UNIQUE (symbol, effective_date)`. **Indexes:** `idx_stock_splits_symbol_date`.

**Lifecycle:** Detected by the daily `StockSplitSyncService` (cron `app.stock-splits.sync-cron`, default 2:00 AM ET, Finnhub) and by the one-time `StockSplitBackfillRunner`. Manual entry and un-apply live under `/api/v1/admin/stock-splits`.

### StockSplitAdjustmentEntity (`stock_split_adjustments`)

Per-tenant audit/undo trail: one split produces many adjustment rows.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| split_id | uuid NOT NULL FK → stock_splits ON DELETE CASCADE | |
| tenant_id | uuid NOT NULL FK → tenants | Raw UUID column in JPA |
| target_table | text NOT NULL | CHECK IN (`transactions`, `prices`, `holdings`) |
| target_row_id | uuid NOT NULL | Id of the adjusted row |
| field_name | text NOT NULL | Column that was rewritten |
| old_value | numeric(19,8) NOT NULL | Value before the adjustment |
| new_value | numeric(19,8) NOT NULL | Value after the adjustment |
| created_at | timestamptz NOT NULL | |

**Indexes:** `idx_split_adjustments_split`, `idx_split_adjustments_tenant`.

**Lifecycle:** Append-only while a split is applied; read back verbatim to reverse a split (un-apply). Adjustments to the *global* `prices` table are recorded once, under whichever tenant triggered the apply — first writer wins, so un-apply restores each price exactly once.

---

## Properties

### PropertyEntity (`properties`)

Real estate asset with optional loan and depreciation details. The Java entity groups the loan columns into an `@Embedded LoanDetails` and the depreciation columns into an `@Embedded DepreciationSettings`; the physical columns are unchanged.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants | |
| address | text NOT NULL | Property address |
| purchase_price | numeric(19,4) NOT NULL | Original purchase price |
| purchase_date | date NOT NULL | Date of purchase |
| current_value | numeric(19,4) NOT NULL | Current market value |
| mortgage_balance | numeric(19,4) NOT NULL DEFAULT 0 | Manual mortgage balance |
| property_type | text NOT NULL DEFAULT 'primary_residence' | CHECK IN (`primary_residence`, `investment`, `vacation`) (V018) |
| loan_amount | numeric(19,4) | Original loan amount (V016) |
| annual_interest_rate | numeric(7,5) | Annual rate as a decimal — V035 converted legacy percentage values (6.5 → 0.065) |
| loan_term_months | integer | Loan term in months |
| loan_start_date | date | Loan origination date |
| use_computed_balance | boolean NOT NULL DEFAULT false | Use the amortization-computed balance instead of the manual one |
| zillow_zpid | text | Zillow property ID for automated valuation (V020) |
| annual_appreciation_rate | numeric(7,5) | Projection assumption (V034) |
| annual_property_tax | numeric(19,4) | Projection assumption (V034) |
| annual_insurance_cost | numeric(19,4) | Projection assumption (V034) |
| annual_maintenance_cost | numeric(19,4) | Projection assumption (V036) |
| in_service_date | date | Date placed in service for depreciation (V032) |
| land_value | numeric(19,4) | Non-depreciable land value |
| depreciation_method | text NOT NULL DEFAULT 'none' | CHECK IN (`none`, `straight_line`, `cost_segregation`) |
| useful_life_years | numeric(4,1) NOT NULL DEFAULT 27.5 | Depreciation period |
| cost_seg_allocations | jsonb NOT NULL DEFAULT '[]' | Structured asset-class allocations for a cost segregation study (V042) |
| bonus_depreciation_rate | numeric(5,4) NOT NULL DEFAULT 1.0000 | Bonus depreciation fraction applied to eligible classes |
| cost_seg_study_year | integer | Year the study applies from (drives §481(a) catch-up) |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_properties_tenant_id`.

**Lifecycle:** CRUD by user. Loan fields must be provided in full or not at all (partial loan details return 400). When `use_computed_balance` is true, mortgage balance is calculated via the amortization formula instead of the manual value. Zillow sync (`PropertyValuationSyncService`, cron `app.zillow.sync-cron`, default Sunday 6:00 AM) updates `current_value` and creates a `PropertyValuationEntity`. Helper methods: `hasLoanDetails()`, `getEquity()`.

### PropertyIncomeEntity (`property_income`)

Rental or other income associated with a property. Shares the `AbstractPropertyCashFlowEntity` mapped superclass with `PropertyExpenseEntity`.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| property_id | uuid NOT NULL FK → properties ON DELETE CASCADE | |
| tenant_id | uuid NOT NULL FK → tenants | |
| date | date NOT NULL | Income date |
| amount | numeric(19,4) NOT NULL | Income amount |
| category | text NOT NULL | CHECK IN (`rent`, `other`) |
| description | text | Optional description |
| frequency | text NOT NULL DEFAULT 'monthly' | CHECK IN (`monthly`, `annual`) (V019) |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_property_income_property_id`, `idx_property_income_tenant_id`, `idx_property_income_property_date (property_id, date)` (V041).

### PropertyExpenseEntity (`property_expenses`)

Cost associated with a property.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| property_id | uuid NOT NULL FK → properties ON DELETE CASCADE | |
| tenant_id | uuid NOT NULL FK → tenants | |
| date | date NOT NULL | Expense date |
| amount | numeric(19,4) NOT NULL | Expense amount |
| category | text NOT NULL | CHECK IN (`mortgage`, `tax`, `insurance`, `maintenance`, `capex`, `hoa`, `mgmt_fee`) |
| description | text | Optional description |
| frequency | text NOT NULL DEFAULT 'monthly' | CHECK IN (`monthly`, `annual`) (V019) |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_property_expenses_property_id`, `idx_property_expenses_tenant_id`, `idx_property_expenses_property_date (property_id, date)` (V041).

### PropertyValuationEntity (`property_valuations`)

Historical property value assessment from various sources.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| property_id | uuid NOT NULL FK → properties ON DELETE CASCADE | |
| tenant_id | uuid NOT NULL FK → tenants | |
| valuation_date | date NOT NULL | Date of assessment |
| value | numeric(19,4) NOT NULL | Assessed value |
| source | text NOT NULL | CHECK IN (`manual`, `zillow`, `appraisal`) |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_property_valuations_property_source_date UNIQUE (property_id, source, valuation_date)`. **Indexes:** `idx_property_valuations_property_id`, `idx_property_valuations_tenant_id`.

**Lifecycle:** Created manually, via the weekly Zillow scrape (Sunday 6:00 AM), or imported as appraisal data. Zillow valuations also update the property's `current_value`.

### PropertyDepreciationScheduleEntity (`property_depreciation_schedule`)

Year-by-year depreciation amounts for cost segregation studies.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| property_id | uuid NOT NULL FK → properties ON DELETE CASCADE | |
| tenant_id | uuid NOT NULL FK → tenants | |
| tax_year | integer NOT NULL | Calendar year |
| depreciation_amount | numeric(19,4) NOT NULL | Annual depreciation for that year |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_depreciation_schedule_property_year UNIQUE (property_id, tax_year)`.

**Lifecycle:** Generated when cost segregation inputs (`cost_seg_allocations`, `bonus_depreciation_rate`, `cost_seg_study_year`) are entered for an investment property. Used by the projection engine to compute rental income tax deductions. For straight-line depreciation, amounts are computed dynamically (no schedule rows needed).

---

## Retirement Projections

### ProjectionScenarioEntity (`projection_scenarios`)

Named retirement projection configuration with parameters, linked accounts, and at most one spending plan.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants | |
| name | text NOT NULL | Scenario display name |
| retirement_date | date | Planned retirement date |
| end_age | integer | Age at which projection ends |
| inflation_rate | numeric(5,4) | Annual inflation rate (e.g., 0.0300) |
| params_json | jsonb | Withdrawal strategy, filing status, state, spouse/household settings, Roth conversion params, mortality options |
| spending_profile_id | uuid FK → spending_profiles | Tier-based spending plan (V015) |
| guardrail_profile_id | uuid FK → guardrail_spending_profiles ON DELETE SET NULL | Monte-Carlo-optimized spending plan (V038) |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_projection_scenarios_tenant_id`.

**Relationships:** OneToMany → `ProjectionAccountEntity` (`CascadeType.ALL`, `orphanRemoval = true`). ManyToMany → `IncomeSourceEntity` via the `ScenarioIncomeSourceEntity` join table.

**Spending plan XOR:** `spending_profile_id` and `guardrail_profile_id` are mutually exclusive — setting one clears the other, and clearing both makes the engine fall back to a withdrawal-rate strategy. The invariant is enforced in `ScenarioCrudService.updateScenario()` and `GuardrailProfileService.optimize()`; the UI presents a single unified "Spending Plan" dropdown.

**Lifecycle:** Created by user with parameters and accounts → optionally linked to a spending plan and income sources → run on demand via `GET /api/v1/projections/{id}/run` → results returned, not persisted. Scenarios can be edited and re-run repeatedly. Deletion cascades to projection accounts, scenario-income-source links, and any guardrail profile.

### ProjectionAccountEntity (`projection_accounts`)

An investment pool within a projection scenario.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| scenario_id | uuid NOT NULL FK → projection_scenarios ON DELETE CASCADE | JPA `CascadeType.ALL` + orphan removal |
| linked_account_id | uuid FK → accounts | Optional; resolves initial balance at runtime |
| initial_balance | numeric(19,4) | NULL when linked to a real account (V028) |
| annual_contribution | numeric(19,4) NOT NULL DEFAULT 0 | Pre-retirement annual contribution |
| expected_return | numeric(5,4) | **Optional override** — NULL means "derive from `allocation`" (V069/V070/V073) |
| cost_basis | numeric(19,4) | Hypothetical-account cost basis for capital-gains tax; NULL defaults to `initial_balance`; ignored for linked accounts (V072) |
| allocation | jsonb | Asset-class weights `{us_stock, intl_stock, bond, cash}`; NULL derives from holdings (linked) or a default (V069) |
| account_type | text NOT NULL DEFAULT 'taxable' | CHECK IN (`traditional`, `roth`, `taxable`) (V012) |
| owner | text NOT NULL DEFAULT 'primary' | CHECK IN (`primary`, `spouse`, `joint`) — household modeling (V078) |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_projection_accounts_scenario_id`.

**Lifecycle:** Created as part of a scenario. When `linked_account_id` is set, the engine resolves `initial_balance` (and cost basis) from the linked account's live holdings at projection run time. Orphan removal deletes accounts removed from a scenario. V009 originally gave `expected_return` a `NOT NULL DEFAULT 0.07`; V069 made it nullable, V070 dropped the default, and V073 nulled every legacy row still carrying exactly 0.07 so allocation-driven, variance-carrying returns apply.

### SpendingProfileEntity (`spending_profiles`)

Tier-based retirement spending definition — the `TierBasedSpendingPlan` half of the `SpendingPlan` sealed interface.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants | |
| name | text NOT NULL | Profile display name |
| essential_expenses | numeric(19,4) NOT NULL DEFAULT 0 | Annual essential spending |
| discretionary_expenses | numeric(19,4) NOT NULL DEFAULT 0 | Annual discretionary spending |
| income_streams | jsonb NOT NULL DEFAULT '[]' | **Deprecated** — legacy income streams, migrated to `income_sources` by V033 |
| spending_tiers | jsonb NOT NULL DEFAULT '[]' | Age-based spending phases (V030) |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_spending_profiles_tenant_id`.

**Lifecycle:** Created by user → linked to one or more projection scenarios. Spending tiers define age-based phases (e.g., "Active Retirement" at 65, "Quiet Years" at 80). Per-tier inflation compounds from the later of the tier's start age or the retirement start age. A profile-level `income_inflation_rate` column existed briefly (V026) and was folded into each stream's JSON and dropped by V027.

### GuardrailSpendingProfileEntity (`guardrail_spending_profiles`)

Monte-Carlo-optimized spending plan bound to exactly one scenario — the `GuardrailSpendingInput` half of the `SpendingPlan` sealed interface (V037).

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants | |
| scenario_id | uuid NOT NULL FK → projection_scenarios ON DELETE CASCADE | |
| name | text NOT NULL | |
| essential_floor | numeric(19,4) NOT NULL | Non-negotiable annual spending floor |
| terminal_balance_target | numeric(19,4) NOT NULL DEFAULT 0 | Desired end-of-plan balance |
| return_mean | numeric(7,4) NOT NULL DEFAULT 0.10 | |
| trial_count | integer NOT NULL DEFAULT 5000 | Monte Carlo trials |
| confidence_level | numeric(5,4) NOT NULL DEFAULT 0.95 | Target success probability |
| phases | jsonb NOT NULL DEFAULT '[]' | User-defined optimizer phases |
| yearly_spending | jsonb NOT NULL DEFAULT '[]' | Per-year optimized spending output |
| median_final_balance | numeric(19,4) | Result summary |
| failure_rate | numeric(7,4) | Result summary |
| percentile_10_final | numeric(19,4) | Result summary |
| portfolio_floor | numeric(19,4) NOT NULL DEFAULT 0 | Smoothing constraint (V039) |
| max_annual_adjustment_rate | numeric(5,4) NOT NULL DEFAULT 0.0500 | Smoothing constraint (V039) |
| phase_blend_years | integer NOT NULL DEFAULT 1 | Smoothing constraint (V039) |
| risk_tolerance | text | `conservative` / `moderate` / `aggressive` → target success 0.95 / 0.90 / 0.80 |
| cash_reserve_years | integer NOT NULL DEFAULT 2 | Bucket strategy (V040) |
| cash_return_rate | numeric(5,4) NOT NULL DEFAULT 0.0400 | Bucket strategy (V040) |
| conversion_schedule | jsonb | Roth conversion optimizer output (V044) |
| conversion_bracket_rate | numeric(5,4) | Roth conversion target bracket (V044) |
| rmd_target_bracket_rate | numeric(5,4) | Target bracket for future RMDs (V044) |
| traditional_exhaustion_buffer | integer DEFAULT 5 | Legacy exhaustion constraint (V044) |
| rmd_bracket_headroom | numeric(5,4) DEFAULT 0.10 | Target-balance headroom replacing the exhaustion constraint (V045) |
| gate_on_adaptive_rules | boolean NOT NULL DEFAULT true | Gate the sustainability search on the with-rules success metric (V076; default flipped to true by V077, existing rows deliberately left at false) |
| scenario_hash | text NOT NULL | Hash of the scenario inputs the result was computed from |
| is_stale | boolean NOT NULL DEFAULT false | True when the scenario changed after optimization |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_guardrail_profiles_scenario UNIQUE (scenario_id)`. **Indexes:** `idx_guardrail_profiles_tenant_id`.

**Removed columns:** V055 dropped `return_stddev`, `percentile_90_final`, and `percentile_55_final` — all were written or declared but never read by any computation or UI surface.

**Lifecycle:** Created by `POST /api/v1/projections/{scenarioId}/optimize`, which also clears the scenario's `spending_profile_id`. Marked stale when the scenario hash no longer matches; re-optimized via `POST /guardrail/reoptimize`; deleted with the scenario or via `DELETE /guardrail`.

---

## Income Sources

### IncomeSourceEntity (`income_sources`)

First-class income definition reusable across projection scenarios (V031).

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK → tenants | |
| name | text NOT NULL | Display name (e.g., "Social Security — Primary") |
| income_type | text NOT NULL | CHECK IN (`rental_property`, `social_security`, `pension`, `part_time_work`, `annuity`, `other`) |
| annual_amount | numeric(19,4) NOT NULL | Base annual income |
| start_age | integer NOT NULL | Age when income begins (interpreted against the owner's age) |
| end_age | integer | Age when income ends (NULL = lifetime) |
| inflation_rate | numeric(7,5) NOT NULL DEFAULT 0 | Annual inflation adjustment rate |
| one_time | boolean NOT NULL DEFAULT false | One-time payment vs recurring |
| tax_treatment | text NOT NULL DEFAULT 'taxable' | CHECK IN (`taxable`, `partially_taxable`, `tax_free`, `rental_passive`, `rental_active_reps`, `rental_active_str`, `self_employment`) |
| property_id | uuid FK → properties ON DELETE SET NULL | Links rental income to a property for depreciation deductions |
| owner | text NOT NULL DEFAULT 'primary' | CHECK IN (`primary`, `spouse`) — household modeling (V079) |
| survivor_percent | numeric(5,4) NOT NULL DEFAULT 1.0 | CHECK between 0 and 1 — fraction that continues after the owner's death (V079) |
| created_at / updated_at | timestamptz NOT NULL | |

**Indexes:** `idx_income_sources_tenant_id`, `idx_income_sources_property_id`.

**Tax treatment details:**
- `taxable` — fully taxable as ordinary income
- `partially_taxable` — Social Security 85% provisional income rule
- `tax_free` — Roth distributions, municipal bond interest
- `rental_passive` — passive activity loss rules ($25k max deduction, phases out at $100k–$150k AGI)
- `rental_active_reps` — real estate professional status (no passive loss limit)
- `rental_active_str` — short-term rental material participation (no passive loss limit)
- `self_employment` — subject to 15.3% SE tax (12.4% Social Security up to the wage base + 2.9% Medicare)

**Lifecycle:** Created by user → linked to scenarios via `ScenarioIncomeSourceEntity` → applied during projection runs with the appropriate tax treatment. On the first-death transition the engine keeps the larger of two Social Security streams and scales surviving streams by `survivor_percent`. Legacy `income_streams` JSON on spending profiles was migrated into these rows by V033.

### ScenarioIncomeSourceEntity (`scenario_income_sources`)

Join table linking income sources to projection scenarios with an optional per-scenario override.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| scenario_id | uuid NOT NULL FK → projection_scenarios ON DELETE CASCADE | |
| income_source_id | uuid NOT NULL FK → income_sources ON DELETE CASCADE | |
| override_annual_amount | numeric(19,4) | Overrides the source's base amount for this scenario |
| created_at | timestamptz NOT NULL | No `updated_at` — this table is create/delete only |

**Constraints:** `uq_scenario_income_source UNIQUE (scenario_id, income_source_id)`. **Indexes:** `idx_scenario_income_sources_scenario`.

**Lifecycle:** Created when a user links an income source to a scenario. The override amount lets the same source (e.g., "Social Security") appear in multiple scenarios with different assumed amounts.

---

## Asset Classification & Capital-Market Data

Introduced by projection realism v2 (V066–V068). Returns are modeled per asset class rather than as a single fixed rate, so every symbol has to resolve to one of four classes: `us_stock`, `intl_stock`, `bond`, `cash`.

### AssetClassReturnEntity (`asset_class_returns`)

Real (CPI-adjusted) historical annual total returns per asset class, used by the joint block bootstrap.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| year | integer NOT NULL | Calendar year |
| asset_class | text NOT NULL | CHECK IN (`us_stock`, `intl_stock`, `bond`, `cash`) |
| real_return | numeric(9,6) NOT NULL | Real annual total return as a decimal |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_asset_class_returns_year_class UNIQUE (year, asset_class)`.

**Lifecycle:** Seeded by `R__seed_asset_class_returns` (1928–2025; truncate-and-reload). Read-only at runtime.

### SecurityAssetClassEntity (`security_asset_class`)

Global symbol → asset class map used by the security classifier.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| symbol | text NOT NULL | Ticker symbol |
| asset_class | text NOT NULL | CHECK IN (`us_stock`, `intl_stock`, `bond`, `cash`) |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_security_asset_class_symbol UNIQUE (symbol)`.

**Lifecycle:** Seeded by `R__seed_security_asset_class` (24 common tickers; truncate-and-reload). Not tenant-scoped.

### SecurityClassOverrideEntity (`security_class_override`)

Per-tenant reclassification layered on top of `security_asset_class`.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid NOT NULL | **No FK** — plain UUID column, mapped as a raw UUID in JPA |
| symbol | text NOT NULL | Ticker symbol |
| asset_class | text NOT NULL | CHECK IN (`us_stock`, `intl_stock`, `bond`, `cash`) |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_security_class_override_tenant_symbol UNIQUE (tenant_id, symbol)`.

**Lifecycle:** Upserted by `PUT /api/v1/securities/{symbol}/classification`. Takes precedence over the global seed map when the classifier resolves a holding's asset class.

---

## Tax & Benefit Reference Data

All tables in this section are global (not tenant-scoped) and seeded by repeatable migrations. `TaxBracketEntity`, `LtcgBracketEntity`, and `StateTaxBracketEntity` share the `AbstractTaxBracketEntity` mapped superclass.

### TaxBracketEntity (`tax_brackets`)

Federal ordinary-income tax brackets by year and filing status.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tax_year | integer NOT NULL | Calendar year |
| filing_status | text NOT NULL | CHECK IN (`single`, `married_filing_jointly`) |
| bracket_floor | numeric(19,4) NOT NULL | Income threshold (lower bound) |
| bracket_ceiling | numeric(19,4) | Upper bound; NULL for the top bracket |
| rate | numeric(5,4) NOT NULL | Marginal rate (e.g., 0.2200 = 22%) |
| created_at | timestamptz NOT NULL | |

**Constraints:** `idx_tax_brackets_year_status_floor UNIQUE (tax_year, filing_status, bracket_floor)`.

**Lifecycle:** Seeded by `R__seed_tax_brackets` (2022–2025). `FederalTaxCalculator` looks brackets up by year and falls back to the latest seeded year for any later projection year.

### StandardDeductionEntity (`standard_deductions`)

Federal standard deduction amounts by year and filing status.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tax_year | integer NOT NULL | Calendar year |
| filing_status | text NOT NULL | CHECK IN (`single`, `married_filing_jointly`) |
| amount | numeric(19,4) NOT NULL | Base standard deduction |
| additional_age65 | numeric(19,4) NOT NULL DEFAULT 0 | IRS age-65+ additional deduction, per qualifying person (Pub. 501) (V074) |
| created_at | timestamptz NOT NULL | |

**Constraints:** `uq_standard_deductions_year_status UNIQUE (tax_year, filing_status)`.

**Lifecycle:** Seeded by `R__seed_standard_deductions` (2022–2025; 2025 uses the post-OBBBA base amounts). `FederalTaxCalculator` subtracts the deduction before applying bracket math, adding one `additional_age65` adder per qualifying person aged 65+ (the filer, plus the spouse when a household scenario supplies a second age). Year fallback works the same as tax brackets.

### LtcgBracketEntity (`ltcg_brackets`)

Long-term capital gains brackets (0 / 15 / 20%), stacked on top of ordinary income (V071).

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tax_year | integer NOT NULL | |
| filing_status | text NOT NULL | `single` / `married_filing_jointly` |
| rate | numeric(6,4) NOT NULL | Declared `numeric(6,4)` in SQL; mapped as `numeric(5,4)` by the shared superclass |
| bracket_floor | numeric(19,4) NOT NULL | Total taxable income threshold |
| bracket_ceiling | numeric(19,4) | NULL for the top bracket |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_ltcg_brackets_year_status_floor UNIQUE (tax_year, filing_status, bracket_floor)`.

**Lifecycle:** Seeded by `R__seed_ltcg_brackets` (2025 only; truncate-and-reload). Drives per-lot FIFO capital-gains tax on taxable projection accounts, alongside NIIT.

### IrmaaTierEntity (`irmaa_tiers`)

Medicare IRMAA surcharge tiers keyed on MAGI (V075).

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tax_year | integer NOT NULL | |
| filing_status | text NOT NULL | `single` / `married_filing_jointly` |
| magi_floor | numeric(19,4) NOT NULL | Bracket lower bound (exclusive) |
| magi_ceiling | numeric(19,4) | Bracket upper bound (inclusive); NULL for the top tier |
| part_b_surcharge | numeric(19,4) NOT NULL | **Additional** monthly Part B amount over the standard premium |
| part_d_surcharge | numeric(19,4) NOT NULL | Monthly Part D surcharge |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_irmaa_tiers_year_status_floor UNIQUE (tax_year, filing_status, magi_floor)`.

**Lifecycle:** Seeded by `R__seed_irmaa_tiers` (2025; truncate-and-reload). Surcharges are pre-netted against the standard Part B premium, so the engine never needs a separate premium lookup. The first (below-threshold) tier per year/status is seeded with zero/zero.

### MortalityRateEntity (`mortality_rates`)

Sex-specific SSA period-life mortality rates for the stochastic-mortality Monte Carlo mode (V080). Lives in the `com.wealthview.persistence.projection` package rather than `entity`.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| sex | text NOT NULL | CHECK IN (`male`, `female`) |
| age | integer NOT NULL | Exact age |
| qx | numeric(9,8) NOT NULL | CHECK between 0 and 1 — P(death within the year given alive at exact age) |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_mortality_rates_sex_age UNIQUE (sex, age)`.

**Lifecycle:** Seeded by `R__seed_mortality_rates` — SSA 2021 Period Life Table (as used in the 2024 Trustees Report), ages 40–119, plus a project-specific terminal row at age 120 with `qx = 1.0` so the sampler always terminates. Upserts via `ON CONFLICT (sex, age) DO UPDATE`. Read only when stochastic mortality is opted into.

### StateTaxBracketEntity (`state_tax_brackets`)

State income tax brackets — mirrors `tax_brackets` with a `state_code` dimension (V043).

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| state_code | text NOT NULL | Two-letter state code |
| tax_year | integer NOT NULL | |
| filing_status | text NOT NULL | CHECK IN (`single`, `married_filing_jointly`) |
| bracket_floor | numeric(19,4) NOT NULL | |
| bracket_ceiling | numeric(19,4) | NULL for the top bracket |
| rate | numeric(5,4) NOT NULL | |
| created_at | timestamptz NOT NULL | |

**Constraints:** `idx_state_tax_brackets_state_year_status_floor UNIQUE (state_code, tax_year, filing_status, bracket_floor)`.

### StateStandardDeductionEntity (`state_standard_deductions`)

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| state_code | text NOT NULL | |
| tax_year | integer NOT NULL | |
| filing_status | text NOT NULL | CHECK IN (`single`, `married_filing_jointly`) |
| amount | numeric(19,4) NOT NULL | |
| created_at | timestamptz NOT NULL | |

**Constraints:** `uq_state_standard_deductions_state_year_status UNIQUE (state_code, tax_year, filing_status)`.

### StateTaxSurchargeEntity (`state_tax_surcharges`)

Flat-rate surcharges above an income threshold (e.g., California's Mental Health Services Tax).

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| state_code | text NOT NULL | |
| tax_year | integer NOT NULL | |
| filing_status | text NOT NULL | CHECK IN (`single`, `married_filing_jointly`) |
| surcharge_name | text NOT NULL | |
| income_threshold | numeric(19,4) NOT NULL | Surcharge applies above this income |
| rate | numeric(5,4) NOT NULL | |
| created_at | timestamptz NOT NULL | |

**Constraints:** `uq_state_tax_surcharges_state_year_status_name UNIQUE (state_code, tax_year, filing_status, surcharge_name)`.

**Lifecycle (all three state tables):** Seeded together by `R__seed_state_tax_brackets` — California, Arizona, and Oregon for 2024–2025; delete-and-reload.

---

## System

### AuditLogEntity (`audit_log`)

Immutable event log for tracking user actions across the system.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| tenant_id | uuid FK → tenants | Nullable |
| user_id | uuid FK → users | Nullable |
| action | text NOT NULL | e.g., `CREATE`, `UPDATE`, `DELETE`, `LOGIN` |
| entity_type | text NOT NULL | e.g., `Account`, `Property` |
| entity_id | uuid | Id of the affected entity — no FK, entity type varies |
| details | jsonb | Additional context (before/after values, metadata) |
| ip_address | text | Client IP address |
| created_at | timestamptz NOT NULL DEFAULT now() | Event timestamp |

**Indexes:** `idx_audit_log_tenant_id`, `idx_audit_log_entity_type_entity_id`, `idx_audit_log_created_at`.

**Lifecycle:** Append-only. Rows are written by service methods and never updated or deleted. `tenant_id` and `user_id` are real (nullable) foreign keys; `entity_id` is a bare UUID because the referenced table varies by `entity_type`. Mapped in JPA as raw `UUID` fields rather than associations.

### NotificationPreferenceEntity (`notification_preferences`)

Per-user notification settings.

| Field | Type | Notes |
|-------|------|-------|
| id | uuid PK | |
| user_id | uuid NOT NULL FK → users ON DELETE CASCADE | |
| notification_type | text NOT NULL | Type identifier |
| enabled | boolean NOT NULL DEFAULT true | Whether notifications of this type are enabled |
| created_at / updated_at | timestamptz NOT NULL | |

**Constraints:** `uq_notification_preferences_user_type UNIQUE (user_id, notification_type)`.

### SystemConfigEntity (`system_config`)

Key/value store for admin-tunable settings such as whether self-registration is open (V050).

| Field | Type | Notes |
|-------|------|-------|
| key | text PK | Setting name |
| value | text NOT NULL | Setting value (always text) |
| updated_at | timestamptz NOT NULL DEFAULT now() | No `created_at` |

**Lifecycle:** Read and written via `GET /api/v1/admin/config` and `PUT /api/v1/admin/config/{key}`.

### MobileAppVersionEntity (`mobile_app_versions`)

Force-update / version-check data for the React Native client — one row per platform (V063).

| Field | Type | Notes |
|-------|------|-------|
| platform | text PK | CHECK IN (`android`, `ios`) |
| minimum_supported_version | text NOT NULL | CHECK matches `^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$`; drives the hard update banner |
| latest_version | text NOT NULL | Same semver CHECK; drives the soft "update available" prompt |
| store_url | text NOT NULL | App Store / Play Store link |
| message | text | Optional message shown with the prompt |
| created_at / updated_at | timestamptz NOT NULL | |

**Lifecycle:** V063 inserts placeholder rows for both platforms (`0.0.1` / `0.0.1`, dummy store URLs). Operators MUST update them via `PUT /api/v1/admin/mobile-versions/{platform}` before announcing a mobile build. The client reads them through `GET /api/v1/app/version-check`.

---

## Entity Lifecycle Patterns

| Pattern | Entities | Description |
|---------|----------|-------------|
| **CRUD** | Account, Property, ProjectionScenario, SpendingProfile, IncomeSource, ExchangeRate | Standard create-read-update-delete with tenant isolation |
| **Cascade delete** | Transaction, Holding, PropertyIncome, PropertyExpense, PropertyValuation, PropertyDepreciationSchedule, ProjectionAccount, ScenarioIncomeSource, GuardrailSpendingProfile, StockSplitAdjustment, NotificationPreference | Deleted automatically when the parent row is removed (`ON DELETE CASCADE`, or JPA cascade + orphan removal for ProjectionAccount) |
| **State machine** | ImportJob (`pending` → `processing` → `completed`/`failed`) | Transitions through defined states; immutable after a terminal state |
| **Consume-once token** | InviteCode, RefreshToken, MfaChallenge, MfaRecoveryCode | Created active, consumed exactly once, then frozen; reuse is treated as an error (refresh-token reuse revokes the whole generation) |
| **Append-only** | AuditLog, LoginActivity, StockSplitAdjustment | Written once, never updated; adjustments are read back to reverse a split |
| **Auto-computed** | Holding (from transactions), PropertyValuation (from Zillow sync), PropertyDepreciationSchedule (from cost seg inputs), GuardrailSpendingProfile (from Monte Carlo optimization) | Derived from source data; Holding supports a manual override |
| **Reference data** | Price, TaxBracket, StandardDeduction, LtcgBracket, IrmaaTier, MortalityRate, AssetClassReturn, SecurityAssetClass, StateTaxBracket, StateStandardDeduction, StateTaxSurcharge | Global (not tenant-scoped), seeded by repeatable migrations |
| **Global with tenant audit trail** | StockSplit (global) + StockSplitAdjustment (tenant-scoped) | A market-wide event with per-tenant undo records |
| **Mutually exclusive FKs** | ProjectionScenario → SpendingProfile XOR GuardrailSpendingProfile | Setting one clears the other; clearing both falls back to a withdrawal-rate strategy |
| **Session / device state** | UserSession, RefreshToken | Revocable per device without disturbing other sessions |
| **Singleton config** | SystemConfig (per key), MobileAppVersion (per platform) | Fixed key space; upserted by admin endpoints |
| **Projection workflow** | Scenario → configure accounts + income → run → compare | Configuration is persisted; results are computed on demand (not stored) |

---

## Flyway Migration Inventory

Flyway migrations live in `backend/wealthview-persistence/src/main/resources/db/migration/` and run automatically on application startup. There are **89 files**: 80 versioned (`V001`–`V080`) and 9 repeatable seeds.

### Versioned Migrations

| Migration | Description                                                                     |
|-----------|---------------------------------------------------------------------------------|
| V001      | Baseline tenants and users tables                                               |
| V002      | Invite codes table                                                              |
| V003      | Accounts table                                                                  |
| V004      | Transactions table                                                              |
| V005      | Holdings table with manual override flag                                        |
| V006      | Prices table (composite PK: symbol + date)                                      |
| V007      | Properties tables (properties, property_income, property_expenses)              |
| V008      | Import jobs table                                                               |
| V009      | Projection tables (scenarios, accounts)                                         |
| V010      | Transaction `import_hash` column for deduplication                              |
| V011      | Add `opening_balance` transaction type                                          |
| V012      | `account_type` on projection accounts (traditional/roth/taxable)                |
| V013      | Federal tax brackets table                                                      |
| V014      | Spending profiles table (essential/discretionary/income streams)                |
| V015      | Add `spending_profile_id` FK to projection scenarios                            |
| V016      | Loan detail columns on properties (amortization support)                        |
| V017      | Property valuations history table                                               |
| V018      | Add `property_type` to properties (primary_residence/investment/vacation)       |
| V019      | Add `frequency` to property income and expenses (monthly/annual)                |
| V020      | Add `zillow_zpid` to properties for Zillow lookups                              |
| V021      | Money market fields on holdings (`is_money_market`, `money_market_rate`)         |
| V022      | Allow `positions` as an import job source                                       |
| V023      | Add `is_active` to tenants for admin enable/disable                             |
| V024      | Create audit log table (action + entity tracking, jsonb details)                |
| V025      | Create notification preferences table                                           |
| V026      | Add `income_inflation_rate` to spending profiles                                |
| V027      | Move `income_inflation_rate` into per-stream JSON, drop the column              |
| V028      | Make projection account `initial_balance` nullable for linked accounts          |
| V029      | Create standard deductions table                                                |
| V030      | Add `spending_tiers` jsonb for age-based spending phases                        |
| V031      | Create income sources and scenario income sources tables                        |
| V032      | Property depreciation fields + `property_depreciation_schedule` table           |
| V033      | Migrate legacy `income_streams` JSON into income_sources rows                   |
| V034      | Property financial fields (appreciation rate, property tax, insurance)          |
| V035      | Convert `annual_interest_rate` from percentage to decimal                       |
| V036      | Add `annual_maintenance_cost` to properties                                     |
| V037      | Create guardrail spending profiles (Monte Carlo optimized plans)                |
| V038      | Add `guardrail_profile_id` FK to projection scenarios                           |
| V039      | Spending optimizer smoothing fields (floor, max adjustment, phase blend)        |
| V040      | Cash buffer / bucket strategy fields on guardrail profiles                      |
| V041      | Composite `(property_id, date)` indexes on property expenses and income         |
| V042      | Cost segregation study fields (allocations, bonus rate, study year)             |
| V043      | State tax tables (brackets, standard deductions, surcharges)                    |
| V044      | Roth conversion optimizer fields on guardrail profiles                          |
| V045      | Add `rmd_bracket_headroom` (target-balance approach)                            |
| V046      | Add `percentile_55_final` column (later dropped by V055)                        |
| V047      | Allow `yahoo` as a price source                                                 |
| V048      | Add `is_active` to users                                                        |
| V049      | Add `is_revoked` to invite codes                                                |
| V050      | Create `system_config` key/value table                                          |
| V051      | Create `login_activity` table                                                   |
| V052      | Add `token_generation` to users (refresh token rotation)                        |
| V053      | Add `currency` to accounts (ISO 4217, default USD)                              |
| V054      | Create tenant-scoped `exchange_rates` table                                     |
| V055      | Drop unused guardrail columns (`return_stddev`, P90, P55)                       |
| V056      | Composite `(tenant_id, created_at DESC)` index on login activity                |
| V057      | Add optimistic-lock `version` column to users                                   |
| V058      | Create `refresh_tokens` table (one-time-use JTI tracking)                       |
| V059      | Create `user_sessions` table (per-device sessions)                              |
| V060      | Add TOTP MFA columns to users                                                   |
| V061      | Create `mfa_recovery_codes` table                                               |
| V062      | Create `mfa_challenges` table                                                   |
| V063      | Create `mobile_app_versions` table + placeholder rows                           |
| V064      | Create `stock_splits` and `stock_split_adjustments`                             |
| V065      | Composite `(tenant_id, symbol)` index on transactions                           |
| V066      | Create `asset_class_returns` (real historical returns per class)                |
| V067      | Create `security_asset_class` (global symbol → class map)                       |
| V068      | Create `security_class_override` (per-tenant reclassification)                  |
| V069      | Add `allocation` jsonb to projection accounts; `expected_return` now nullable   |
| V070      | Drop the stale `DEFAULT 0.07` on `projection_accounts.expected_return`          |
| V071      | Create `ltcg_brackets` (long-term capital gains, 0/15/20)                       |
| V072      | Add `cost_basis` to projection accounts (hypothetical taxable pools)            |
| V073      | Null the legacy 0.07 `expected_return` values so allocation returns apply       |
| V074      | Add `additional_age65` to standard deductions                                   |
| V075      | Create `irmaa_tiers` (Medicare Part B/D surcharges by MAGI)                     |
| V076      | Add `gate_on_adaptive_rules` to guardrail profiles (default false)              |
| V077      | Flip the `gate_on_adaptive_rules` column DEFAULT to true (no row backfill)      |
| V078      | Add `owner` to projection accounts (primary/spouse/joint)                       |
| V079      | Add `owner` + `survivor_percent` to income sources                              |
| V080      | Create `mortality_rates` (SSA period-life qx by sex and age)                    |

### Repeatable Migrations

Repeatable migrations re-run whenever their checksum changes. Each is written to be idempotent — most `TRUNCATE`/`DELETE` and reload, and `R__seed_mortality_rates` upserts via `ON CONFLICT`.

| Migration | Description |
|-----------|-------------|
| R__seed_stock_prices | Daily closes for 12 symbols (AAPL, AMZN, BND, FXAIX, GOOG, MSFT, NVDA, SCHD, VOO, VTI, VUG, VXUS) — ~72,700 rows, earliest 1980-12-12; deletes and re-inserts all prices |
| R__seed_tax_brackets | Federal ordinary-income brackets 2022–2025 (single + married filing jointly) |
| R__seed_standard_deductions | Federal standard deductions 2022–2025 with age-65+ adders; 2025 uses post-OBBBA base amounts |
| R__seed_ltcg_brackets | Long-term capital gains brackets for 2025 (0/15/20) |
| R__seed_irmaa_tiers | 2025 IRMAA tiers; Part B surcharges pre-netted against the $185.00 standard premium |
| R__seed_state_tax_brackets | State brackets, standard deductions, and surcharges — CA, AZ, OR for 2024–2025 |
| R__seed_asset_class_returns | Real annual returns per asset class, 1928–2025 (Shiller, Damodaran, MSCI EAFE, CPI-U) |
| R__seed_security_asset_class | Symbol → asset class map for 24 common tickers |
| R__seed_mortality_rates | SSA 2021 Period Life Table qx by sex, ages 40–119, plus a terminal age-120 row (qx = 1.0) |

**Important:** Versioned migrations are immutable once released. Never edit a committed migration file — create a new one instead.

---

## Related Docs

- [Architecture](architecture.md) — Module structure and dependency rules
- [API Reference](api-reference.md) — Full endpoint documentation
