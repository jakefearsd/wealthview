[← Back to README](../../README.md)

# Tenant & User Management

This guide covers the administrative operations for managing tenants, users, roles, invite
codes, audit logging, data exports, and scheduled jobs in WealthView.

All of it is reachable from the consolidated admin area at `/admin`. The old `/settings`
and `/audit-log` routes now redirect there, as does `/admin/prices`.

---

## Super-Admin Account

WealthView ships with a built-in super-admin account created on first startup by
`SuperAdminInitializer` (active on the `dev`, `docker`, and `prod` profiles):

- **Email:** `admin@wealthview.local`
- **Password:** the value of `SUPER_ADMIN_PASSWORD`. It is a required environment variable
  in Docker and production — the compose files refuse to start without it, and
  `ProductionConfigValidator` rejects known development defaults.
- **Tenant:** a tenant named `System`, created alongside the account.

In the database the row has `role = 'admin'` and `is_super_admin = true`; the issued JWT
carries the role `super_admin` (`TokenService`), which is what the security rules match on.
The `is_super_admin` flag cannot be assigned through the UI or API.

**Important:** set `SUPER_ADMIN_PASSWORD` to a strong value *before* first startup. The
initializer only creates the account when the email does not already exist — it never
resets an existing password. To change it afterwards, use one of:

- the admin UI: `/admin` → **Users** → **Reset PW**
- the API: `PUT /api/v1/admin/users/{userId}/password`
- `./wv rotate-secret SUPER_ADMIN_PASSWORD` — writes a new value to the env file and
  updates the database row, but only when `python3` has the `bcrypt` module available (see
  [maintenance.md](maintenance.md#rotating-secrets))

### What the Super-Admin Can Do

Everything under `/api/v1/admin/**` requires the `SUPER_ADMIN` role, with one exception:
`/api/v1/admin/prices/**` is open to `ADMIN` as well.

| Operation | Endpoint | Description |
|-----------|----------|-------------|
| Create a tenant | `POST /api/v1/admin/tenants` | Body: `{ "name": "Acme Corp" }`. Returns `201 Created` with `id`, `name`, `created_at` |
| List tenants | `GET /api/v1/admin/tenants` | `200 OK` with an array of tenant objects |
| List tenants with details | `GET /api/v1/admin/tenants/details` | Adds `is_active`, `user_count`, `account_count` per tenant |
| Get one tenant | `GET /api/v1/admin/tenants/{id}` | Same detail shape for a single tenant |
| Enable/disable a tenant | `PUT /api/v1/admin/tenants/{id}/active` | Body: `{ "active": true }` or `{ "active": false }`. Returns `204 No Content` |
| List all users (cross-tenant) | `GET /api/v1/admin/users` | Every user with `role`, `tenant_id`, `tenant_name`, `is_active` |
| Reset a user's password | `PUT /api/v1/admin/users/{userId}/password` | Body: `{ "new_password": "..." }`, 12--64 chars, rejected if it is a known common password. `204 No Content` |
| Activate/deactivate a user | `PUT /api/v1/admin/users/{userId}/active` | Body: `{ "active": false }`. `204 No Content` |
| System statistics | `GET /api/v1/admin/system-stats` | User/tenant/account/holding/transaction counts, database size, symbols tracked, stale symbols |
| Recent login activity | `GET /api/v1/admin/login-activity` | Email, tenant, IP address, timestamp |
| Read/modify system config | `GET /api/v1/admin/config`, `PUT /api/v1/admin/config/{key}` | Runtime settings such as `finnhub.rate-limit-ms`, `yahoo.rate-limit-ms`, `zillow.scraper.enabled` |

**Expected error responses:** a missing tenant or user is `404 Not Found`; a non-super-admin
caller gets `403 Forbidden`; an unauthenticated caller gets `401 Unauthorized`. All of them
use the standard envelope `{ "error": "...", "message": "...", "status": ... }`.

**Frontend:** the `/admin` page. The sidebar renders super-admin-only sections (Dashboard,
Tenants, Stock Splits, System Config) only when the logged-in role is `super_admin`.

---

## The Admin Area (`/admin`)

`AdminAreaPage` is a single route with a sidebar. Sections:

| Section | Sidebar visibility | What it does |
|---|---|---|
| Dashboard | super-admin only | System stats and recent login activity |
| Users | everyone who reaches `/admin` | Manage users (see [Managing Users](#managing-users)) |
| Tenants | super-admin only | Create tenants, enable/disable them |
| Prices | everyone who reaches `/admin` | Price browser, manual entry, Finnhub/Yahoo sync |
| Stock Splits | super-admin only | Review, add, and un-apply splits |
| Exchange Rates | everyone who reaches `/admin` | Per-currency manual rates |
| Invite Codes | everyone who reaches `/admin` | Generate, revoke, and clean up invite codes |
| System Config | super-admin only | Runtime configuration keys |
| Audit Log | everyone who reaches `/admin` (API requires admin or super-admin) | Paginated audit trail with entity-type filtering |

The default section is **Dashboard** for a super-admin and **Users** for everyone else.

The sidebar only hides the four `superAdminOnly` sections; the rest render for any
authenticated user who lands on `/admin`. What stops non-admins is (a) the main navigation,
which renders the "Admin" link only for `admin` and `super_admin`, and (b) the API itself,
which returns 403 for the calls those sections make. Treat the sidebar as convenience, not
as the access control boundary.

---

## User Roles

The `users.role` column accepts exactly three values — `admin`, `member`, `viewer` — plus
the separate `is_super_admin` flag that produces the effective `super_admin` role.
Authorities are `ROLE_ADMIN`, `ROLE_MEMBER`, `ROLE_VIEWER`, `ROLE_SUPER_ADMIN`.

### Viewer

Read-only. Most `GET /api/v1/**` calls only require authentication, so a Viewer can see the
dashboard, accounts, holdings, properties, projections, and exports — but `POST`, `PUT`,
and `DELETE` are refused with 403. The audit log is the exception among reads:
`GET /api/v1/audit-log` requires `ADMIN` or `SUPER_ADMIN`.

**Use case:** sharing access with a financial advisor or family member who needs to see
data but shouldn't modify it.

### Member

Everything a Viewer can do, plus write access:

- Create, edit, and delete investment accounts
- Add transactions and manage holdings
- Create and manage properties (income, expenses, valuations)
- Create and manage projection scenarios, spending profiles, and income sources
- Import data (CSV, OFX)

Members cannot manage invite codes or other users, and cannot write price data
(`POST`/`PUT`/`DELETE` on `/api/v1/prices/**` requires `ADMIN` or `SUPER_ADMIN`).

**Use case:** the primary user role. Most users should be Members.

### Tenant Admin

Everything a Member can do, plus:

- Generate, revoke, and clean up invite codes for the tenant
- List users in the tenant, change their roles, and remove them
- Write price data and use the admin price tools (`/api/v1/admin/prices/**`)

**Use case:** the person who manages the household's or organization's WealthView instance.

### Super Admin

Everything a Tenant Admin can do within their own tenant, plus the cross-tenant system
management listed above. There is only one super-admin account.

### Role Matrix

| Action | Super Admin | Tenant Admin | Member | Viewer |
|--------|:-----------:|:------------:|:------:|:------:|
| Create/manage tenants | Y | - | - | - |
| Enable/disable tenants | Y | - | - | - |
| Cross-tenant user list, password reset, activate/deactivate | Y | - | - | - |
| Generate/revoke invite codes | Y | Y | - | - |
| Manage tenant users (role change, remove) | Y | Y | - | - |
| Write price data (`/api/v1/prices`, `/api/v1/admin/prices`) | Y | Y | - | - |
| Create/edit accounts | Y | Y | Y | - |
| Create/edit properties | Y | Y | Y | - |
| Create/edit projections | Y | Y | Y | - |
| Import transactions | Y | Y | Y | - |
| View dashboard/data | Y | Y | Y | Y |
| View audit log (API) | Y | Y | - | - |
| Export data | Y | Y | Y | Y |

`GET /api/v1/audit-log` is restricted to `ADMIN` and `SUPER_ADMIN` by an explicit matcher
in `SecurityConfig`. It previously carried no matcher and fell through to
`anyRequest().authenticated()`, so a Member or Viewer could read the tenant's whole audit
trail directly over HTTP even though the nav link is admin-only — UI-only gating is not
access control.

Note the last row, though: `GET /api/v1/export/**` still carries no role restriction beyond
authentication. Any authenticated user of the tenant — Viewer included — can export the
tenant's data.

---

## Invite Code Workflow

WealthView uses invite codes to control registration. New users cannot sign up without a
valid code.

### Lifecycle

1. **Generation:** a Tenant Admin (or Super Admin) generates a code via
   `POST /api/v1/tenant/invite-codes`. The code is a 24-character random string from a
   fixed alphabet.
2. **Expiry window:** 7 days by default. Send `{ "expiry_days": N }` to choose a different
   window — the admin UI exposes this as a numeric input next to "Generate Code".
3. **Sharing:** the admin shares the code out of band (email, chat). Codes are never
   emailed automatically. The UI offers a copy-to-clipboard button.
4. **Consumption:** the invitee registers at `/register` with their email, a password, and
   the code. On success the code records `consumed_by` / `consumed_at`, and the new user
   joins **the code's tenant** with the role `member`.
5. **Terminal states:** a code that is consumed, revoked, or expired cannot be used or
   reactivated. Registration attempts against one fail with `InvalidInviteCodeException`.

### Managing Invite Codes

| Operation | Endpoint | Description |
|-----------|----------|-------------|
| Generate code | `POST /api/v1/tenant/invite-codes` | Optional body `{ "expiry_days": 7 }`. Returns `201 Created` with `code`, `expires_at`, `consumed`, `is_revoked`, `used_by_email`, `created_by_email`, `created_at` |
| List codes | `GET /api/v1/tenant/invite-codes` | All codes for the tenant — active, expired, revoked, and consumed |
| Revoke a code | `PUT /api/v1/tenant/invite-codes/{id}/revoke` | `204 No Content`. The code can no longer be redeemed |
| Delete used codes | `DELETE /api/v1/tenant/invite-codes/used` | Bulk cleanup. Returns `{ "deleted": N }` |

All four require `ADMIN` or `SUPER_ADMIN`.

**Frontend:** `/admin` → **Invite Codes**.

---

## Managing Users

Tenant Admins manage users within their own tenant:

| Operation | Endpoint | Description |
|-----------|----------|-------------|
| List users | `GET /api/v1/tenant/users` | All users in the caller's tenant with `id`, `email`, `role`, `created_at` |
| Change role | `PUT /api/v1/tenant/users/{id}/role` | Body: `{ "role": "member" }` — valid values `admin`, `member`, `viewer`. Returns the updated user |
| Remove user | `DELETE /api/v1/tenant/users/{id}` | `204 No Content` |

Behaviour worth knowing:

- **The super-admin cannot be demoted.** `updateUserRole` rejects any attempt to change the
  role of a user with `is_super_admin = true`.
- **Role changes and password resets invalidate existing sessions.** Both bump the user's
  `token_generation`, so already-issued JWTs stop validating and the user must log in
  again.
- **Deactivating is safer than deleting.** A super-admin can flip `is_active` with
  `PUT /api/v1/admin/users/{userId}/active`, which blocks login while leaving every
  reference intact.

**Data impact of removing a user:**

- The `users` row is deleted. Sessions, refresh tokens, MFA secrets, MFA recovery codes,
  and notification preferences are removed with it (those foreign keys are
  `ON DELETE CASCADE`).
- Tenant-scoped data — accounts, properties, transactions, projections — is untouched.
  Ownership is tenant-scoped, not user-scoped.
- `audit_log.user_id` and `invite_codes.created_by` / `consumed_by` reference `users(id)`
  with **no** `ON DELETE` rule. A user who has logged in, created an invite code, or
  registered through one still has rows pointing at them, so the delete can fail with a
  foreign-key violation. Clearing consumed codes (`DELETE /api/v1/tenant/invite-codes/used`)
  removes one class of reference; audit rows are immutable by design. When in doubt,
  deactivate instead of deleting.

**Frontend:** `/admin` → **Users**. A tenant admin sees their own tenant's users with a
role dropdown and a Delete action. A super-admin sees every user across all tenants, with
extra Tenant and Status columns plus **Reset PW** and **Activate/Deactivate** actions.

---

## Audit Log

Significant actions are recorded in an append-only audit log. Rows are never updated or
deleted by the application.

### What Is Logged

| Action | Entity type | Raised by |
|---|---|---|
| `CREATE`, `DELETE` | `account`, `property`, `transaction` | Account/property/transaction services |
| `UPDATE` | `holding`, `transaction` | Holding/transaction services |
| `CREATE` | `holding`, `tenant` | Holding service, tenant creation |
| `SET_ACTIVE` | `tenant` | Super-admin tenant enable/disable |
| `LOGIN`, `REGISTER` | `user` | Authentication service |
| `USER_ROLE_UPDATE`, `USER_DELETE`, `USER_PASSWORD_RESET`, `USER_SET_ACTIVE` | `user` | User management service |

### Audit Record Fields

| Field | Description |
|-------|-------------|
| `id` | Audit row UUID |
| `tenant_id` | Tenant the action belongs to |
| `user_id` | Acting user, when one is in context (null for some system-initiated writes) |
| `action` | The operation performed |
| `entity_type` | The type of entity affected (`account`, `property`, `user`, ...) |
| `entity_id` | The UUID of the affected entity |
| `details` | JSONB object with additional context (e.g. `old_role`/`new_role`, `email`) |
| `created_at` | Timestamp of the event |

The table also stores `ip_address`, but the API response does not include it. Query the
`audit_log` table directly (`./wv psql`) if you need it.

### Querying the Audit Log

```
GET /api/v1/audit-log?page=0&size=20&entity_type=account
```

- **Pagination:** `page` (default 0) and `size` (default 50); both are clamped to safe
  bounds server-side.
- **Filtering:** `entity_type` restricts to one entity type.
- **Scope:** results are always limited to the caller's tenant.
- **Shape:** a paged envelope whose rows live under `data` (not `content`).

### What to Look For

- **Unauthorized access attempts:** `LOGIN` events at unusual times or from unexpected IPs
  (cross-check `GET /api/v1/admin/login-activity`, which includes the IP address)
- **Unexpected modifications:** `UPDATE`/`DELETE` during off-hours
- **Privilege changes:** `USER_ROLE_UPDATE`, `USER_SET_ACTIVE`, `USER_PASSWORD_RESET`
- **Tenant lockouts:** `SET_ACTIVE` on a tenant

**Frontend:** `/admin` → **Audit Log**, with pagination and an entity-type filter. The old
`/audit-log` route redirects here.

---

## Data Export

Tenant data can be exported for backup, analysis, or migration. All export endpoints are
`GET` and require only an authenticated session.

### Full JSON Export

```
GET /api/v1/export/json
```

Returns a single JSON document with the tenant's accounts, transactions, holdings,
properties (with income, expenses, and valuations), projection scenarios, spending
profiles, and income sources.

**Use cases:** full data backup, migrating to another instance, analysis in external tools.

### Per-Entity CSV Exports

| Endpoint | Content |
|----------|---------|
| `GET /api/v1/export/csv/accounts` | All accounts |
| `GET /api/v1/export/csv/transactions` | All transactions with account reference |
| `GET /api/v1/export/csv/holdings` | All holdings |
| `GET /api/v1/export/csv/properties` | All properties |

**Use cases:** spreadsheets, portfolio analysis tools, tax preparation.

**Frontend:** `/export` with a download button per format.

> A data export is not a substitute for a database backup — it covers one tenant's
> business data, not the schema, users, or audit history. Use `./wv backup` for that; see
> [Backups](backups.md).

---

## Scheduled Jobs

Three background jobs run on a schedule. Full detail, verification queries, and failure
handling live in [maintenance.md](maintenance.md#scheduled-jobs); the summary:

| Job | Schedule | Requirement |
|---|---|---|
| Price sync (Finnhub) | Weekdays 6:00 PM ET | `FINNHUB_API_KEY` set — without it the job's beans do not exist |
| Stock split sync | Daily 2:00 AM ET | `FINNHUB_API_KEY` |
| Zillow valuation sync | Sundays 6:00 AM server time | `ZILLOW_ENABLED=true` |

**What if a job fails?**

- **Price sync:** individual symbol failures are logged at WARN and the loop continues.
  Requests are throttled by `app.finnhub.rate-limit-ms` (default 1100ms).
- **Stock split sync:** per-symbol failures are logged and counted
  (`wealthview.splits.sync_failed`); the rest of the symbols still process.
- **Zillow sync:** Zillow may block or rate-limit scraping. Failures are logged and the
  property's stored value is left unchanged — no data is lost.
- There is no retry mechanism. Each job simply runs again at its next scheduled time.
  Price sync can also be triggered on demand from `/admin` → **Prices**.

---

## Related Docs

- [Backup Operations Guide](backups.md) — on-demand and scheduled backups, restore
- [Monitoring & Logging](monitoring-and-logging.md) — health checks, log parsing, metrics
- [Maintenance](maintenance.md) — updates, rollback, scheduled jobs, capacity planning
- [Troubleshooting](troubleshooting.md) — diagnostics and common problem resolution
- [Operations Handbook](../deployment/operations.md) — the `wv` admin command surface
- [Configuration Reference](../reference/configuration.md) — environment variables and Spring profiles
- [API Reference](../reference/api-reference.md) — full endpoint documentation
