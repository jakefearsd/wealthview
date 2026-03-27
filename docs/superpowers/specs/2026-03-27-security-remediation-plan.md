# Security Remediation Plan

## Context

A security review identified 1 critical, 4 high, and 7 medium severity issues. This plan addresses all of them in dependency order, grouped into phases that can each be committed and verified independently. Each phase includes specific tests to ensure no regressions in existing functionality.

---

## Phase 1: JWT Token Type Validation (CRITICAL + MEDIUM)

Fixes: refresh token usable as access token, refresh doesn't revalidate user state.

### Changes

**`JwtTokenProvider.java`** — add `type` claim to access tokens and validation methods:
- Add `"type", "access"` claim to `generateAccessToken()` (refresh tokens already have `"type", "refresh"`)
- Add `extractTokenType(String token)` method returning the `type` claim value
- Add `validateAccessToken(String token)` that calls `validateToken()` AND checks `type != "refresh"`
- Add `validateRefreshToken(String token)` that calls `validateToken()` AND checks `type == "refresh"`

**`JwtAuthenticationFilter.java`** — reject refresh tokens:
- Change `jwtTokenProvider.validateToken(token)` to `jwtTokenProvider.validateAccessToken(token)`
- If a refresh token is presented as a Bearer token, the filter ignores it (no auth set, request continues unauthenticated)

**`AuthService.refresh()`** — validate token type and recheck user state:
- Change `validateToken(refreshToken)` to `validateRefreshToken(refreshToken)`
- After finding the user, verify `user.isActive()` and `user.getTenant().isActive()` before issuing new tokens

### Tests

**`JwtTokenProviderTest`** — new tests:
- `validateAccessToken_withAccessToken_returnsTrue`
- `validateAccessToken_withRefreshToken_returnsFalse`
- `validateRefreshToken_withRefreshToken_returnsTrue`
- `validateRefreshToken_withAccessToken_returnsFalse`
- `extractTokenType_accessToken_returnsAccess`
- `extractTokenType_refreshToken_returnsRefresh`

**`AuthServiceTest`** — new tests:
- `refresh_withAccessTokenInsteadOfRefresh_throwsBadCredentials`
- `refresh_withDisabledUser_throwsBadCredentials`
- `refresh_withDisabledTenant_throwsBadCredentials`

**`AuthControllerIT`** — new integration tests:
- `refresh_withValidRefreshToken_returns200` (happy path — currently untested)
- `refresh_withAccessTokenAsRefresh_returns401`
- `apiCall_withRefreshTokenAsBearer_returns401` (verify filter rejects refresh tokens)

### Regression check
- Run all existing `AuthControllerIT` tests — login and registration must still work
- Run all controller ITs — JWT authentication must still function for normal API calls

---

## Phase 2: SecurityConfig Tightening (HIGH)

Fixes: overly permissive `GET /**` rule.

### Changes

**`SecurityConfig.java`** — replace the broad `GET /**` rule:
```java
// BEFORE (line 60):
.requestMatchers(HttpMethod.GET, "/**").permitAll()

// AFTER — explicitly permit only static SPA assets:
.requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
```

### Tests

**`AuthControllerIT`** — new test:
- `getUnknownPath_unauthenticated_returns401` — verify `GET /api/v1/some-nonexistent` returns 401, not 200
- `getStaticAssetPath_unauthenticated_returns200orNotFound` — verify `/`, `/index.html` still accessible

### Regression check
- Run full `AuthControllerIT` suite
- Run `PrometheusEndpointIT` — actuator endpoints must still work
- Manual: `docker compose up --build` and verify the SPA loads at `http://localhost` — this is the most important regression test since the rule serves the frontend

---

## Phase 3: Role Validation (HIGH)

Fixes: `updateUserRole()` accepts arbitrary role strings.

### Changes

**`UserManagementService.java`** — validate role against whitelist:
```java
private static final Set<String> VALID_ROLES = Set.of("member", "admin");

public UserEntity updateUserRole(UUID tenantId, UUID userId, String newRole) {
    if (!VALID_ROLES.contains(newRole)) {
        throw new IllegalArgumentException("Invalid role: " + newRole);
    }
    // ... existing logic
}
```

Also add a guard preventing role change on super admin users:
```java
if (user.isSuperAdmin()) {
    throw new IllegalStateException("Cannot modify super admin role");
}
```

### Tests

**`UserManagementServiceTest`** — new tests:
- `updateUserRole_validRole_updatesSuccessfully` (member, admin)
- `updateUserRole_invalidRole_throwsIllegalArgument` ("super_admin", "root", "", etc.)
- `updateUserRole_superAdminUser_throwsIllegalState`

**`UserControllerIT`** — new test:
- `updateRole_toInvalidRole_returns400`

### Regression check
- Run existing `UserControllerIT` tests — role update happy path must still work
- Run `TenantIsolationIT` — cross-tenant access must still be blocked

---

## Phase 4: File Upload Limits (HIGH)

Fixes: no file size limits on imports.

### Changes

**`application.yml`** — add multipart limits:
```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
```

**`ImportController.java`** — add content type validation:
```java
private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
    "text/csv", "text/plain", "application/octet-stream",
    "application/vnd.ms-excel", "application/xml", "text/xml"
);

// In each import method, before processing:
if (file.getContentType() != null && !ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
    throw new IllegalArgumentException("Unsupported file type: " + file.getContentType());
}
```

**`GlobalExceptionHandler.java`** — add handler for `MaxUploadSizeExceededException`:
```java
@ExceptionHandler(MaxUploadSizeExceededException.class)
public ResponseEntity<ErrorResponse> handleMaxUpload(MaxUploadSizeExceededException ex, HttpServletRequest request) {
    recordError(ex, HttpStatus.PAYLOAD_TOO_LARGE);
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(new ErrorResponse("PAYLOAD_TOO_LARGE", "File size exceeds the 10MB limit", 413));
}
```

### Tests

**`ImportControllerTest`** (unit) — new tests:
- `importCsv_unsupportedContentType_returns400`

**`ImportControllerIT`** — new test:
- `importCsv_validFile_returns200` (verify existing happy path still works with limits)

**`GlobalExceptionHandlerTest`** — new test:
- `handleMaxUpload_returns413`

### Regression check
- Run existing `ImportControllerIT` and `ImportPositionsControllerIT` — all imports must still work
- Existing test CSV files are small (<1KB), so the 10MB limit won't affect them

---

## Phase 5: Password Strength (MEDIUM)

Fixes: weak password requirements.

### Changes

**`RegisterRequest.java`** — strengthen password validation:
```java
@NotBlank
@Size(min = 12, max = 128, message = "Password must be between 12 and 128 characters")
String password
```

Add a custom `@StrongPassword` validator (new class in `com.wealthview.core.auth.validation`):
- At least one uppercase letter
- At least one lowercase letter
- At least one digit
- Minimum 12 characters

**`SuperAdminController`** password reset — apply same validation to the password reset DTO. Create a `PasswordResetRequest` record with the same validation instead of accepting raw `Map<String, String>`.

### Tests

**`RegisterRequestValidationTest`** — new test class:
- `password_tooShort_fails`
- `password_noUppercase_fails`
- `password_noLowercase_fails`
- `password_noDigit_fails`
- `password_valid_passes`

**`AuthControllerIT`** — new test:
- `register_weakPassword_returns400`

### Regression check
- Update existing test fixtures that use `"password123"` (6 chars) to use `"Password123!"` (meets new requirements)
- Run all `AuthControllerIT` tests
- Update `AuthHelper` and any dev/sample data initializers that create users with weak passwords

---

## Phase 6: Account Lockout (MEDIUM)

Fixes: no brute force protection beyond IP rate limiting.

### Changes

**`LoginAttemptService`** — new service in `com.wealthview.core.auth`:
- Tracks failed login attempts per email using an in-memory `ConcurrentHashMap<String, AttemptWindow>`
- After 5 failed attempts within 15 minutes, block further login attempts for that email for 15 minutes
- On successful login, reset the counter
- Method: `boolean isBlocked(String email)`, `void recordFailure(String email)`, `void recordSuccess(String email)`

**`AuthService.login()`** — integrate lockout check:
```java
if (loginAttemptService.isBlocked(request.email())) {
    meterRegistry.counter("wealthview.auth.login", "result", "failure", "reason", "account_locked").increment();
    throw new BadCredentialsException("Account temporarily locked due to too many failed attempts");
}
// ... existing logic
// On failure paths: loginAttemptService.recordFailure(request.email());
// On success: loginAttemptService.recordSuccess(request.email());
```

### Tests

**`LoginAttemptServiceTest`** — new test class:
- `isBlocked_noAttempts_returnsFalse`
- `isBlocked_underThreshold_returnsFalse`
- `isBlocked_atThreshold_returnsTrue`
- `recordSuccess_resetsCounter`
- `isBlocked_afterWindowExpires_returnsFalse`

**`AuthServiceTest`** — new tests:
- `login_accountLocked_throwsBadCredentials`
- `login_failedAttemptIncrements_locksAfterThreshold`

**`AuthControllerIT`** — new test:
- `login_repeatedFailures_returns401WithLockMessage`

### Regression check
- All existing login tests must pass (they won't hit the lockout threshold)
- Verify the lockout doesn't interfere with legitimate login after a single typo

---

## Phase 7: Invite Code Hardening (MEDIUM)

Fixes: invite code enumeration via different error messages.

### Changes

**`AuthService.register()`** — unify error messages:
```java
// BEFORE: 4 different messages for invalid/consumed/revoked/expired
// AFTER: single generic message for all failure cases

var inviteCode = inviteCodeRepository.findByCode(request.inviteCode())
        .orElseThrow(() -> {
            log.warn("Registration failed: invalid invite code");
            meterRegistry.counter("wealthview.auth.registration", "result", "failure", "reason", "invalid_invite").increment();
            return new InvalidInviteCodeException("Invalid or expired invite code");
        });

if (inviteCode.isConsumed() || inviteCode.isRevoked() || inviteCode.isExpired()) {
    log.warn("Registration failed: invite code unusable (consumed={}, revoked={}, expired={})",
            inviteCode.isConsumed(), inviteCode.isRevoked(), inviteCode.isExpired());
    meterRegistry.counter("wealthview.auth.registration", "result", "failure", "reason", "invalid_invite").increment();
    throw new InvalidInviteCodeException("Invalid or expired invite code");
}
```

### Tests

**`AuthServiceTest`** — update existing tests:
- All invite code failure tests should now assert the same message: `"Invalid or expired invite code"`

**`AuthControllerIT`** — update existing test:
- `register_withExpiredInviteCode_returns400` — verify message is generic

### Regression check
- All existing registration tests must still pass (just with updated expected messages)

---

## Phase 8: Monetary Value Validation (MEDIUM)

Fixes: missing `@DecimalMin`/`@DecimalMax` on financial DTOs.

### Changes

**`TransactionRequest.java`**:
```java
@NotNull @DecimalMin("0") BigDecimal quantity,
@NotNull BigDecimal amount  // amount can be negative for sells
```

**`PropertyRequest.java`** — add to all BigDecimal fields:
```java
@NotNull @DecimalMin("0") BigDecimal purchasePrice,
@NotNull @DecimalMin("0") BigDecimal currentValue,
// etc. for mortgageBalance, loanAmount, interestRate, annualAppreciationRate, etc.
```

**`CreateIncomeSourceRequest.java`** — add validation annotations:
```java
@NotBlank String name,
@NotBlank String incomeType,
@NotNull @Min(0) Integer startAge,
@NotNull @Min(0) Integer endAge,
@NotNull @DecimalMin("0") BigDecimal annualAmount,
@DecimalMin("0") @DecimalMax("1") BigDecimal inflationRate,
String taxTreatment
```

**`UpdateIncomeSourceRequest.java`** — mirror the same validations.

### Tests

**Unit tests** for each DTO — validate that negative values are rejected:
- `TransactionRequestValidationTest` — negative quantity rejected
- `PropertyRequestValidationTest` — negative purchasePrice rejected
- `CreateIncomeSourceRequestValidationTest` — negative annualAmount rejected

### Regression check
- Run all existing controller tests — they use valid positive values so they should pass
- Run `ImportControllerIT`, `PropertyControllerIT`, `AccountControllerIT` — no regressions
- Check if any test fixtures use values that would now fail validation

---

## Phase 9: Hardcoded Credentials Cleanup (MEDIUM)

Fixes: dev credentials in source control.

### Changes

**`application-dev.yml`**:
```yaml
app:
  super-admin:
    password: ${SUPER_ADMIN_PASSWORD:DevPass123!}  # env var with non-trivial default
```

**`application-docker.yml`**:
```yaml
app:
  super-admin:
    password: ${SUPER_ADMIN_PASSWORD}  # no default — must be provided
```

**`application-prod.yml`** — add fail-fast validation. Create a `ProductionConfigValidator` that runs on `prod` profile and throws if `JWT_SECRET` equals the default or if `SUPER_ADMIN_PASSWORD` is not set.

**`.env`** — remove from git, add to `.gitignore`. Create `.env.example` with placeholder values only (this file already exists but `.env` itself is tracked).

### Tests

**`ProductionConfigValidatorTest`** — new test:
- `validate_missingJwtSecret_throwsOnProdProfile`
- `validate_defaultJwtSecret_throwsOnProdProfile`
- `validate_validConfig_passes`

### Regression check
- Run all IT tests (they use `it` profile, not `prod`)
- `docker compose up --build` must still work (docker profile uses env vars)
- Dev profile must still work with defaults

---

## Phase 10: Scheduled Task Tenant Scoping (MEDIUM)

Fixes: `PropertyValuationSyncService.syncAll()` processes all tenants without isolation.

### Changes

**`PropertyValuationSyncService.syncAll()`** — process per-tenant:
```java
public void syncAll() {
    var tenantIds = propertyRepository.findDistinctTenantIds();
    for (var tenantId : tenantIds) {
        syncForTenant(tenantId);
    }
}

private void syncForTenant(UUID tenantId) {
    var properties = propertyRepository.findByTenant_Id(tenantId);
    // ... existing per-property loop
}
```

**`PropertyRepository`** — add `findDistinctTenantIds()` method if not present, or use existing `findByTenant_Id()`.

Note: `PriceSyncService.findDistinctSymbols()` is intentionally cross-tenant — prices are shared reference data. Document this decision with a comment.

### Tests

**`PropertyValuationSyncServiceTest`** — update `syncAll` tests to verify per-tenant processing.

### Regression check
- Run existing `PropertyValuationSyncServiceTest` — all tests must pass
- Run `PropertyControllerIT` — property CRUD must work

---

## Phase 11: Refresh Token Rotation (MEDIUM)

Fixes: old refresh tokens remain valid after use.

### Changes

This is the largest change. Two approaches:

**Approach A (Simpler — token family tracking):**
- Add `refresh_tokens` table: `id`, `user_id`, `token_hash`, `family_id`, `revoked`, `expires_at`, `created_at`
- On login: create a new refresh token family, store hashed token
- On refresh: verify token exists and is not revoked, issue new token in same family, revoke old token
- On reuse of a revoked token: revoke ALL tokens in the family (compromise detection)

**Approach B (Simplest — generation counter on user):**
- Add `token_generation` integer column to `users` table
- Include `generation` claim in refresh tokens
- On refresh: verify generation matches user's current generation, then increment generation
- On logout: increment generation (invalidates all existing refresh tokens)
- Old refresh tokens become invalid because their generation claim no longer matches

**Recommendation:** Approach B. It's a single column addition, no new table, and provides logout functionality for free.

### Changes (Approach B)

**Migration `V047__add_token_generation_to_users.sql`**:
```sql
ALTER TABLE users ADD COLUMN token_generation integer NOT NULL DEFAULT 0;
```

**`UserEntity`** — add `tokenGeneration` field.

**`JwtTokenProvider`** — include `generation` claim in refresh tokens.

**`AuthService.refresh()`** — verify `generation` claim matches `user.getTokenGeneration()`, then increment.

**`AuthController`** — add `POST /api/v1/auth/logout` that increments `tokenGeneration`.

### Tests

**`AuthServiceTest`** — new tests:
- `refresh_validGeneration_issuesNewTokensAndIncrements`
- `refresh_staleGeneration_throwsBadCredentials`
- `logout_incrementsGeneration`

**`AuthControllerIT`** — new tests:
- `logout_invalidatesPreviousRefreshToken`
- `refresh_afterLogout_returns401`

### Regression check
- All existing auth tests must pass
- Login flow must still work end-to-end

---

## Verification Strategy

### After each phase:

1. `mvn clean test -pl wealthview-core,wealthview-api,wealthview-app` — all unit tests pass
2. `mvn verify -pl wealthview-app` — all integration tests pass

### After all phases complete:

3. `docker compose up --build -d` — app starts successfully
4. Manual smoke test:
   - Load `http://localhost` — SPA renders (Phase 2 regression)
   - Login with `demo@wealthview.local` / `demo123` — (update password if Phase 5 requires longer passwords)
   - Navigate dashboard, accounts, projections — no auth failures
   - Import a CSV file — works within size limits
   - `curl http://localhost/actuator/prometheus` — metrics endpoint still accessible
   - `curl http://localhost/actuator/health` — health check returns 200
5. Run the full IT suite one final time: `mvn verify -pl wealthview-app`

### Security verification:

6. `curl http://localhost/api/v1/accounts` (no auth) — returns 401 (Phase 2)
7. Attempt login with wrong password 6 times — verify lockout (Phase 6)
8. Use a refresh token as Bearer token — returns 401 (Phase 1)
9. Register with password `"123456"` — returns 400 (Phase 5)
10. Try `PUT /api/v1/tenant/users/{id}/role` with body `"super_admin"` — returns 400 (Phase 3)

---

## Implementation Order Rationale

Phases are ordered by:
1. **Severity** — Critical/High before Medium
2. **Dependency** — JWT token type validation (Phase 1) before refresh token rotation (Phase 11)
3. **Risk of regression** — SecurityConfig change (Phase 2) is high-risk for breaking the SPA, so it comes early with thorough manual verification
4. **Independence** — Most phases are independent and could be reordered, but the order above minimizes merge conflicts

Estimated scope: ~15-20 files modified, ~5 new files, 1 migration, ~50 new tests.
