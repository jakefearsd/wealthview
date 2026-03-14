[← Back to README](../../README.md)

# Tenant & User Management

This guide covers the administrative operations for managing tenants, users, roles, invite codes, audit logging, data exports, and scheduled jobs in WealthView.

---

## Super-Admin Account

WealthView ships with a built-in super-admin account that is automatically created on first startup:

- **Email:** `admin@wealthview.local`
- **Password:** set by `SUPER_ADMIN_PASSWORD` environment variable (defaults to `admin123`)

The super-admin has cross-tenant system-level privileges. It is the only account with the `is_super_admin` flag set to `true`. This flag cannot be assigned through the UI or API — it is set only during application initialization.

**Important:** Change the super-admin password immediately in production by setting `SUPER_ADMIN_PASSWORD` to a strong value before first startup. If the app has already started with the default password, change it in the database or redeploy with a new password and a fresh database.

### What the Super-Admin Can Do

| Operation | Endpoint | Description |
|-----------|----------|-------------|
| Create a tenant | `POST /api/v1/admin/tenants` | Creates a new tenant organization. Request body: `{ "name": "Acme Corp" }` |
| List tenants | `GET /api/v1/admin/tenants` | Returns all tenants with basic info (id, name, is_active) |
| List tenants with details | `GET /api/v1/admin/tenants/details` | Same as above plus user counts per tenant |
| Enable a tenant | `PUT /api/v1/admin/tenants/{id}/active` | Body: `{ "is_active": true }` — re-enables login for all users in the tenant |
| Disable a tenant | `PUT /api/v1/admin/tenants/{id}/active` | Body: `{ "is_active": false }` — blocks all authentication for the tenant's users |

**Expected responses:**

- Create tenant: `201 Created` with the new tenant object
- List tenants: `200 OK` with an array of tenant objects
- Enable/disable: `200 OK` with the updated tenant object
- If the tenant ID doesn't exist: `404 Not Found`

**Frontend:** Super-admin operations are available on the `/admin` page, visible only when logged in as the super-admin.

---

## User Roles

WealthView has four roles, each building on the permissions of the one below it:

### Viewer

Read-only access. Can view the dashboard, accounts, holdings, properties, projections, and all data — but cannot create, edit, or delete anything.

**Use case:** Sharing access with a financial advisor or family member who needs to see data but shouldn't modify it.

### Member

Everything a Viewer can do, plus:

- Create, edit, and delete investment accounts
- Add transactions and manage holdings
- Create and manage properties (income, expenses, valuations)
- Create and manage projection scenarios, spending profiles, and income sources
- Import data (CSV, OFX)
- Export data (JSON, CSV)

**Use case:** The primary user role. Most users should be Members.

### Tenant Admin

Everything a Member can do, plus:

- Generate and manage invite codes for the tenant
- View, modify, and remove other users in the tenant
- Change user roles (promote Member to Admin, demote Admin to Viewer, etc.)
- View the audit log

**Use case:** The person who manages the household or organization's WealthView instance.

### Super Admin

Everything a Tenant Admin can do within their own tenant, plus cross-tenant system management (see above). There is only one super-admin account.

### Role Matrix

| Action | Super Admin | Tenant Admin | Member | Viewer |
|--------|:-----------:|:------------:|:------:|:------:|
| Create/manage tenants | Y | - | - | - |
| Enable/disable tenants | Y | - | - | - |
| Generate invite codes | Y | Y | - | - |
| Manage tenant users | Y | Y | - | - |
| Create/edit accounts | Y | Y | Y | - |
| Create/edit properties | Y | Y | Y | - |
| Create/edit projections | Y | Y | Y | - |
| Import transactions | Y | Y | Y | - |
| View dashboard/data | Y | Y | Y | Y |
| View audit log | Y | Y | - | - |
| Export data | Y | Y | Y | - |

---

## Invite Code Workflow

WealthView uses invite codes to control user registration. New users cannot sign up without a valid code.

### Lifecycle

1. **Generation:** A Tenant Admin (or Super Admin) generates an invite code via `POST /api/v1/tenant/invite-codes`. The code is an 8-character alphanumeric string.
2. **Sharing:** The admin shares the code with the person they want to invite (via email, chat, etc.). Codes are not emailed automatically.
3. **Expiration:** Codes expire 7 days after creation. Expired codes cannot be used.
4. **Consumption:** The invitee registers at `/register` using their email, a password, and the invite code. On successful registration, the code is marked as consumed (records `consumed_by` and `consumed_at`).
5. **Immutable:** Once consumed or expired, the code cannot be reused or reactivated.

### Managing Invite Codes

| Operation | Endpoint | Description |
|-----------|----------|-------------|
| Generate code | `POST /api/v1/tenant/invite-codes` | Returns the new code object with `code`, `expires_at` |
| List codes | `GET /api/v1/tenant/invite-codes` | Lists all codes — active, expired, and consumed |

**Frontend:** Available on the `/settings` page under the "Invite Codes" section.

---

## Managing Users

Tenant Admins can manage users within their tenant:

| Operation | Endpoint | Description |
|-----------|----------|-------------|
| List users | `GET /api/v1/tenant/users` | Returns all users in the tenant with their roles |
| Change role | `PUT /api/v1/tenant/users/{id}/role` | Body: `{ "role": "member" }` — valid values: `admin`, `member`, `viewer` |
| Remove user | `DELETE /api/v1/tenant/users/{id}` | Permanently removes the user from the tenant |

**Data impact of removing a user:**

- The user record is deleted from the `users` table.
- All data owned by the tenant (accounts, properties, projections) remains intact — data is tenant-scoped, not user-scoped.
- Audit log entries referencing the user are preserved (they use raw UUIDs, not foreign keys).

**Frontend:** Available on the `/settings` page under the "User Management" section (visible to admins only).

---

## Audit Log

Every significant action in the system is recorded in an immutable audit log. Audit records are append-only — they are never updated or deleted.

### What Is Logged

- Entity lifecycle events: `CREATE`, `UPDATE`, `DELETE` for accounts, properties, transactions, projections, etc.
- Authentication events: `LOGIN` (successful and failed)
- Administrative actions: tenant enable/disable, role changes, invite code generation

### Audit Record Fields

| Field | Description |
|-------|-------------|
| `action` | The operation performed (CREATE, UPDATE, DELETE, LOGIN) |
| `entity_type` | The type of entity affected (Account, Property, Transaction, etc.) |
| `entity_id` | The UUID of the affected entity |
| `details` | JSONB object with additional context (before/after values, metadata) |
| `ip_address` | The client IP address that initiated the action |
| `created_at` | Timestamp of the event |

### Querying the Audit Log

```
GET /api/v1/audit-log?page=0&size=20&entity_type=Account
```

- **Pagination:** Use `page` and `size` query parameters
- **Filtering:** Use `entity_type` to filter by entity type

### What to Look For

- **Unauthorized access attempts:** Look for LOGIN events with unusual IP addresses or high frequency
- **Unexpected modifications:** Look for UPDATE or DELETE events during off-hours
- **Import activity:** Track import job creation and completion
- **Role changes:** Monitor changes to user roles

**Frontend:** Available at `/audit-log` with pagination controls and entity type filtering.

---

## Data Export

Tenant data can be exported for backup, analysis, or migration purposes.

### Full JSON Export

```
GET /api/v1/export/json
```

Returns a single JSON document containing all tenant data: accounts, transactions, holdings, properties (with income, expenses, and valuations), projection scenarios, spending profiles, and income sources.

**Use cases:** Full data backup, migrating to another instance, data analysis in external tools.

### Per-Entity CSV Exports

| Endpoint | Content |
|----------|---------|
| `GET /api/v1/export/csv/accounts` | All accounts with type and institution |
| `GET /api/v1/export/csv/transactions` | All transactions with account reference |
| `GET /api/v1/export/csv/holdings` | All holdings with current values |

**Use cases:** Importing into spreadsheets, portfolio analysis tools, tax preparation.

**Frontend:** Available at `/export` with download buttons for each format.

---

## Scheduled Jobs

WealthView runs two automated background jobs:

### Price Sync

- **Schedule:** Weekdays at 4:30 PM (server timezone)
- **What it does:** Fetches the latest closing prices from the Finnhub API for all ticker symbols that appear in holdings across all tenants
- **Prerequisite:** `FINNHUB_API_KEY` must be set. When empty, the job is disabled entirely.
- **Rate limiting:** Requests are throttled to respect Finnhub's free-tier limit (60 requests/minute, configurable via `app.finnhub.rate-limit-ms`)

**Verifying it ran:**

```bash
# Check application logs for price sync activity
docker compose logs app | grep -i "price sync"

# Check if prices exist for today
docker compose exec db psql -U wv_app wealthview \
  -c "SELECT symbol, date, close_price FROM prices WHERE date = CURRENT_DATE ORDER BY symbol;"
```

### Zillow Valuation Sync

- **Schedule:** Sunday at 6:00 AM (server timezone)
- **What it does:** Scrapes Zillow Zestimates for all properties that have a `zillow_zpid` configured. Updates the property's `current_value` and creates a `property_valuations` record.
- **Prerequisite:** `ZILLOW_ENABLED` must be `true`. When `false`, the job is disabled.

**Verifying it ran:**

```bash
# Check logs for Zillow activity
docker compose logs app | grep -i "zillow"

# Check recent valuations
docker compose exec db psql -U wv_app wealthview \
  -c "SELECT p.address, v.valuation_date, v.value FROM property_valuations v JOIN properties p ON p.id = v.property_id ORDER BY v.valuation_date DESC LIMIT 10;"
```

### What If a Job Fails

- **Price sync:** Failures are logged as warnings. Individual symbol failures don't stop the job — it continues with the next symbol. Check logs for `ERROR` or `WARN` entries related to Finnhub.
- **Zillow sync:** Zillow may block or rate-limit scraping requests. Failures are logged and the property's value remains unchanged. No data is lost on failure.
- Both jobs run on their next scheduled occurrence regardless of previous failures. There is no retry mechanism — if a run fails, it will try again at the next scheduled time.

---

## Related Docs

- [Backup Operations Guide](backups.md) — Automated backup scheduling and restore procedures
- [Monitoring & Logging](monitoring-and-logging.md) — Health checks, log configuration, and alerting
- [Maintenance](maintenance.md) — Database maintenance, disk space, and capacity planning
- [Troubleshooting](troubleshooting.md) — Diagnostics and common problem resolution
- [Configuration Reference](../reference/configuration.md) — Environment variables and Spring profiles
- [API Reference](../reference/api-reference.md) — Full endpoint documentation
