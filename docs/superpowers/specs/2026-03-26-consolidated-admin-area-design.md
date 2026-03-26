# Consolidated Admin Area — Design Spec

## Context

Admin features are scattered across 4 separate pages (Admin, Settings, Price Admin,
Audit Log) with inconsistent navigation and missing capabilities. Key gaps include:
no password reset, no system config UI, no login activity visibility, no invite code
revocation, and no price data browsing. This spec consolidates everything into a
unified admin area with a sidebar navigation pattern.

## Consolidated Admin Area

Replace scattered admin pages with a unified `/admin` page using sidebar navigation.

### Sidebar Sections
1. **Dashboard** — system stats, login activity, quick-action price sync
2. **Users** — user management with password reset, deactivation
3. **Tenants** — create/manage tenants (super_admin only)
4. **Prices** — Finnhub/Yahoo/CSV import tabs + price data browser
5. **Invite Codes** — generate, list, revoke, delete used
6. **System Config** — API keys, feature flags, app settings (super_admin only)
7. **Audit Log** — existing audit log (moved here)

### Access Control
- `super_admin` sees all sections
- `admin` sees Users (own tenant), Invite Codes, Audit Log
- Old standalone pages (`/settings`, `/admin`, `/admin/prices`, `/audit-log`) redirect
  to the consolidated area

---

## Section 1: Dashboard

### Stat Cards (3 rows)

**Row 1: Users & Tenants**
- Total users, active users (logged in within 30 days), total tenants (super_admin)

**Row 2: Data**
- Total accounts/holdings/transactions, database size (`pg_database_size()`),
  last backup timestamp

**Row 3: Prices**
- Symbols tracked, stale count (>2 trading days old)
- Last Finnhub sync + "Sync Now" button (spinner, toast with results)
- Last Yahoo sync + "Sync Now" button (same UX)

### Login Activity Table
Recent logins below the cards: user email, timestamp, IP address, success/failure.

### Backend
- New `login_activity` table: `id, user_email, tenant_id, success, ip_address, created_at`
- `AuthService.login()` records each attempt
- `GET /api/v1/admin/login-activity?limit=50`
- `GET /api/v1/admin/system-stats` — user counts, data counts, DB size

---

## Section 2: Users

### User List Table
Email, role, tenant name (super_admin), join date, last login. Sortable, filterable
by role and tenant.

### Actions Per User
- **Change role** — inline dropdown
- **Reset password** — `PUT /api/v1/admin/users/{id}/password` with `{ newPassword }`.
  Super_admin resets any user; admin resets own tenant only. User told out-of-band.
- **Deactivate** — soft disable via `is_active` flag. Deactivated users can't login.
  `PUT /api/v1/admin/users/{id}/active`
- **Delete** — hard delete with confirmation (existing, kept as fallback)

### Backend
- V048: `ALTER TABLE users ADD COLUMN IF NOT EXISTS is_active boolean NOT NULL DEFAULT true`
- `AuthService.login()` checks `is_active` — returns 403 "Account disabled"
- `PUT /api/v1/admin/users/{id}/password`
- `PUT /api/v1/admin/users/{id}/active`

---

## Section 3: Invite Codes

### Improvements
- **Generate** with configurable expiry (1/7/30/custom days, range 1-90)
- **Table:** Code, created by, created date, expires, status (Active/Used/Expired/Revoked),
  used by email. Copy-to-clipboard button.
- **Revoke** — marks active code as revoked. Registration rejects revoked codes.
- **Delete Used Codes** — button with confirmation, removes all consumed codes for tenant.

### Backend
- V049: `ALTER TABLE invite_codes ADD COLUMN IF NOT EXISTS is_revoked boolean NOT NULL DEFAULT false`
- `POST /api/v1/tenant/invite-codes` gains optional `expiryDays` (default 7, range 1-90)
- `PUT /api/v1/tenant/invite-codes/{id}/revoke`
- `DELETE /api/v1/tenant/invite-codes/used` — returns `{ deleted: N }`
- Registration checks `is_revoked` flag

---

## Section 4: Prices

### Existing Tabs (unchanged)
- Finnhub sync
- Yahoo Finance fetch/sync
- CSV upload

### New: Browse Tab
- Symbol selector (dropdown or type-to-search)
- Date range picker (default last 30 days)
- Price table: Date, Close Price, Source — with delete per row
- Simple line chart of prices over the date range

### Backend
- `GET /api/v1/admin/prices/{symbol}?from=YYYY-MM-DD&to=YYYY-MM-DD`
- `DELETE /api/v1/admin/prices/{symbol}/{date}`

---

## Section 5: System Config (Super Admin Only)

### Database-backed with hot reload
New `system_config` table storing key-value pairs. Services read from
`SystemConfigService` (cached) instead of `@Value` annotations.

### Config Sections

**API Keys:**
- Finnhub API Key (masked display, edit reveals)
- Zillow scraper toggle

**Application Settings:**
- CORS allowed origins
- JWT access token expiry (1hr/4hr/8hr/24hr)
- JWT refresh token expiry (1d/7d/30d)

**Price Sync:**
- Finnhub sync schedule (daily 6PM ET / twice daily / manual only)
- Finnhub rate limit (ms)
- Yahoo rate limit (ms)

### Backend
- V050: `CREATE TABLE system_config (key text PRIMARY KEY, value text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now())`
- Seed defaults from application.yml on startup if keys don't exist
- `SystemConfigService` with `@Cacheable`, evicts on update
- `GET /api/v1/admin/config` — all entries (sensitive keys masked)
- `PUT /api/v1/admin/config/{key}` — update + cache evict

---

## Section 6: Audit Log (moved from standalone page)

Existing functionality relocated to the admin sidebar. No changes to behavior —
same filtered, paginated log. Remove the standalone `/audit-log` route.

---

## Migrations

| Migration | Change |
|-----------|--------|
| V048 | `users.is_active` boolean column |
| V049 | `invite_codes.is_revoked` boolean column |
| V050 | `system_config` table |
| V051 | `login_activity` table |

## Files to Create

| File | Purpose |
|------|---------|
| `SystemConfigService.java` | DB-backed config with caching |
| `SystemConfigEntity.java` + repository | Config persistence |
| `LoginActivityService.java` | Record and query login attempts |
| `LoginActivityEntity.java` + repository | Login activity persistence |
| `frontend/src/pages/AdminAreaPage.tsx` | Consolidated admin with sidebar |
| `frontend/src/components/admin/DashboardSection.tsx` | Stats + login activity |
| `frontend/src/components/admin/UsersSection.tsx` | User management |
| `frontend/src/components/admin/InviteCodesSection.tsx` | Invite code management |
| `frontend/src/components/admin/SystemConfigSection.tsx` | Config editor |
| `frontend/src/components/admin/PriceBrowserTab.tsx` | Price data browser |
| `frontend/src/api/adminConfig.ts` | Config API client |
| `frontend/src/api/adminUsers.ts` | User admin API client |
| V048-V051 migrations | Schema changes |

## Files to Modify

| File | Changes |
|------|---------|
| `AuthService.java` | Check `is_active`, record login activity |
| `SuperAdminController.java` | New endpoints (password reset, user active, config, login activity, system stats, price browse/delete) |
| `TenantManagementController.java` | Invite code revoke, delete used, configurable expiry |
| `TenantService.java` | Invite code revoke, delete used, expiry param |
| `UserManagementService.java` | Password reset, user deactivation |
| `PriceService.java` | Price browse by symbol/date, delete single price |
| `PriceRepository.java` | findBySymbolAndDateBetween, deleteBySymbolAndDate |
| `Layout.tsx` | Consolidate nav items to single "Admin" entry |
| `App.tsx` | New route, redirects for old pages |

## Not In Scope
- 2FA/MFA (may add later)
- Email/SMTP notification delivery (may add later)
- Tenant quotas / rate limiting
- Session management / remote logout

## Verification
- `cd backend && mvn clean install -DskipITs` — all pass
- `cd frontend && npm run test -- --run && npm run build` — all pass
- Manual: login as super_admin, navigate consolidated admin, test all 7 sections
- Manual: login as tenant admin, verify restricted sections hidden
- Manual: deactivate a user, verify they can't login
- Manual: reset a user's password, verify new password works
- Manual: change a system config value, verify hot reload (no restart)
