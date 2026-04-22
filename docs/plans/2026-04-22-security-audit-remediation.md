# Security Audit Remediation Plan — 2026-04-22

Findings come from the parallel-agent audit run after Items 1-3 of the prior audit were shipped (`506c58c`, `3592897`, `b8fd672`, `8fdd847`). This plan covers **only the confirmed findings**; false positives were already discarded during the audit.

Each task is independently executable. Check the box when done, commit, and the next context can resume cleanly by re-reading this file.

---

## Phase 1 — P1 (pre-production blockers)

Ship as four separate conventional commits on `main`. TDD throughout (the iron law). Do **not** push until the user asks.

### [x] 1.1 Registration email-enumeration leak
- **Where**: `wealthview-core/src/main/java/com/wealthview/core/auth/AuthService.java:126-144`
- **Problem**: `existsByEmail` short-circuits before invite-code validation — distinct exception class + distinct response time lets an attacker enumerate registered emails by spraying invite codes.
- **Fix**: Validate the invite code FIRST (same lookup + state checks as today), then check email uniqueness. Keep messages distinct to the operator via logs, but collapse the client-facing contract so timing + status code don't differentiate "email known" from "invite invalid."
  - Option A (simplest): always look up invite first; if invalid → `InvalidInviteCodeException` (same as today); if valid, then check email; if duplicate → still return `DuplicateEntityException` but only *after* the invite lookup has completed, so the BCrypt-equivalent time budget is spent regardless. Accept the residual info-leak (duplicate response on valid invite is already gated behind having a valid invite, which is admin-issued).
  - Option B (stronger): respond with a generic 202-accepted "if your invite is valid, a confirmation will follow" and defer the actual duplicate/register decision. Significant UX shift — flag for user decision before adopting.
- **Tests**: new `AuthServiceTest`
  - `register_invalidInviteCode_throwsBeforeEmailCheck` — assert `userRepository.existsByEmail` is never invoked when invite is bad.
  - `register_validInviteDuplicateEmail_stillThrowsDuplicate` — behavior preserved for the happy path.
- **Commit**: `fix(auth): validate invite code before email uniqueness to close enumeration channel`

### [x] 1.2 CORS production default is empty
- **Where**: `wealthview-app/src/main/resources/application-prod.yml:3` (`${CORS_ORIGIN:}`) and `wealthview-app/src/main/java/com/wealthview/app/config/ProductionConfigValidator.java`
- **Problem**: Operator who forgets to set `CORS_ORIGIN` in prod ships with empty allowed-origins — fragile and silently misconfigurable.
- **Fix**: Remove the `:` default from `application-prod.yml` (so absence fails fast) AND add a validator check: on `ApplicationReadyEvent` under prod/docker profile, assert `app.cors.allowed-origins` is non-empty and each entry is an `https://...` URL (except for explicitly allowed localhost in docker profile).
- **Tests**: extend `ProductionConfigValidatorTest` with `prodProfile_emptyCorsOrigin_fails` and `prodProfile_nonHttpsOrigin_fails`.
- **Commit**: `fix(app): require non-empty https CORS origin in production profile`

### [x] 1.3 Finnhub API key in query string
- **Where**: `wealthview-import/src/main/java/com/wealthview/importmodule/finnhub/FinnhubClient.java:40,72`
- **Problem**: `?token=...` query param gets into access logs / proxies / error reports.
- **Fix**: Drop `token` from the URI template; configure `FinnhubConfig` to inject an `X-Finnhub-Token` header on every request via a `ClientHttpRequestInterceptor` (or move the header into each `FinnhubClient` call).
- **Tests**: `FinnhubClientTest` — verify header is set, query param is absent. Use `MockRestServiceServer`.
- **Commit**: `fix(import): transmit Finnhub API token via header instead of query string`

### [x] 1.4 External HTTP clients missing timeouts
- **Where**: `wealthview-app/src/main/java/com/wealthview/app/config/FinnhubConfig.java`, `YahooConfig.java`, any other `RestClient.builder()` under `wealthview-app/config/`.
- **Problem**: No connect/read timeout → a hung upstream pins a servlet thread indefinitely.
- **Fix**: Configure a shared `ClientHttpRequestFactory` (Apache HttpClient5 or JDK with timeouts) and attach it to each `RestClient.builder().requestFactory(...)`. Suggested: connect 5s, read 15s (tune per upstream SLA).
- **Tests**: add a test that builds the RestClient through the config and inspects the request factory timeouts — or, more practically, a smoke test with a delay'd local test server verifying a `ResourceAccessException` fires within the configured budget.
- **Commit**: `fix(app): apply connect and read timeouts to external HTTP clients`

### Phase 1 exit criteria
- All four commits on `main`; push on user request.
- `mvn clean verify` green (unit + IT).
- Context can be cleared before Phase 2.

---

## Phase 2 — P2 (defense-in-depth)

Six independent tasks. Group as individual commits.

### [x] 2.1 Dockerfile non-root + HEALTHCHECK
- **Where**: `/home/jakefear/source/wealthview/Dockerfile`
- **Fix**: Add a non-root user (`addgroup -S wv && adduser -S wv -G wv`) and `USER wv`; add `HEALTHCHECK --interval=30s --timeout=3s CMD wget -q -O- http://localhost:8080/actuator/health || exit 1`. Ensure the user has read access to the app jar and any writable dir Spring needs.
- **Tests**: manual — `docker compose up --build -d && docker compose exec app id` should not return uid=0; `docker inspect` shows Health status transitioning to healthy.
- **Commit**: `chore(docker): run as non-root user and add healthcheck`

### [x] 2.2 Composite index on `login_activity(tenant_id, created_at)`
- **Where**: new Flyway migration `V056__index_login_activity_tenant.sql` under `wealthview-persistence/src/main/resources/db/migration/` (V055 already taken).
- **Fix**: `CREATE INDEX IF NOT EXISTS idx_login_activity_tenant_created_at ON login_activity (tenant_id, created_at DESC);`
- **Tests**: migration runs cleanly in the Testcontainers IT suite (existing AbstractApiIntegrationTest will exercise it).
- **Commit**: `db(persistence): add composite index for tenant-scoped login activity queries`

### [ ] 2.3 Optimistic locking on `UserEntity.tokenGeneration`
- **Where**: `wealthview-persistence/src/main/java/com/wealthview/persistence/entity/UserEntity.java`; migration to add `version bigint NOT NULL DEFAULT 0`.
- **Fix**: Add `@Version private long version;` field. Add migration `V056__add_version_to_users.sql`. Confirm no service code assumes the field is absent.
- **Tests**: `UserRepositoryIT` — concurrent refresh simulation (two threads each bump generation) — one wins, the other surfaces `OptimisticLockingFailureException`. Wrap in `AuthService.refresh` to translate into `BadCredentialsException`.
- **Commit**: `fix(auth): add optimistic locking to user token generation to resolve refresh races`

### [ ] 2.4 Audit events for super-admin mutations
- **Where**: `wealthview-core/src/main/java/com/wealthview/core/tenant/UserManagementService.java`
- **Fix**: Inject `ApplicationEventPublisher`; publish `AuditEvent` on `updateUserRole`, `deleteUser`, `setUserActive*`, `resetPassword*`. Include tenant, actor, target user, action name, relevant before/after fields. Make sure the audit entity's details Map is small and well-typed (see 2.5).
- **Tests**: extend `UserManagementServiceTest` to verify each mutation publishes exactly one event with the expected payload.
- **Commit**: `feat(core): emit audit events for user management mutations`

### [ ] 2.5 Cap audit `details` JSON depth and size
- **Where**: wherever `AuditLogEntity.details` is persisted — likely `AuditEventListener` or a dedicated service.
- **Fix**: Before persistence, reject or truncate details whose serialized JSON exceeds a threshold (suggest 8 KB) or whose map depth exceeds 3 levels. Write as utility method `AuditDetailsValidator.validate(Map<String,Object>)`.
- **Tests**: unit tests covering oversize, overdeep, and the happy path.
- **Commit**: `fix(core): bound audit event details to prevent storage-amplification payloads`

### [ ] 2.6 `Permissions-Policy` header
- **Where**: `wealthview-api/src/main/java/com/wealthview/api/security/SecurityConfig.java` header block (`~line 80`).
- **Fix**: Add `.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "geolocation=(), microphone=(), camera=(), payment=()"))` (or equivalent Spring Security 6 fluent call).
- **Tests**: extend an existing `SecurityConfigIntegrationTest` (or add one) to GET a public endpoint and assert the header value.
- **Commit**: `fix(api): set Permissions-Policy header to disable unused browser features`

### Phase 2 exit criteria
- All six commits on `main`; push on user request.
- `mvn clean verify` green.
- Update `memory/project_security_audit_deferred.md` to remove items now addressed (Permissions-Policy, non-root Docker, HEALTHCHECK were previously deferred).

---

## Explicitly deferred (not in this plan)

- **httpOnly cookie migration for tokens** — multi-day refactor, separate project.
- **Redis-backed rate limiter + login-attempt store** — multi-instance concern, not yet justified.
- **HSTS preload submission** — wait until prod domain is stable.
- **SRI rule for third-party assets** — no CDN assets currently, enshrine only if one is added.
- **Backup encryption** — deployment concern.
- **Docker `:latest` pinning** — deployment concern.
- **`.env` gitignore verification** — deployment housekeeping.

These live in `memory/project_security_audit_deferred.md` and are intentionally postponed until production rollout prep.

---

## Progress tracking rules

- Only check a box once the commit is on `main` AND `mvn clean verify` is green.
- Update this file in the same commit as the fix (the checkbox tick lives with the code change).
- Between tasks, the context can be cleared freely — this file plus `git log` is sufficient to resume.
