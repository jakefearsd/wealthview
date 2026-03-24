# REST API Guide

**Base path:** `/api/v1/`

All endpoints except `/auth/login` and `/auth/register` require an `Authorization: Bearer <token>`
header. JSON field names follow `snake_case` throughout.

---

## Authentication

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/auth/login` | Public | Email + password; returns `access_token` (1 h) and `refresh_token` (24 h) |
| `POST` | `/auth/register` | Public | Email, password, invite code; creates user within the code's tenant |
| `POST` | `/auth/refresh` | Public | Exchange a valid refresh token for a new access token |

The access token is a JWT containing `tenant_id`, `user_id`, and `role`. The tenant isolation
filter extracts `tenant_id` on every request — callers cannot spoof a different tenant.

---

## Accounts

| Method | Path | Min Role | Description |
|---|---|---|---|
| `GET` | `/accounts` | Viewer | List all accounts for the tenant |
| `POST` | `/accounts` | Member | Create an account |
| `GET` | `/accounts/{id}` | Viewer | Fetch a single account |
| `PUT` | `/accounts/{id}` | Member | Update name, type, institution |
| `DELETE` | `/accounts/{id}` | Admin | Delete account and all child data |
| `GET` | `/accounts/{id}/holdings` | Viewer | Current holdings for the account |
| `GET` | `/accounts/{id}/transactions` | Viewer | Paginated transaction history |
| `GET` | `/accounts/{id}/portfolio-history` | Viewer | Theoretical portfolio value over time |

---

## Holdings & Transactions

| Method | Path | Min Role | Description |
|---|---|---|---|
| `POST` | `/holdings` | Member | Create a manual holding override |
| `GET` | `/holdings/{id}` | Viewer | Fetch a holding |
| `PUT` | `/holdings/{id}` | Member | Update quantity / cost basis (sets `is_manual_override = true`) |
| `DELETE` | `/holdings/{id}` | Admin | Remove a holding |
| `PUT` | `/transactions/{id}` | Member | Update a transaction; triggers holdings recomputation |
| `DELETE` | `/transactions/{id}` | Admin | Delete a transaction; triggers holdings recomputation |

Holdings are auto-recomputed whenever any transaction in an account+symbol pair is created,
updated, or deleted. A `409 Conflict` is returned if a transaction write conflicts with a
manually overridden holding.

---

## Prices

| Method | Path | Min Role | Description |
|---|---|---|---|
| `POST` | `/prices` | Member | Add a manual price entry `{symbol, date, close_price}` |
| `GET` | `/prices/{symbol}/latest` | Viewer | Most recent price for a symbol (any source) |

The Finnhub daily sync runs automatically at market close and populates prices with
`source = finnhub`. Manual entries always take precedence in dashboard valuation when they are
more recent.

---

## Import

| Method | Path | Min Role | Description |
|---|---|---|---|
| `POST` | `/import/csv` | Member | Upload CSV; `format` param: `fidelity`, `vanguard`, `schwab` |
| `POST` | `/import/positions` | Member | Upload a positions/holdings CSV |
| `POST` | `/import/ofx` | Member | Upload OFX or QFX file (any US broker/bank format) |
| `GET` | `/import/jobs` | Viewer | Paginated import history with status and row counts |

Duplicate detection runs on every import. Transactions already present (matched by SHA-256 hash
of date + amount + description) are silently skipped; `rows_skipped` in the job record reflects
the count.

---

## Dashboard

| Method | Path | Min Role | Description |
|---|---|---|---|
| `GET` | `/dashboard/summary` | Viewer | Net worth, account balances, asset allocation |
| `GET` | `/dashboard/portfolio-history` | Viewer | Aggregate portfolio value over time; optional `?years=5` |

Net worth = Σ(holding × latest price) + Σ(property current value − mortgage balance) + Σ(bank balances).

---

## Properties

| Method | Path | Min Role | Description |
|---|---|---|---|
| `GET` | `/properties` | Viewer | List all properties |
| `POST` | `/properties` | Member | Create a property |
| `GET` | `/properties/{id}` | Viewer | Fetch a property |
| `PUT` | `/properties/{id}` | Member | Update property details |
| `DELETE` | `/properties/{id}` | Admin | Delete property and all child records |
| `GET` | `/properties/{id}/analytics` | Viewer | Cap rate, cash-on-cash, equity growth, mortgage progress |
| `GET` | `/properties/{id}/income` | Viewer | Income line items |
| `POST` | `/properties/{id}/income` | Member | Add income entry |
| `DELETE` | `/properties/income/{id}` | Admin | Delete income entry |
| `GET` | `/properties/{id}/expenses` | Viewer | Expense line items |
| `POST` | `/properties/{id}/expenses` | Member | Add expense entry |
| `DELETE` | `/properties/expenses/{id}` | Admin | Delete expense entry |
| `GET` | `/properties/{id}/valuations` | Viewer | Historical valuation snapshots |
| `POST` | `/properties/{id}/valuations` | Member | Add manual valuation |
| `POST` | `/properties/valuations/refresh` | Member | Trigger Zillow sync for all properties with a ZPID |
| `POST` | `/properties/select-zpid` | Member | Associate a Zillow ZPID with a property |

---

## Retirement Projections

| Method | Path | Min Role | Description |
|---|---|---|---|
| `GET` | `/projections` | Viewer | List all scenarios |
| `POST` | `/projections` | Member | Create a scenario |
| `GET` | `/projections/{id}` | Viewer | Fetch scenario with linked accounts and income sources |
| `PUT` | `/projections/{id}` | Member | Update scenario parameters |
| `DELETE` | `/projections/{id}` | Admin | Delete scenario |
| `POST` | `/projections/compute/{id}` | Member | Run the deterministic projection; returns year-by-year results |
| `POST` | `/projections/compare` | Member | Side-by-side comparison of multiple scenarios |

---

## Spending Profiles

| Method | Path | Min Role | Description |
|---|---|---|---|
| `GET` | `/spending-profiles` | Viewer | List all spending profiles |
| `POST` | `/spending-profiles` | Member | Create a tier-based spending profile |
| `GET` | `/spending-profiles/{id}` | Viewer | Fetch profile with tiers |
| `PUT` | `/spending-profiles/{id}` | Member | Update |
| `DELETE` | `/spending-profiles/{id}` | Admin | Delete |

---

## Guardrail / Monte Carlo Optimization

| Method | Path | Min Role | Description |
|---|---|---|---|
| `POST` | `/guardrails/{scenarioId}/optimize` | Member | Run MC spending optimizer; persists result as `guardrail_spending_profile` |
| `GET` | `/guardrails/{scenarioId}/results` | Viewer | Fetch the most recent optimization result |

The optimizer runs 500+ Monte Carlo trials using block-bootstrap return simulation and finds the
highest inflation-adjusted spending level the portfolio sustains at the configured confidence
level (default 55th percentile).

---

## Income Sources

| Method | Path | Min Role | Description |
|---|---|---|---|
| `GET` | `/income-sources` | Viewer | List tenant's income sources (Social Security, pension, etc.) |
| `POST` | `/income-sources` | Member | Create an income source |
| `GET` | `/income-sources/{id}` | Viewer | Fetch |
| `PUT` | `/income-sources/{id}` | Member | Update |
| `DELETE` | `/income-sources/{id}` | Admin | Delete |

---

## Tenant & User Management (Admin)

| Method | Path | Description |
|---|---|---|
| `GET` | `/tenant/users` | List all users in the tenant |
| `PUT` | `/tenant/users/{id}/role` | Change a user's role |
| `DELETE` | `/tenant/users/{id}` | Remove a user |
| `GET` | `/tenant/invite-codes` | List invite codes (pending and consumed) |
| `POST` | `/tenant/invite-codes` | Generate a new invite code |
| `DELETE` | `/tenant/invite-codes/{id}` | Revoke an unused invite code |

---

## Super-Admin

| Method | Path | Description |
|---|---|---|
| `POST` | `/admin/tenants` | Create a new tenant |
| `GET` | `/admin/tenants` | List all tenants |

Super-admin credentials are seeded via `SuperAdminInitializer` on first startup using
environment-variable-configured email/password.

---

## Error Responses

All errors return a standard envelope:

```json
{
  "error": "NOT_FOUND",
  "message": "Account abc123 not found for tenant xyz",
  "status": 404
}
```

| HTTP Status | Error Code | Cause |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Jakarta Bean Validation failure on request body |
| 401 | `UNAUTHORIZED` | Missing or expired JWT |
| 403 | `FORBIDDEN` | Valid JWT but insufficient role for the operation |
| 404 | `NOT_FOUND` | Entity does not exist within the tenant |
| 409 | `CONFLICT` | Duplicate import, duplicate invite code, etc. |
| 500 | `INTERNAL_ERROR` | Unexpected server-side failure |

---

## Pagination

List endpoints that could return large result sets accept `?page=0&size=25` and return:

```json
{
  "data": [...],
  "page": 0,
  "size": 25,
  "total": 142
}
```
