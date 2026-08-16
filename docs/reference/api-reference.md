[← Back to README](../../README.md)

# API Reference

All endpoints are under `/api/v1/`. Everything requires authentication except the
handful of public endpoints listed under [Authorization](#authorization).

JSON field names are `snake_case` on the wire (Jackson is configured globally with
`PropertyNamingStrategies.SNAKE_CASE`), so a Java record component `annualAmount`
serializes as `annual_amount`. The tables below use the wire names.

## Authentication

There are two transports, and they are separate controllers with separate paths.

**Web (cookie transport)** — `/api/v1/auth/**`. Access and refresh tokens are issued as
`HttpOnly; Secure; SameSite=Strict; Path=/` cookies named `access_token` and
`refresh_token`. They are **never** present in a request or response body. The response
body carries only the user identity.

**Native mobile (bearer transport)** — `/api/v1/auth/token/**`. The same operations, but
tokens are returned in the response body for clients that persist them in OS-managed
secure storage. Authenticated requests then send `Authorization: Bearer <jwt>`.

| Endpoint                          | Method | Description                                       |
|-----------------------------------|--------|---------------------------------------------------|
| `/api/v1/auth/login`              | POST   | Login. Sets token cookies, or returns an MFA challenge |
| `/api/v1/auth/mfa/challenge`      | POST   | Complete an MFA-gated login                       |
| `/api/v1/auth/register`           | POST   | Register with an invite code (201)                |
| `/api/v1/auth/refresh`            | POST   | Rotate tokens; reads the `refresh_token` cookie   |
| `/api/v1/auth/logout`             | POST   | Revoke sessions and clear both cookies (204)      |
| `/api/v1/auth/me`                 | GET    | Current user identity                             |

**Login request:**
```json
{ "email": "user@example.com", "password": "yourpassword", "device_label": "Firefox on Linux" }
```
`device_label` is optional (max 64 chars) and labels the session row.

**Login response** (no MFA on the account) — 200, plus `Set-Cookie` for both tokens:
```json
{
  "user_id": "00000000-0000-0000-0000-000000000000",
  "tenant_id": "00000000-0000-0000-0000-000000000000",
  "email": "user@example.com",
  "role": "admin"
}
```

**Login response** (MFA enabled) — 200, no cookies set:
```json
{ "mfa_required": true, "mfa_token": "example-not-a-real-token" }
```
Post that `mfa_token` with either a `totp_code` (6-8 chars) or a `recovery_code`
(exactly 8 chars) to `/api/v1/auth/mfa/challenge` to finish logging in:
```json
{ "mfa_token": "example-not-a-real-token", "totp_code": "123456" }
```

`GET /api/v1/auth/me` returns `user_id`, `tenant_id`, `email`, `role` — the same shape as
the login response.

**Register request:**
```json
{ "email": "user@example.com", "password": "at-least-8-chars", "invite_code": "ABC123" }
```
Password must be 8-64 characters. Returns 201.

### Mobile token endpoints

| Endpoint                                | Method | Description                              |
|-----------------------------------------|--------|------------------------------------------|
| `/api/v1/auth/token/login`              | POST   | Login, tokens in body (or MFA challenge) |
| `/api/v1/auth/token/mfa/challenge`      | POST   | Complete an MFA-gated login              |
| `/api/v1/auth/token/register`           | POST   | Register with an invite code (201)       |
| `/api/v1/auth/token/refresh`            | POST   | Rotate tokens; refresh token in body     |
| `/api/v1/auth/token/logout`             | POST   | Revoke the bearer sessions (204)         |

Request and response bodies match the cookie endpoints, except that the success response
also carries the tokens:
```json
{
  "access_token": "example.not.a.real.jwt",
  "refresh_token": "example.not.a.real.jwt",
  "user_id": "00000000-0000-0000-0000-000000000000",
  "tenant_id": "00000000-0000-0000-0000-000000000000",
  "email": "user@example.com",
  "role": "admin"
}
```
`POST /api/v1/auth/token/refresh` takes `{ "refresh_token": "..." }` in the body, since a
native client has no cookie jar.

Include the access token on subsequent requests:
```
Authorization: Bearer example.not.a.real.jwt
```

`GET /api/v1/auth/me` works for both transports.

### MFA management

All of these act on the authenticated caller's own account and require authentication.

| Endpoint                                        | Method | Description                       |
|-------------------------------------------------|--------|-----------------------------------|
| `/api/v1/auth/mfa/status`                       | GET    | `enabled`, `setup_at`, `recovery_codes_remaining` |
| `/api/v1/auth/mfa/setup`                        | POST   | Returns `secret`, `qr_code_uri`, `recovery_codes` |
| `/api/v1/auth/mfa/verify-setup`                 | POST   | Confirm setup with `totp_code` (204) |
| `/api/v1/auth/mfa/disable`                      | POST   | Disable with `totp_code` (204)    |
| `/api/v1/auth/mfa/regenerate-recovery-codes`    | POST   | New recovery codes                |

`setup` returns the recovery codes exactly once — the server stores BCrypt hashes only.
`verify-setup` and `disable` both take `{ "totp_code": "123456" }` and return 401 on a
bad code.

### Sessions

| Endpoint                          | Method | Description                                     |
|-----------------------------------|--------|-------------------------------------------------|
| `/api/v1/auth/sessions`           | GET    | List this user's non-revoked sessions           |
| `/api/v1/auth/sessions/{id}`      | DELETE | Revoke one session (204; 404 if not the caller's) |
| `/api/v1/auth/sessions`           | DELETE | Revoke every session except the current one (204) |

Each row carries `id`, `device_label`, `transport` (`cookie` or `bearer`), `ip_address`,
`user_agent`, `created_at`, `last_used_at`, and `current`.

## Authorization

Roles are stored lowercase and mapped to Spring authorities as `ROLE_<UPPERCASE>`:
`super_admin`, `admin`, `member`, `viewer`.

**Public (no authentication):**
- `POST /api/v1/auth/login`, `/register`, `/refresh`, `/mfa/challenge` and all of
  `/api/v1/auth/token/**` except `logout`
- `GET /api/v1/app/version-check`
- `GET /actuator/health`
- `GET /**` — the SPA's static assets

**Everything else under `/api/v1/`** requires authentication, with these role rules:

| Path pattern                                    | Required role                    |
|-------------------------------------------------|----------------------------------|
| `/api/v1/admin/prices/**`                       | `admin` or `super_admin`         |
| `/api/v1/admin/**` (all other admin paths)      | `super_admin`                    |
| `/actuator/**` (other than `/actuator/health`)  | `super_admin`                    |
| `POST`/`PUT`/`DELETE` on `/api/v1/prices/**`    | `admin` or `super_admin`         |
| `/api/v1/tenant/invite-codes**`, `/api/v1/tenant/users**` | `admin` or `super_admin` |
| `/api/v1/audit-log`, `/api/v1/audit-log/**`     | `admin` or `super_admin`         |
| `GET` on any other `/api/v1/**`                 | any authenticated role           |
| `POST`/`PUT`/`DELETE` on any other `/api/v1/**` | `admin`, `member`, or `super_admin` |

In practice `viewer` is read-only: it can `GET` but is rejected on every write.

### CSRF

Cookie-authenticated requests use the double-submit-cookie pattern. The server sets a
non-HttpOnly `XSRF-TOKEN` cookie; the client echoes it in the `X-XSRF-TOKEN` header.
CSRF is not enforced for `POST /api/v1/auth/login`, `/register`, `/refresh`,
`/mfa/challenge`, anything under `/api/v1/auth/token/**`, or any request that carries an
`Authorization: Bearer ` header.

### Rate limits

Applied to all `/api/**` requests; `super_admin` is exempt.

| Scope                       | Limit               |
|-----------------------------|---------------------|
| `/api/v1/auth/**`, per IP   | 60 requests/minute  |
| All other `/api/**`, per user (per IP if anonymous) | 300 requests/minute |

Every response carries `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and
`X-RateLimit-Reset` (epoch seconds). Exceeding the limit returns 429 with
`{"error":"RATE_LIMITED","message":"Too many requests","status":429}`.

## Errors

Every error returns the standard envelope:
```json
{ "error": "NOT_FOUND", "message": "Account not found: ...", "status": 404 }
```

| Condition                                                     | Status | `error`                 |
|---------------------------------------------------------------|--------|-------------------------|
| Entity not found, or no controller mapped to the path         | 404    | `NOT_FOUND`             |
| Bad credentials, invalid/expired session                      | 401    | `UNAUTHORIZED`          |
| Missing or unparseable authentication                         | 401    | `UNAUTHORIZED`          |
| Role mismatch, cross-tenant access                            | 403    | `FORBIDDEN`             |
| Duplicate entity, illegal state                               | 409    | `CONFLICT`              |
| Bean-validation failure, invalid invite code, illegal argument | 400    | `BAD_REQUEST`           |
| Unparseable request body or query-param type mismatch         | 400    | `BAD_REQUEST`           |
| Bad date/time format, CSV/IO parse failure, DB constraint violation | 400 | `BAD_REQUEST`          |
| Upload larger than 10MB                                       | 413    | `PAYLOAD_TOO_LARGE`     |
| Optional integration not configured (Finnhub, Zillow)         | 503    | `SERVICE_UNAVAILABLE`   |
| Rate limit exceeded                                           | 429    | `RATE_LIMITED`          |
| Anything unhandled                                            | 500    | `INTERNAL_SERVER_ERROR` |

Validation failures put the offending fields in `message` as `field: reason` pairs joined
by `; `.

## Pagination

Endpoints that page (`GET /api/v1/accounts`, `GET /api/v1/accounts/{accountId}/transactions`,
`GET /api/v1/audit-log`) take `page` and `size` query params and return:
```json
{ "data": [], "page": 0, "size": 25, "total": 0 }
```
Note the field is `data`, not `content`. `size` is clamped to 1-200 and `page` to
0-100000; out-of-range values are silently coerced rather than rejected.

## Accounts

| Endpoint                                    | Method | Description                     |
|---------------------------------------------|--------|---------------------------------|
| `/api/v1/accounts`                          | POST   | Create an account (201)         |
| `/api/v1/accounts`                          | GET    | Paginated list (`page`, `size`, default 0/25) |
| `/api/v1/accounts/{id}`                     | GET    | Get one account                 |
| `/api/v1/accounts/{id}`                     | PUT    | Update an account               |
| `/api/v1/accounts/{id}`                     | DELETE | Delete an account (204)         |
| `/api/v1/accounts/{id}/theoretical-history` | GET    | Reconstructed value history (`months`, default 24) |

**Account request:**
```json
{ "name": "Fidelity Brokerage", "type": "brokerage", "institution": "Fidelity", "currency": "USD" }
```
`type` must be one of `brokerage`, `ira`, `401k`, `roth`, `bank`. The response adds `id`,
`balance`, and `created_at`.

## Holdings

| Endpoint                                | Method | Description                     |
|-----------------------------------------|--------|---------------------------------|
| `/api/v1/accounts/{accountId}/holdings` | GET    | Holdings for one account        |
| `/api/v1/holdings/{id}`                 | GET    | Get one holding                 |
| `/api/v1/holdings`                      | POST   | Create a manual holding (201)   |
| `/api/v1/holdings/{id}`                 | PUT    | Update a holding                |

**Holding request:** `account_id`, `symbol`, `quantity`, `cost_basis` (all required).

Responses add `is_manual_override`, `is_money_market`, `money_market_rate`, `as_of_date`,
and — when a price is available — `current_price`, `market_value`, `gain_loss`.

## Transactions

| Endpoint                                     | Method | Description                     |
|----------------------------------------------|--------|---------------------------------|
| `/api/v1/accounts/{accountId}/transactions`  | POST   | Create a transaction (201)      |
| `/api/v1/accounts/{accountId}/transactions`  | GET    | Paginated list; optional `symbol` filter |
| `/api/v1/transactions/{id}`                  | PUT    | Update a transaction            |
| `/api/v1/transactions/{id}`                  | DELETE | Delete a transaction (204)      |

**Transaction request:** `date` and `amount` are required, `type` is a required enum
(lowercase wire tokens), `symbol` is optional, `quantity` must be >= 0. An unknown `type`
token fails deserialization and surfaces as 400.

## Properties

| Endpoint                                                      | Method | Description                        |
|---------------------------------------------------------------|--------|------------------------------------|
| `/api/v1/properties`                                          | POST   | Create a property (201)            |
| `/api/v1/properties`                                          | GET    | List properties                    |
| `/api/v1/properties/{id}`                                     | GET    | Get one property                   |
| `/api/v1/properties/{id}`                                     | PUT    | Update a property                  |
| `/api/v1/properties/{id}`                                     | DELETE | Delete a property (204)            |
| `/api/v1/properties/{id}/expenses`                            | GET    | List expenses for a property       |
| `/api/v1/properties/{id}/expenses`                            | POST   | Add an expense (201, empty body)   |
| `/api/v1/properties/{id}/expenses/{expenseId}`                | DELETE | Delete an expense (204)            |
| `/api/v1/properties/{id}/income`                              | POST   | Add a rental income entry (201, empty body) |
| `/api/v1/properties/{id}/cashflow`                            | GET    | Monthly cash flow; `from`/`to` as `YYYY-MM` |
| `/api/v1/properties/{id}/cashflow-detail`                     | GET    | As above, with expenses broken out by category |
| `/api/v1/properties/{id}/analytics`                           | GET    | Cap rate, cash-on-cash, equity growth, mortgage progress. Optional `year` |
| `/api/v1/properties/{id}/valuations`                          | GET    | Valuation history                  |
| `/api/v1/properties/{id}/valuations/refresh`                  | POST   | Trigger a Zillow lookup for this property |
| `/api/v1/properties/{id}/valuations/select-zpid`              | POST   | Resolve an ambiguous Zillow match  |
| `/api/v1/properties/{id}/depreciation-schedule`               | GET    | Year-by-year depreciation, incl. cost-seg class breakdown |
| `/api/v1/properties/{propertyId}/income-sources/{sourceId}/roi-analysis` | GET | Hold-vs-sell ROI comparison |

Property expense `category` must be one of `mortgage`, `tax`, `insurance`,
`maintenance`, `capex`, `hoa`, `mgmt_fee`; income `category` is `rent` or `other`. Both
accept an optional `frequency` of `monthly` or `annual`.

`valuations/refresh` returns `{ "status": "updated" | "multiple_matches" | "no_results", ... }`.
On `multiple_matches` it includes a `candidates` list; pick one and POST its `zpid`
(1-15 digits) to `select-zpid`. Both endpoints return 503 when the Zillow scraper is not
configured.

`roi-analysis` accepts `years` (default 10), `investment_return` (default 0.07),
`rent_growth` (default 0.03), and `expense_inflation` (default 0.03).

## Import

| Endpoint                       | Method | Description                                          |
|--------------------------------|--------|------------------------------------------------------|
| `/api/v1/import/csv`           | POST   | Transaction CSV (201). Params: `accountId`, `file`, optional `format` |
| `/api/v1/import/positions`     | POST   | Positions CSV (201). Same params; `format` defaults to `fidelityPositions` |
| `/api/v1/import/ofx`           | POST   | OFX/QFX file (201). Params: `accountId`, `file`       |
| `/api/v1/import/jobs`          | GET    | Import job history for the tenant                     |

All three uploads are `multipart/form-data`. Uploads are capped at 10MB, and the content
type must be one of `text/csv`, `text/plain`, `application/octet-stream`,
`application/vnd.ms-excel`, `application/xml`, `text/xml`, `application/x-ofx`,
`application/x-qfx` — anything else is a 400.

Job responses carry `id`, `source`, `status`, `total_rows`, `successful_rows`,
`failed_rows`, `error_message`, `created_at`.

## Prices

| Endpoint                          | Method | Description                                   |
|-----------------------------------|--------|-----------------------------------------------|
| `/api/v1/prices`                  | GET    | Latest price for every tracked symbol         |
| `/api/v1/prices`                  | POST   | Add a price (201). Admin or super-admin       |
| `/api/v1/prices/{symbol}/latest`  | GET    | Latest price for one symbol                   |

**Price request:** `symbol`, `date`, `close_price` (all required). Responses add `source`.

## Stock Splits

| Endpoint                                | Method | Description                                   |
|-----------------------------------------|--------|-----------------------------------------------|
| `/api/v1/stock-splits`                  | GET    | Splits affecting this tenant. Optional `symbol`, `from`, `to` |
| `/api/v1/admin/stock-splits`            | POST   | Record and apply a split manually (201). Super-admin |
| `/api/v1/admin/stock-splits/{id}`       | DELETE | Un-apply a split (204). Super-admin           |
| `/api/v1/admin/stock-splits/sync`       | POST   | Run the Finnhub split sync now. Super-admin   |

**Manual split request:** `symbol`, `effective_date`, `numerator`, `denominator`
(numerator and denominator must both be positive). Manually created splits are recorded
with source `manual`.

`sync` returns `symbols_scanned`, `splits_discovered`, `splits_applied`, `failed_symbols`,
and 503 when no Finnhub API key is configured.

## Securities

| Endpoint                                       | Method | Description                        |
|------------------------------------------------|--------|------------------------------------|
| `/api/v1/securities/{symbol}/classification`   | PUT    | Set a per-tenant asset-class override |

Takes `{ "asset_class": "us_stock" }` (case-insensitive). Valid keys are `us_stock`,
`intl_stock`, `bond`, `cash`; anything else is a 400. Returns
`{ "symbol": "...", "asset_class": "..." }`.

## Projections

| Endpoint                            | Method | Description                                   |
|-------------------------------------|--------|-----------------------------------------------|
| `/api/v1/projections`               | POST   | Create a scenario (201)                       |
| `/api/v1/projections`               | GET    | List scenarios                                |
| `/api/v1/projections/{id}`          | GET    | Get one scenario                              |
| `/api/v1/projections/{id}`          | PUT    | Update a scenario                             |
| `/api/v1/projections/{id}`          | DELETE | Delete a scenario (204)                       |
| `/api/v1/projections/{id}/run`      | GET    | Run the projection, year-by-year results      |
| `/api/v1/projections/compare`       | POST   | Compare 2-3 scenarios side by side            |

Create and update take an identical payload. Beyond the basics (`name`,
`retirement_date`, `end_age`, `inflation_rate`, `birth_year`, `withdrawal_rate`,
`withdrawal_strategy`, `filing_status`, `state`) it carries the tax and sequencing knobs
(`annual_roth_conversion`, `withdrawal_order`, `roth_conversion_strategy`,
`target_bracket_rate`, `dynamic_sequencing_bracket_rate`, `dividend_yield`,
`interest_yield`, `fee_rate`), the household/survivor fields (`spouse_birth_year`,
`primary_death_age`, `spouse_death_age`, `survivor_spending_factor`,
`community_property`, `stochastic_mortality`, `primary_sex`, `spouse_sex`,
`longevity_conditional_age`), plus `accounts`, `income_sources`, `spending_profile_id`,
and `use_guardrail_profile`.

`spending_profile_id` and the guardrail profile are mutually exclusive — setting one
clears the other, and clearing both falls back to a withdrawal-rate strategy. The
scenario response echoes both `spending_profile` and `guardrail_profile` so a client can
render whichever is active.

`compare` takes `{ "scenario_ids": ["...", "..."] }` with 2 or 3 ids.

`run` returns `scenario_id`, `yearly_data`, `final_balance`, `years_in_retirement`,
`spending_feasibility`, `final_net_worth`, `unclassified_symbols`, and `warnings` (the
last is omitted when empty).

## Guardrail Profiles

Guardrail profiles are the Monte-Carlo-optimized form of a spending plan and hang off a
scenario.

| Endpoint                                              | Method | Description                    |
|-------------------------------------------------------|--------|--------------------------------|
| `/api/v1/projections/{scenarioId}/optimize`           | POST   | Run the optimizer and save a profile |
| `/api/v1/projections/{scenarioId}/guardrail`          | GET    | Get the scenario's guardrail profile |
| `/api/v1/projections/{scenarioId}/guardrail`          | DELETE | Delete the profile (204)       |
| `/api/v1/projections/{scenarioId}/guardrail/reoptimize` | POST | Re-run with the saved settings |

The optimization request accepts `essential_floor`, `terminal_balance_target`,
`return_mean`, `trial_count` (100-50000), `confidence_level` (0.5-0.999), `phases`,
`portfolio_floor`, `max_annual_adjustment_rate`, `phase_blend_years`, `risk_tolerance`,
`cash_reserve_years`, `cash_return_rate`, `optimize_conversions`,
`conversion_bracket_rate`, `rmd_target_bracket_rate`, `traditional_exhaustion_buffer`,
`rmd_bracket_headroom`, `dynamic_sequencing_bracket_rate`, and `gate_on_adaptive_rules`.
Every field is optional; the service resolves defaults for anything omitted.

The response includes the resolved settings plus `yearly_spending`, `median_final_balance`,
`failure_rate`, `success_probability`, `percentile10_final`, `stale`,
`conversion_schedule`, and `stochastic_mortality`.

## Spending Profiles

| Endpoint                           | Method | Description                    |
|------------------------------------|--------|--------------------------------|
| `/api/v1/spending-profiles`        | POST   | Create a profile (201)         |
| `/api/v1/spending-profiles`        | GET    | List profiles                  |
| `/api/v1/spending-profiles/{id}`   | GET    | Get one profile                |
| `/api/v1/spending-profiles/{id}`   | PUT    | Update a profile               |
| `/api/v1/spending-profiles/{id}`   | DELETE | Delete a profile (204)         |

Request carries `name`, `essential_expenses`, `discretionary_expenses`, and
`spending_tiers` — a list of `{ name, start_age, end_age, essential_expenses,
discretionary_expenses }` age-banded phases.

## Income Sources

| Endpoint                           | Method | Description                    |
|------------------------------------|--------|--------------------------------|
| `/api/v1/income-sources`           | POST   | Create an income source (201)  |
| `/api/v1/income-sources`           | GET    | List income sources            |
| `/api/v1/income-sources/{id}`      | GET    | Get one income source          |
| `/api/v1/income-sources/{id}`      | PUT    | Update an income source        |
| `/api/v1/income-sources/{id}`      | DELETE | Delete an income source (204)  |

Request and response carry `name`, `income_type`, `annual_amount`, `start_age`,
`end_age`, `inflation_rate` (0-1), `one_time`, `tax_treatment`, an optional `property_id`
linking rental income to a property, and the household fields `owner` (`primary` or
`spouse`, defaulting to `primary`) and `survivor_percent` (0-1, ignored for
Social-Security-typed sources). Responses also include `property_address`, `created_at`,
`updated_at`.

## Exchange Rates

| Endpoint                                  | Method | Description                    |
|-------------------------------------------|--------|--------------------------------|
| `/api/v1/exchange-rates`                  | GET    | List the tenant's rates        |
| `/api/v1/exchange-rates`                  | POST   | Create a rate (201)            |
| `/api/v1/exchange-rates/{currencyCode}`   | PUT    | Update a rate                  |
| `/api/v1/exchange-rates/{currencyCode}`   | DELETE | Delete a rate (204)            |

Request is `{ "currency_code": "EUR", "rate_to_usd": 1.09 }`. `currency_code` must be
exactly three uppercase letters and `rate_to_usd` must be greater than zero.

## Dashboard

| Endpoint                                 | Method | Description                                       |
|------------------------------------------|--------|---------------------------------------------------|
| `/api/v1/dashboard/summary`              | GET    | Net worth, allocation breakdown, account balances |
| `/api/v1/dashboard/portfolio-history`    | GET    | Aggregate portfolio history (`years`, default 2)  |
| `/api/v1/dashboard/snapshot-projection`  | GET    | Forward projection from today's snapshot (`years` default 10, `lookback` default 10) |

`summary` returns `net_worth`, `total_investments`, `total_cash`,
`total_property_equity`, `accounts`, `allocation`.

## Audit Log

| Endpoint                          | Method | Description                                       |
|-----------------------------------|--------|---------------------------------------------------|
| `/api/v1/audit-log`               | GET    | Paginated audit log. Requires `admin` or `super_admin`. Params: `page`, `size` (default 50), `entity_type` |

Each entry carries `id`, `tenant_id`, `user_id`, `action`, `entity_type`, `entity_id`,
a JSONB `details` object, and `created_at`.

## Data Export

| Endpoint                            | Method | Description                                  |
|-------------------------------------|--------|----------------------------------------------|
| `/api/v1/export/json`               | GET    | Full tenant export (accounts, transactions, holdings, properties) |
| `/api/v1/export/csv/accounts`       | GET    | Accounts as CSV                              |
| `/api/v1/export/csv/transactions`   | GET    | Transactions as CSV                          |
| `/api/v1/export/csv/holdings`       | GET    | Holdings as CSV                              |
| `/api/v1/export/csv/properties`     | GET    | Properties as CSV                            |

All five set `Content-Disposition: attachment`; the CSV endpoints return `text/csv`.

## Notification Preferences

| Endpoint                                  | Method | Description                        |
|-------------------------------------------|--------|------------------------------------|
| `/api/v1/notifications/preferences`       | GET    | This user's notification preferences |
| `/api/v1/notifications/preferences`       | PUT    | Replace the preference set (200)   |

GET returns one row per known notification type — `LARGE_TRANSACTION`, `IMPORT_COMPLETE`,
`IMPORT_FAILED` — defaulting to `enabled: true` where the user has saved no preference.

PUT body:
```json
{ "preferences": [ { "notification_type": "IMPORT_FAILED", "enabled": false } ] }
```
Both fields on each item are required; a null `enabled` is a 400.

## Mobile App Version

| Endpoint                       | Method | Description                                          |
|--------------------------------|--------|------------------------------------------------------|
| `/api/v1/app/version-check`    | GET    | Anonymous. Required params: `platform`, `version`     |

Returns `platform`, `current_version`, `minimum_supported_version`, `latest_version`,
`update_required`, `update_recommended`, `store_url`, `message`. A missing `platform` or
`version` is a 400. The matching admin endpoints are under
[Super-Admin](#super-admin).

## Tenant Management

Admin or super-admin, scoped to the caller's own tenant.

| Endpoint                                  | Method | Description                        |
|-------------------------------------------|--------|------------------------------------|
| `/api/v1/tenant/invite-codes`             | POST   | Generate an invite code (201)      |
| `/api/v1/tenant/invite-codes`             | GET    | List invite codes                  |
| `/api/v1/tenant/invite-codes/{id}/revoke` | PUT    | Revoke an invite code (204)        |
| `/api/v1/tenant/invite-codes/used`        | DELETE | Purge consumed codes; returns `{ "deleted": n }` |
| `/api/v1/tenant/users`                    | GET    | List users in this tenant          |
| `/api/v1/tenant/users/{id}/role`          | PUT    | Update a user's role               |
| `/api/v1/tenant/users/{id}`               | DELETE | Remove a user (204)                |

The invite-code POST body is optional; supply `{ "expiry_days": 14 }` to override the
7-day default. `PUT .../role` takes `{ "role": "admin" }` where role is one of `admin`,
`member`, `viewer`.

## Super-Admin

Everything under `/api/v1/admin/` requires `super_admin`, except `/api/v1/admin/prices/**`
which also accepts `admin`.

### Tenants

| Endpoint                              | Method | Description                        |
|---------------------------------------|--------|------------------------------------|
| `/api/v1/admin/tenants`               | POST   | Create a tenant (201)              |
| `/api/v1/admin/tenants`               | GET    | List tenants                       |
| `/api/v1/admin/tenants/details`       | GET    | List with user and account counts  |
| `/api/v1/admin/tenants/{id}`          | GET    | One tenant with its counts         |
| `/api/v1/admin/tenants/{id}/active`   | PUT    | Enable or disable a tenant (204)   |

Create takes `{ "name": "..." }`; the active toggle takes `{ "active": true }`.

### Users

| Endpoint                                    | Method | Description                          |
|---------------------------------------------|--------|--------------------------------------|
| `/api/v1/admin/users`                       | GET    | Every user across every tenant       |
| `/api/v1/admin/users/{userId}/password`     | PUT    | Reset a password (204)               |
| `/api/v1/admin/users/{userId}/active`       | PUT    | Enable or disable a user (204)       |

Password reset takes `{ "new_password": "..." }` — 12 to 64 characters.

### System

| Endpoint                          | Method | Description                                   |
|-----------------------------------|--------|-----------------------------------------------|
| `/api/v1/admin/system-stats`      | GET    | Row counts, database size, symbol staleness   |
| `/api/v1/admin/login-activity`    | GET    | Recent login attempts (`limit`, default 50)   |
| `/api/v1/admin/config`            | GET    | All system config key/value pairs             |
| `/api/v1/admin/config/{key}`      | PUT    | Set one config value (204)                    |

`PUT config/{key}` takes `{ "value": "..." }`.

### Prices (admin or super-admin)

| Endpoint                                    | Method | Description                                |
|---------------------------------------------|--------|--------------------------------------------|
| `/api/v1/admin/prices/sync`                 | POST   | Trigger the Finnhub daily sync             |
| `/api/v1/admin/prices/status`               | GET    | Per-symbol latest date, source, staleness  |
| `/api/v1/admin/prices/yahoo/sync`           | POST   | Sync every tracked symbol from Yahoo       |
| `/api/v1/admin/prices/yahoo/fetch`          | POST   | Fetch (without saving) a symbol/date range |
| `/api/v1/admin/prices/yahoo/save`           | POST   | Bulk-upsert fetched prices (204)           |
| `/api/v1/admin/prices/csv`                  | POST   | Import prices from a CSV upload            |
| `/api/v1/admin/prices/{symbol}/history`     | GET    | Price history. Required params: `from`, `to` |
| `/api/v1/admin/prices/{symbol}/{date}`      | DELETE | Delete one price row (204)                 |

`yahoo/fetch` takes `{ "symbols": [...], "from_date": "...", "to_date": "..." }`;
`yahoo/save` takes `{ "prices": [ { "symbol": ..., "date": ..., "close_price": ... } ] }`.
`prices/sync` returns 503 when no Finnhub API key is configured.

### Mobile versions

| Endpoint                                       | Method | Description                     |
|------------------------------------------------|--------|---------------------------------|
| `/api/v1/admin/mobile-versions`                | GET    | List the per-platform version rows |
| `/api/v1/admin/mobile-versions/{platform}`     | PUT    | Update one platform's version policy |

PUT requires `minimum_supported_version`, `latest_version`, and `store_url`; `message` is
optional.

---

## Related Docs

- [Administration Guide](../administration/tenant-and-user-management.md) — Role matrix and admin capabilities
- [Data Model Reference](data-model.md) — Entity definitions and relationships
- [Frontend Routes](frontend-routes.md) — SPA routes that consume these endpoints
