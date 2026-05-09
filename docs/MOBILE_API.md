# Mobile API Contract

This document is the contract between the WealthView backend and any native
mobile client. The web frontend uses cookie-based auth; native clients use
the parallel token-in-body endpoints described here.

The cookie endpoints (`/api/v1/auth/login`, `/api/v1/auth/refresh`,
`/api/v1/auth/logout`) are unchanged and remain the recommended path for
browser clients. Native apps SHOULD use the `/api/v1/auth/token/**`
endpoints below.

## Auth endpoints

| Method | Path                          | Auth          | Request body                               | Response body |
|--------|-------------------------------|---------------|--------------------------------------------|---------------|
| POST   | `/api/v1/auth/token/login`    | none          | `{email, password}`                        | `MobileAuthResponse` (200) |
| POST   | `/api/v1/auth/token/register` | none          | `{email, password, invite_code}`           | `MobileAuthResponse` (201) |
| POST   | `/api/v1/auth/token/refresh`  | none          | `{refresh_token}`                          | `MobileAuthResponse` (200) |
| POST   | `/api/v1/auth/token/logout`   | Bearer        | (empty)                                    | (empty, 204) |
| GET    | `/api/v1/auth/me`             | Bearer or Cookie | (none)                                  | `CurrentUserResponse` (200) |

`MobileAuthResponse` is JSON with `snake_case` field names (Jackson global
strategy):

```json
{
  "access_token": "<jwt>",
  "refresh_token": "<jwt>",
  "user_id": "5b4d…",
  "tenant_id": "8a01…",
  "email": "user@example.com",
  "role": "member"
}
```

`CurrentUserResponse`:

```json
{
  "user_id": "5b4d…",
  "tenant_id": "8a01…",
  "email": "user@example.com",
  "role": "member"
}
```

`GET /api/v1/auth/me` works for both transports. It is the recommended
"am I logged in?" check on app launch — it round-trips the token through
the same `JwtAuthenticationFilter` the rest of the API uses, so a 200
means the access token is valid right now (signature, expiry, and the
server-side `token_generation`).

## Authenticated requests

Send the access token in the `Authorization` header:

```
Authorization: Bearer <access_token>
```

The server's `JwtAuthenticationFilter` tries the `Authorization: Bearer …`
header first and falls back to the `access_token` cookie. CSRF
(`X-XSRF-TOKEN`) is required for cookie-authenticated mutating requests
but is **not** required for Bearer-authenticated requests — the
`SecurityConfig` CSRF skip detects the `Bearer` header directly and
exempts the request.

CORS is not a factor for native clients (no `Origin` header). Rate
limiting buckets per-user-id when authenticated, so it works identically
for cookie and Bearer transports.

## Token lifetimes

From `application.yml` (`app.jwt.*`):

| Token         | Lifetime        | Source key                       |
|---------------|-----------------|----------------------------------|
| Access token  | 1 hour (3,600,000 ms) | `app.jwt.access-token-expiration`  |
| Refresh token | 24 hours (86,400,000 ms) | `app.jwt.refresh-token-expiration` |

The mobile client SHOULD refresh proactively (e.g., a few minutes before
expiry) or reactively on the first 401. Both work. The 401-then-refresh
flow is shown below.

## Refresh-on-401 flow

```
Client                                Server
  |                                     |
  |--- GET /api/v1/accounts ----------->|
  |    Authorization: Bearer <stale>    |
  |                                     |
  |<------------------------- 401 ------|
  |    {error: UNAUTHORIZED}            |
  |                                     |
  |--- POST /api/v1/auth/token/refresh->|
  |    body: {refresh_token: "<rt>"}    |
  |                                     |
  |<--------- 200 + new {at, rt} -------|
  |  (persist new rt; rotate at)        |
  |                                     |
  |--- GET /api/v1/accounts ----------->|
  |    Authorization: Bearer <fresh>    |
  |                                     |
  |<-------- 200 + payload -------------|
```

Implementation hints for the client interceptor:

- Single retry per request. If the second attempt also returns 401, treat
  it as a forced logout: clear stored tokens and return the user to the
  login screen.
- Serialize concurrent refresh attempts. If multiple requests 401 at the
  same time, only one should call `/refresh`; the others wait for that
  call and then retry.
- The refresh response **rotates both tokens**. The previous refresh
  token is invalidated server-side (`token_generation` increment); reusing
  it returns 401.

## Token storage

Refresh tokens are long-lived credentials. They MUST be stored in
platform-managed secure storage:

- **iOS:** Keychain (e.g., `kSecClassGenericPassword` with a service-bound
  access group). Plain `UserDefaults` is unacceptable.
- **Android:** Keystore-backed `EncryptedSharedPreferences`. Plain
  `SharedPreferences` and `AsyncStorage` (React Native) are unacceptable.
- Optional: gate the refresh token behind a biometric prompt
  (Touch ID / Face ID / Android BiometricPrompt) on a configurable
  inactivity timer.

Access tokens may be held in memory for the session and re-derived from a
successful refresh on app resume.

## Logout

`POST /api/v1/auth/token/logout` with the current Bearer access token
returns 204 and increments the user's `token_generation` server-side,
invalidating all outstanding access and refresh tokens for that user
across both cookie and Bearer transports. After logout, the client MUST
delete the stored refresh token.

## Error envelope

All error responses use the standard envelope:

```json
{
  "error": "UNAUTHORIZED",
  "message": "Invalid email or password",
  "status": 401
}
```

Examples mobile clients should be ready for:

| Status | `error`            | When |
|--------|--------------------|------|
| 401    | `UNAUTHORIZED`     | Invalid credentials, missing/expired Bearer token, revoked refresh token |
| 403    | `FORBIDDEN`        | Authenticated but role lacks permission for this endpoint |
| 400    | `VALIDATION_FAILED`| Request body fails Bean Validation (e.g., missing `email`) |
| 409    | `CONFLICT`         | Duplicate email on register |
| 429    | `RATE_LIMITED`     | Too many requests for this principal/IP |
| 5xx    | `INTERNAL_ERROR`   | Server fault. Retry with exponential backoff. |

## Per-device sessions

Every successful login creates a row in `user_sessions` and the issued
access token carries the row id in a `sid` claim. The session list lets
users see and revoke individual devices without nuking other devices the
way `token_generation` does.

| Method | Path                                  | Auth                | Notes |
|--------|---------------------------------------|---------------------|-------|
| GET    | `/api/v1/auth/sessions`               | Bearer or Cookie    | Returns active sessions, sorted by `last_used_at` desc. The current session is marked `current: true`. |
| DELETE | `/api/v1/auth/sessions/{id}`          | Bearer or Cookie    | Revokes one session. Returns 404 if the id doesn't belong to the caller (avoids cross-tenant existence oracle). |
| DELETE | `/api/v1/auth/sessions`               | Bearer or Cookie    | "Log out everywhere else." Revokes every active session except the caller's current one. |

Optional `device_label` field on `POST /api/v1/auth/{token,}/login`
(max 64 chars) is surfaced in the session list so users can recognize
their own devices.

## Single-use refresh tokens

Refresh tokens rotate on every use. The server tracks each issued JTI in
`refresh_tokens`. When a token is consumed it's marked `used_at`; if the
same JTI shows up a second time we treat it as **compromise** —
`token_generation` is bumped, every active token (including the
legitimate replacement) is invalidated, and the caller must log in
fresh. Mobile clients must never retry a refresh call with the same
token; if a refresh fails for any reason, drop both tokens and re-auth.

## Rate-limit headers

Every API response carries:

| Header                  | Meaning |
|-------------------------|---------|
| `X-RateLimit-Limit`     | The bucket size for this principal/IP. Auth endpoints: 60/min/IP. Authenticated API: 300/min/user. |
| `X-RateLimit-Remaining` | Requests left in the current 60-second window. |
| `X-RateLimit-Reset`     | Unix epoch (seconds) when the current window resets. |

Headers travel on 2xx, 4xx, AND the 429 response itself. Clients should
back off proactively when `X-RateLimit-Remaining` falls below ~10, and
must wait until `X-RateLimit-Reset` after a 429.

## TOTP MFA

Users can enable TOTP MFA at any time via authenticator apps (Google
Authenticator, 1Password, Authy, etc.).

### Setup

```
POST /api/v1/auth/mfa/setup            (Bearer or Cookie, 200)
  Response: { secret, qr_code_uri, recovery_codes: [...10] }
            (secret + recovery codes shown ONCE; persist nothing client-side)

POST /api/v1/auth/mfa/verify-setup     (Bearer or Cookie, 204)
  Body: { totp_code: "123456" }
  → flips mfa_enabled = true. Until verified, MFA is not active.

GET  /api/v1/auth/mfa/status           (Bearer or Cookie, 200)
  Response: { enabled, setup_at, recovery_codes_remaining }
```

### Login challenge

```
POST /api/v1/auth/{token,}/login
  Body: { email, password }

If MFA is NOT enabled → returns tokens / cookies as before.
If MFA IS  enabled  → returns:
  HTTP 200
  Body: { mfa_required: true, mfa_token: "<short-lived JWT>" }

POST /api/v1/auth/{token,}/mfa/challenge
  Body: { mfa_token, totp_code }
        OR { mfa_token, recovery_code }
  → returns the same shape as the original login response (tokens or cookies).

The mfa_token is single-use, expires in 5 minutes, and is bound to the
specific user that just authenticated.
```

### Disable

```
POST /api/v1/auth/mfa/disable          (Bearer or Cookie, 204)
  Body: { totp_code }
  → requires a current TOTP. Clears all mfa_* state and recovery codes.

POST /api/v1/auth/mfa/regenerate-recovery-codes
  → invalidates the prior batch and returns 10 fresh codes.
```

### Sequence diagram (login w/ MFA)

```
Client                                Server
  |                                     |
  |--- POST /token/login -------------->|
  |    {email, password}                |
  |                                     |
  |<- 200 {mfa_required, mfa_token} ----|
  |                                     |
  |  user enters TOTP from authenticator|
  |                                     |
  |--- POST /token/mfa/challenge ------>|
  |    {mfa_token, totp_code}           |
  |                                     |
  |<- 200 {access_token, refresh_token}-|
```

## Version check

The mobile client SHOULD call `GET /api/v1/app/version-check` on every
launch (and ideally on app foreground) to learn whether the running build
is still acceptable.

| Method | Path                       | Auth | Notes |
|--------|----------------------------|------|-------|
| GET    | `/api/v1/app/version-check` | none | Anonymous — the app may not have credentials yet on launch. |

### Request

Two required query parameters:

| Param      | Required | Notes |
|------------|----------|-------|
| `platform` | yes      | `android` or `ios` (case-insensitive, normalized to lowercase server-side). |
| `version`  | yes      | The installed build's version. Semver shape: `\d+\.\d+\.\d+` with optional `-pre.release` suffix. |

Examples:

```
GET /api/v1/app/version-check?platform=android&version=1.2.3
GET /api/v1/app/version-check?platform=ios&version=2.0.0-beta.1
```

### Response (200)

```json
{
  "platform": "android",
  "current_version": "1.2.3",
  "minimum_supported_version": "1.0.0",
  "latest_version": "1.5.0",
  "update_required": false,
  "update_recommended": true,
  "store_url": "https://play.google.com/store/apps/details?id=com.wealthview",
  "message": null
}
```

| Field                       | Meaning |
|-----------------------------|---------|
| `update_required`           | `current_version < minimum_supported_version` (semver compare). When `true`, the app MUST refuse to operate and show a hard "Update required" screen pointing the user to `store_url`. |
| `update_recommended`        | `current_version < latest_version && !update_required`. When `true`, show a soft, dismissable "Update available" banner. |
| `message`                   | Optional admin-set string ("Required for new tax features"); shown alongside the prompt. `null` if not set. |
| `store_url`                 | Where to send the user to update. Operator-set per platform. |

### Recommended client behaviour

1. Call `version-check` early in the launch flow (before showing the login
   screen).
2. If `update_required` is `true`: show a non-dismissable update screen
   with `message` (if set) and a "Update now" button that opens
   `store_url`. Do not allow the user to proceed.
3. If `update_recommended` is `true`: show a dismissable banner. Allow
   normal use.
4. If both are `false`: continue normally.
5. Treat any 4xx/5xx as "skip the check" — do NOT block the user on
   transient backend issues.

### Errors

| Status | When |
|--------|------|
| 400    | Missing `platform` / `version`, unknown platform (anything other than android/ios), or malformed semver. |
| 5xx    | Server fault. Client should treat as "no policy returned" and proceed. |

### Caching

Server-side, the lookup is cached with a 5-minute TTL per platform.
Operator updates via the admin endpoint evict the cache immediately, so
fresh policy values are visible on the next request after a bump. Mobile
clients SHOULD NOT add their own client-side cache beyond a single launch.

### Admin endpoints (SUPER_ADMIN)

For operators only. Documented here so mobile developers know how the
backend values get bumped:

```
GET  /api/v1/admin/mobile-versions
PUT  /api/v1/admin/mobile-versions/{platform}
       body: {
         "minimum_supported_version": "1.5.0",
         "latest_version": "2.0.0",
         "store_url": "https://play.google.com/store/apps/details?id=com.wealthview",
         "message": "Required for new tax features"
       }
```

The seeded defaults (`0.0.1` / `0.0.1` and dummy store URLs) MUST be
updated by the operator before announcing a real mobile build.

## Out of scope (future work)

Not implemented today; surface here so the mobile developer can plan:

- Push notifications (no `device_registrations` table yet).
- Export-as-JSON-body variant of `DataExportController` (currently emits
  `text/csv` with `Content-Disposition: attachment`, which native apps
  can't trivially trigger).
