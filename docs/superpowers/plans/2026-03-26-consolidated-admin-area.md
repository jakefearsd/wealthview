# Consolidated Admin Area Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace 4 scattered admin pages with a unified admin area featuring sidebar navigation, system dashboard, user management with password reset/deactivation, invite code improvements, price data browser, database-backed system config, and login activity tracking.

**Architecture:** 4 Flyway migrations (V048-V051) add `users.is_active`, `invite_codes.is_revoked`, `system_config` table, and `login_activity` table. New `SystemConfigService` provides DB-backed hot-reload config. New `LoginActivityService` records auth attempts. Frontend consolidates into `AdminAreaPage` with sidebar routing to 7 section components. Old admin pages redirect.

**Tech Stack:** Java 21 / Spring Boot 3.3 / JUnit 5 + Mockito + AssertJ / PostgreSQL 16 + Flyway / React 18 + TypeScript + Vitest

**Spec:** `docs/superpowers/specs/2026-03-26-consolidated-admin-area-design.md`

---

## Regression Test Checkpoints

- **After Task 3:** `cd backend && mvn test -pl wealthview-core`
- **After Task 6:** `cd backend && mvn test -pl wealthview-core,wealthview-api`
- **After Task 8:** `cd backend && mvn clean install -DskipITs`
- **After Task 10:** `cd frontend && npm run test -- --run && npm run build`

---

## Task 1: Migrations V048-V051

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V048__add_user_is_active.sql`
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V049__add_invite_code_revoked.sql`
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V050__create_system_config_table.sql`
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V051__create_login_activity_table.sql`

- [ ] **Step 1: Create all 4 migrations**

V048:
```sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_active boolean NOT NULL DEFAULT true;
```

V049:
```sql
ALTER TABLE invite_codes ADD COLUMN IF NOT EXISTS is_revoked boolean NOT NULL DEFAULT false;
```

V050:
```sql
CREATE TABLE IF NOT EXISTS system_config (
    key text PRIMARY KEY,
    value text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

V051:
```sql
CREATE TABLE IF NOT EXISTS login_activity (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_email text NOT NULL,
    tenant_id uuid,
    success boolean NOT NULL,
    ip_address text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_login_activity_created_at ON login_activity (created_at DESC);
```

- [ ] **Step 2: Run backend build to verify migrations**

Run: `cd backend && mvn clean install -DskipITs -DskipTests`

- [ ] **Step 3: Commit**

```
db: add V048-V051 migrations for consolidated admin area

V048: users.is_active for soft deactivation
V049: invite_codes.is_revoked for code revocation
V050: system_config table for DB-backed hot-reload config
V051: login_activity table for auth attempt tracking
```

---

## Task 2: Entities and Repositories

**Files:**
- Modify: `backend/wealthview-persistence/.../entity/UserEntity.java` — add `isActive` field
- Modify: `backend/wealthview-persistence/.../entity/InviteCodeEntity.java` — add `isRevoked` field
- Create: `backend/wealthview-persistence/.../entity/SystemConfigEntity.java`
- Create: `backend/wealthview-persistence/.../entity/LoginActivityEntity.java`
- Create: `backend/wealthview-persistence/.../repository/SystemConfigRepository.java`
- Create: `backend/wealthview-persistence/.../repository/LoginActivityRepository.java`
- Modify: `backend/wealthview-persistence/.../repository/PriceRepository.java` — add browse/delete queries

- [ ] **Step 1: Add `isActive` to UserEntity**

```java
@Column(name = "is_active", nullable = false)
private boolean isActive = true;
// getter + setter
```

- [ ] **Step 2: Add `isRevoked` to InviteCodeEntity**

```java
@Column(name = "is_revoked", nullable = false)
private boolean isRevoked = false;
// getter + setter
```

- [ ] **Step 3: Create SystemConfigEntity**

```java
@Entity @Table(name = "system_config")
public class SystemConfigEntity {
    @Id private String key;
    @Column(nullable = false) private String value;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
    // getters, setters, constructor
}
```

- [ ] **Step 4: Create LoginActivityEntity**

```java
@Entity @Table(name = "login_activity")
public class LoginActivityEntity {
    @Id @GeneratedValue private UUID id;
    @Column(name = "user_email", nullable = false) private String userEmail;
    @Column(name = "tenant_id") private UUID tenantId;
    @Column(nullable = false) private boolean success;
    @Column(name = "ip_address") private String ipAddress;
    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
}
```

- [ ] **Step 5: Create repositories**

```java
// SystemConfigRepository
@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfigEntity, String> {}

// LoginActivityRepository
@Repository
public interface LoginActivityRepository extends JpaRepository<LoginActivityEntity, UUID> {
    List<LoginActivityEntity> findTop50ByOrderByCreatedAtDesc();
    List<LoginActivityEntity> findTop50ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    long countBySuccessTrueAndCreatedAtAfter(OffsetDateTime since);
}
```

- [ ] **Step 6: Add price browse/delete to PriceRepository**

```java
List<PriceEntity> findBySymbolAndDateBetweenOrderByDateDesc(String symbol, LocalDate from, LocalDate to);
void deleteBySymbolAndDate(String symbol, LocalDate date);
```

- [ ] **Step 7: Run tests, commit**

```
feat(persistence): add entities and repositories for admin area
```

---

## Task 3: Backend Services — SystemConfig, LoginActivity, User Admin

**Files:**
- Create: `backend/wealthview-core/.../config/SystemConfigService.java`
- Create: `backend/wealthview-core/.../config/dto/SystemConfigResponse.java`
- Create: `backend/wealthview-core/.../auth/LoginActivityService.java`
- Create: `backend/wealthview-core/.../auth/dto/LoginActivityResponse.java`
- Create: `backend/wealthview-core/.../auth/dto/SystemStatsResponse.java`
- Modify: `backend/wealthview-core/.../auth/AuthService.java` — check isActive, record login
- Modify: `backend/wealthview-core/.../tenant/UserManagementService.java` — password reset, deactivation
- Modify: `backend/wealthview-core/.../tenant/TenantService.java` — invite code revoke, delete used, configurable expiry
- Modify: `backend/wealthview-core/.../price/PriceService.java` — browse, delete single price
- Create tests for all new services

- [ ] **Step 1: Create SystemConfigService**

```java
@Service
public class SystemConfigService {
    private final SystemConfigRepository repo;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public Map<String, String> getAll() { /* load from DB, mask sensitive */ }
    public Optional<String> get(String key) { /* cache-first, fallback to DB */ }
    public void set(String key, String value) { /* update DB + cache */ }
    public void seedDefaults(Map<String, String> defaults) { /* insert if not exists */ }

    private static final Set<String> SENSITIVE_KEYS = Set.of("finnhub.api-key", "jwt.secret");
    private String mask(String value) { /* show first 4 + last 4 chars */ }
}
```

- [ ] **Step 2: Create LoginActivityService**

```java
@Service
public class LoginActivityService {
    public void record(String email, UUID tenantId, boolean success, String ipAddress) { /* save */ }
    public List<LoginActivityResponse> getRecent(int limit) { /* top N */ }
    public List<LoginActivityResponse> getRecentForTenant(UUID tenantId, int limit) { /* tenant-scoped */ }
}
```

- [ ] **Step 3: Create SystemStatsResponse**

```java
public record SystemStatsResponse(
    long totalUsers, long activeUsers, long totalTenants,
    long totalAccounts, long totalHoldings, long totalTransactions,
    String databaseSize, int symbolsTracked, int staleSymbols
) {}
```

- [ ] **Step 4: Modify AuthService — check isActive, record login activity**

In `login()`: after password check, before token generation:
```java
if (!user.isActive()) {
    loginActivityService.record(email, user.getTenant().getId(), false, ipAddress);
    throw new AuthenticationException("Account is disabled");
}
loginActivityService.record(email, user.getTenant().getId(), true, ipAddress);
```

Add `String ipAddress` parameter to `login()`. Controller passes from `HttpServletRequest.getRemoteAddr()`.

- [ ] **Step 5: Modify UserManagementService — password reset, deactivation**

```java
public void resetPassword(UUID tenantId, UUID userId, String newPassword) {
    var user = findUserInTenant(tenantId, userId);
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    userRepository.save(user);
}

public void setUserActive(UUID tenantId, UUID userId, boolean active) {
    var user = findUserInTenant(tenantId, userId);
    user.setActive(active);
    userRepository.save(user);
}
```

- [ ] **Step 6: Modify TenantService — invite code improvements**

```java
public InviteCodeEntity generateInviteCode(UUID tenantId, UUID createdByUserId, int expiryDays) {
    // existing logic but use expiryDays instead of hardcoded 7
}

public void revokeInviteCode(UUID tenantId, UUID codeId) {
    var code = inviteCodeRepository.findById(codeId)
            .orElseThrow(() -> new EntityNotFoundException("Invite code not found"));
    // verify tenant match
    code.setRevoked(true);
    inviteCodeRepository.save(code);
}

public int deleteUsedCodes(UUID tenantId) {
    return inviteCodeRepository.deleteByTenant_IdAndConsumedByIsNotNull(tenantId);
}
```

Check registration: reject if `isRevoked = true`.

- [ ] **Step 7: Modify PriceService — browse, delete**

```java
public List<PriceResponse> browseSymbol(String symbol, LocalDate from, LocalDate to) {
    return priceRepository.findBySymbolAndDateBetweenOrderByDateDesc(symbol, from, to)
            .stream().map(PriceResponse::from).toList();
}

@Transactional
public void deletePrice(String symbol, LocalDate date) {
    priceRepository.deleteBySymbolAndDate(symbol, date);
}
```

- [ ] **Step 8: Write tests for all new/modified services**

- [ ] **Step 9: Run tests, commit**

```
feat(core): add admin services — SystemConfig, LoginActivity, user admin, invite improvements
```

---

## Task 4: Backend Endpoints — SuperAdminController + TenantManagementController

**Files:**
- Modify: `backend/wealthview-api/.../controller/SuperAdminController.java` — add ~8 new endpoints
- Modify: `backend/wealthview-api/.../controller/TenantManagementController.java` — invite code improvements
- Modify: `backend/wealthview-api/.../controller/AuthController.java` — pass IP address
- Modify: `backend/wealthview-api/.../security/SecurityConfig.java` — new endpoint permissions

- [ ] **Step 1: Add to SuperAdminController**

```java
@GetMapping("/system-stats")
public SystemStatsResponse getSystemStats() { ... }

@GetMapping("/login-activity")
public List<LoginActivityResponse> getLoginActivity(@RequestParam(defaultValue = "50") int limit) { ... }

@PutMapping("/users/{userId}/password")
public ResponseEntity<Void> resetPassword(@PathVariable UUID userId, @RequestBody Map<String, String> body) { ... }

@PutMapping("/users/{userId}/active")
public ResponseEntity<Void> setUserActive(@PathVariable UUID userId, @RequestBody Map<String, Boolean> body) { ... }

@GetMapping("/config")
public Map<String, String> getConfig() { ... }

@PutMapping("/config/{key}")
public ResponseEntity<Void> setConfig(@PathVariable String key, @RequestBody Map<String, String> body) { ... }

@GetMapping("/prices/{symbol}")
public List<PriceResponse> browseSymbolPrices(@PathVariable String symbol,
        @RequestParam LocalDate from, @RequestParam LocalDate to) { ... }

@DeleteMapping("/prices/{symbol}/{date}")
public ResponseEntity<Void> deletePrice(@PathVariable String symbol, @PathVariable LocalDate date) { ... }
```

- [ ] **Step 2: Add to TenantManagementController**

```java
@PutMapping("/invite-codes/{id}/revoke")
public ResponseEntity<Void> revokeInviteCode(@PathVariable UUID id, @AuthenticationPrincipal TenantUserPrincipal p) { ... }

@DeleteMapping("/invite-codes/used")
public Map<String, Integer> deleteUsedCodes(@AuthenticationPrincipal TenantUserPrincipal p) { ... }
```

Update `POST /invite-codes` to accept optional `expiryDays` parameter.

- [ ] **Step 3: Modify AuthController — pass IP**

Add `HttpServletRequest request` parameter to login endpoint. Pass `request.getRemoteAddr()` to `authService.login()`.

- [ ] **Step 4: Update SecurityConfig for new endpoints**

```java
.requestMatchers(HttpMethod.PUT, "/api/v1/tenant/invite-codes/*/revoke").hasAnyRole("ADMIN", "SUPER_ADMIN")
.requestMatchers(HttpMethod.DELETE, "/api/v1/tenant/invite-codes/used").hasAnyRole("ADMIN", "SUPER_ADMIN")
```

- [ ] **Step 5: Write controller tests, run full build**

- [ ] **Step 6: Commit**

```
feat(api): add admin endpoints — stats, login activity, user admin, config, invite codes, price browse
```

---

## Task 5: SystemConfig Hot-Reload Wiring

**Files:**
- Modify: `backend/wealthview-core/.../pricefeed/PriceSyncService.java` — read API key from SystemConfig
- Create: `backend/wealthview-app/.../config/SystemConfigInitializer.java` — seed defaults on startup

- [ ] **Step 1: Create SystemConfigInitializer**

`@Component` that runs on startup, reads `@Value` annotations, seeds SystemConfigService with defaults if keys don't exist.

- [ ] **Step 2: Wire PriceSyncService to read from SystemConfigService**

Replace `@Value("${app.finnhub.api-key}")` with `systemConfigService.get("finnhub.api-key")`. The Finnhub API key can be updated at runtime without restart.

- [ ] **Step 3: Run tests, commit**

```
feat(core): wire SystemConfig hot-reload for Finnhub API key
```

---

## Task 6: Frontend — Admin Area Shell + Dashboard

**Files:**
- Create: `frontend/src/pages/AdminAreaPage.tsx` — sidebar + content routing
- Create: `frontend/src/components/admin/DashboardSection.tsx` — stats cards + login activity
- Create: `frontend/src/api/adminSystem.ts` — system stats + login activity + config API
- Modify: `frontend/src/components/Layout.tsx` — consolidate nav items
- Modify: `frontend/src/App.tsx` — new route, redirects

- [ ] **Step 1: Create API client**

```typescript
export async function getSystemStats(): Promise<SystemStats> { ... }
export async function getLoginActivity(limit?: number): Promise<LoginActivity[]> { ... }
export async function syncFinnhub(): Promise<void> { ... }
export async function syncYahoo(): Promise<YahooSyncResult> { ... }
```

- [ ] **Step 2: Create AdminAreaPage with sidebar**

Sidebar with 7 sections. Content area renders active section. Role-based section visibility.

- [ ] **Step 3: Create DashboardSection**

3 rows of stat cards + login activity table + Finnhub/Yahoo sync buttons.

- [ ] **Step 4: Update Layout.tsx**

Replace Settings, Audit Log, Admin, Price Admin nav items with single "Admin" item visible to admin+.

- [ ] **Step 5: Update App.tsx**

Add `/admin/*` route. Redirect old paths (`/settings`, `/audit-log`, `/admin/prices`) to `/admin`.

- [ ] **Step 6: Build, test, commit**

```
feat(frontend): admin area shell with dashboard section
```

---

## Task 7: Frontend — Users + Invite Codes Sections

**Files:**
- Create: `frontend/src/components/admin/UsersSection.tsx`
- Create: `frontend/src/components/admin/InviteCodesSection.tsx`
- Create: `frontend/src/api/adminUsers.ts` — user admin API

- [ ] **Step 1: Create user admin API client**

```typescript
export async function getAllUsers(): Promise<AdminUser[]> { ... }
export async function resetPassword(userId: string, newPassword: string): Promise<void> { ... }
export async function setUserActive(userId: string, active: boolean): Promise<void> { ... }
```

- [ ] **Step 2: Create UsersSection**

User table with role dropdown, reset password modal, activate/deactivate toggle, delete with confirmation.

- [ ] **Step 3: Create InviteCodesSection**

Generate form with expiry dropdown. Code table with status, revoke button, copy button. "Delete Used" button with confirmation.

- [ ] **Step 4: Build, test, commit**

```
feat(frontend): admin users and invite codes sections
```

---

## Task 8: Frontend — System Config + Price Browser + Audit Log

**Files:**
- Create: `frontend/src/components/admin/SystemConfigSection.tsx`
- Create: `frontend/src/components/admin/PriceBrowserTab.tsx`
- Modify: `frontend/src/components/admin/` — integrate existing AdminPriceManagementPage tabs
- Move: existing AuditLogPage content into admin section

- [ ] **Step 1: Create SystemConfigSection**

Card sections for API Keys, App Settings, Price Sync. Masked display for sensitive keys, edit reveals input. Save button per section.

- [ ] **Step 2: Create PriceBrowserTab**

Symbol selector, date range, price table with delete, simple line chart.

- [ ] **Step 3: Integrate prices tabs**

Move existing Finnhub/Yahoo/CSV tabs + new Browse tab into the Prices section.

- [ ] **Step 4: Move audit log**

Extract existing AuditLogPage content into an AuditLogSection component. Render inside admin area.

- [ ] **Step 5: Build, test, commit**

```
feat(frontend): admin system config, price browser, and audit log sections
```

---

## Task 9: Cleanup — Redirects + Remove Old Pages

**Files:**
- Modify: `frontend/src/App.tsx` — redirect old routes
- Modify: `frontend/src/components/Layout.tsx` — final nav cleanup

- [ ] **Step 1: Add redirects for old routes**

```tsx
<Route path="/settings" element={<Navigate to="/admin/users" replace />} />
<Route path="/audit-log" element={<Navigate to="/admin/audit" replace />} />
<Route path="/admin/prices" element={<Navigate to="/admin/prices" replace />} />
```

Keep old page files for now (can be deleted later after verifying nothing links to them).

- [ ] **Step 2: Final nav item cleanup**

Ensure single "Admin" entry in nav for admin+ roles.

- [ ] **Step 3: Full build, all tests, commit**

```
refactor(frontend): redirect old admin pages to consolidated admin area
```

---

## Task 10: End-to-End Manual Testing

- [ ] **Step 1: Rebuild and deploy**

```bash
DOCKER_BUILDKIT=0 docker compose build --no-cache app
docker compose down && docker compose up -d
```

- [ ] **Step 2: Test as super_admin (admin@wealthview.local)**

1. Navigate to Admin → Dashboard: verify stats, login activity, sync buttons
2. Admin → Users: see all users across tenants, reset password, deactivate/reactivate
3. Admin → Tenants: create tenant, toggle active
4. Admin → Prices: Finnhub/Yahoo/CSV tabs + Browse tab with chart
5. Admin → Invite Codes: generate with custom expiry, revoke, delete used
6. Admin → System Config: view/edit API keys (masked), change settings
7. Admin → Audit Log: filtered, paginated
8. Verify old URLs redirect (/settings → /admin, /audit-log → /admin/audit)

- [ ] **Step 3: Test as tenant admin (demo@wealthview.local)**

1. Verify admin sees: Users (own tenant), Invite Codes, Audit Log
2. Verify admin does NOT see: Tenants, System Config, Price Admin
3. Verify password reset works for own tenant users only

- [ ] **Step 4: Test deactivation flow**

1. Deactivate a user
2. Try to login as that user — expect "Account disabled"
3. Reactivate, login succeeds

- [ ] **Step 5: Test system config hot-reload**

1. Change Finnhub API key via System Config
2. Trigger price sync — should use new key immediately (no restart)
