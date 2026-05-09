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

## Out of scope (future work)

Not implemented today; surface here so the mobile developer can plan:

- Force-update endpoint (`GET /api/v1/app/version-check`).
- Push notifications (no `device_registrations` table yet).
- Export-as-JSON-body variant of `DataExportController` (currently emits
  `text/csv` with `Content-Disposition: attachment`, which native apps
  can't trivially trigger).
