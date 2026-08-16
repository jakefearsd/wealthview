[<- Back to README](../../README.md)

# Security Hardening

This guide covers security practices for a WealthView production deployment.
Much of what is described below is already in place in the default
configuration — this document explains what the defaults are and what you
should add at the host-operating-system level on top of them.

It is organized by layer: secrets, host OS, Docker, the database, the
application itself, and the edge proxy. A final [checklist](#security-checklist)
summarizes everything.

---

## Secrets management

### Generate strong secrets

Every secret in `.env` should be cryptographically random. Never use
dictionary words, reuse secrets across environments, or commit secrets to git.

```bash
openssl rand -base64 24    # DB_PASSWORD
openssl rand -base64 48    # JWT_SECRET (must be 32+ chars)
openssl rand -base64 18    # SUPER_ADMIN_PASSWORD
openssl rand -base64 32    # MFA_ENCRYPTION_KEY (must decode to exactly 32 bytes)
```

`ProductionConfigValidator` runs on both the `prod` and `docker` profiles
(`@Profile({"prod", "docker"})`) and **aborts** the application if it detects
any of the following:

- `JWT_SECRET` unset, blank, shorter than 32 characters, matching a known dev
  default (`default-secret-key-...`, `production-secret-key-...`), or starting
  with the `LOCAL_DEV_` sentinel prefix.
- `SUPER_ADMIN_PASSWORD` unset, blank, matching `admin123` / `demo123` /
  the `LOCAL_DEV_...` sentinel, or starting with `LOCAL_DEV_`.
- `DB_PASSWORD` unset, blank, or a `LOCAL_DEV_` sentinel. Note the scope: there
  is **no length or entropy check** on the DB password — any non-sentinel,
  non-empty string passes. Generating a random one is on you.
- `MFA_ENCRYPTION_KEY` unset, blank, the known dev sentinel, or a `LOCAL_DEV_`
  value. Separately, `MfaSecretCipher` refuses to construct unless the value
  base64-decodes to exactly 32 bytes, so a malformed key fails at startup too.
- `CORS_ORIGIN` empty or blank on **either** profile. The additional
  "every origin must be `https://`" rule applies only when `prod` is active —
  the `docker` profile hardcodes `http://localhost` for local evaluation.
- The `prod` profile active alongside `dev` or `docker`. Those profiles activate
  `SampleDataInitializer` / `DevDataInitializer`, which seed accounts with
  hardcoded passwords that no env-var check could catch.

The validator runs on `ApplicationReadyEvent`, i.e. after the context has
refreshed — so the failure surfaces as the process exiting shortly after boot,
not as a refusal to bind. With `restart: unless-stopped` set on the container,
that looks like a crash loop. Check `./wv logs app` for the
`SECURITY: ...` message.

This is intentional. If you see the app fail to start with a
`ProductionConfigValidator` error, fix the `.env` value rather than
bypassing the check.

`MFA_ENCRYPTION_KEY` is the one secret you should **not** rotate casually: it
decrypts the stored TOTP shared secrets, so changing it makes every enrolled
authenticator undecryptable and forces affected users to re-enroll.

### Validate `.env` before bringing the stack up

`./wv up` and `./wv update` both run `wv_env_check` first, and refuse to
proceed unless `DB_PASSWORD`, `JWT_SECRET`, `SUPER_ADMIN_PASSWORD`, and
`MFA_ENCRYPTION_KEY` are all present, non-empty, and not still set to the
literal `CHANGE_ME` placeholder from `.env.example`. Run it on demand with:

```bash
./wv config-check
```

which additionally parses the resolved compose file and, if `gitleaks` is
installed, scans your staged diff.

Both compose files back that guard up at the interpolation layer: `docker-compose.yml`
and `docker-compose.prod.yml` alike interpolate `DB_PASSWORD`, `JWT_SECRET`,
`SUPER_ADMIN_PASSWORD` and `MFA_ENCRYPTION_KEY` as `${VAR:?message}`, so running
`docker compose -f docker-compose.prod.yml up` directly with any of them unset
aborts with the named variable rather than silently substituting an empty string.
`wv_env_check` remains the friendlier check (it also rejects a leftover
`CHANGE_ME`), but it is no longer the only thing standing between you and a
passwordless prod stack.

One caveat worth knowing: `wv_env_check` does **not** check `CORS_ORIGIN` — that
one is caught later, by `ProductionConfigValidator` at app startup.

### Protect the `.env` file

```bash
chmod 600 .env
ls -la .env      # -rw------- 1 you you ...
```

`.env` is in `.gitignore`. Verify before your first commit:

```bash
grep -E '^\.env$' .gitignore
```

If secrets ever land in git history, **rotate them** — deleting the file
from history is not enough. Generate new values and redeploy.

### Separate secrets per environment

Never share `JWT_SECRET` or `DB_PASSWORD` between staging, production, and
backup environments. A leak in one then compromises all.

### Secret scanning

The repo ships a `gitleaks` setup with two enforcement points:

- **Pre-commit hook** — `.githooks/pre-commit` runs
  `gitleaks protect --staged --redact --config .gitleaks.toml` and blocks the
  commit on a hit. It is **opt-in per clone**: activate it with
  `git config core.hooksPath .githooks` (or `./scripts/install-hooks.sh`). The
  hook hard-fails if `gitleaks` isn't on `PATH`.
- **CI workflow** — `.github/workflows/secret-scan.yml` runs
  `gitleaks detect` over the full history and uploads a SARIF report on
  failure. Note the trigger: **`push` on `v*` tags plus manual
  `workflow_dispatch` only.** It does not run on every push or pull request,
  so the pre-commit hook is your real per-commit gate.

`.gitleaks.toml` extends the v8 default ruleset, allowlists the dev/IT
sentinel values and the `ProductionConfigValidator` denylist file, and adds
one custom hard-block rule for four historical strings scrubbed from git
history. Don't refactor those denylist literals away — the allowlist exists so
that file can keep them.

### Rotating a secret

`./wv rotate-secret <NAME>` generates a fresh value, writes it to `.env`, and
restarts what needs restarting. Supported names are `JWT_SECRET`,
`SUPER_ADMIN_PASSWORD`, and `DB_PASSWORD` — anything else is rejected.
`MFA_ENCRYPTION_KEY` is deliberately not rotatable through this path (see
above).

---

## Host operating system

### Firewall (ufw)

Lock down the server to only the ports you actually use.

If you are running the app behind **nginx on the host** (Let's Encrypt for
TLS):

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp      # SSH (or your custom SSH port)
sudo ufw allow 80/tcp      # HTTP (certbot challenges + HTTPS redirect)
sudo ufw allow 443/tcp     # HTTPS
sudo ufw enable
sudo ufw status verbose
```

If you are running the app behind **Cloudflare Tunnel**:

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp      # SSH only
sudo ufw enable
```

`cloudflared` connects *outbound* to Cloudflare, so you don't need any
inbound rule for HTTP/HTTPS at all.

### SSH

Disable password authentication (keys only):

```bash
sudo nano /etc/ssh/sshd_config
```

```
PasswordAuthentication no
PubkeyAuthentication yes
PermitRootLogin no
```

Make sure your public key is already in `~/.ssh/authorized_keys` before
disabling passwords — otherwise you will lock yourself out.

Apply changes:

```bash
sudo systemctl restart sshd
```

**Test from a new terminal before closing your current session.**

Changing the SSH port (e.g. to 2222) adds a small amount of log hygiene but
is not a substitute for key-only auth.

### Automatic security updates

On Debian/Ubuntu:

```bash
sudo apt-get install -y unattended-upgrades
sudo dpkg-reconfigure --priority=low unattended-upgrades
```

This keeps the kernel, OpenSSH, and other host packages patched without
manual intervention.

---

## Docker

### The app runs as a non-root user

The `app` container image runs as an unprivileged user (`wv`, added by the
Dockerfile with `RUN addgroup -S wv && adduser -S wv -G wv`). If an attacker
ever achieves code execution inside the container, they cannot immediately
touch the host kernel or any file not owned by `wv`.

Verify after a rebuild:

```bash
docker compose -f docker-compose.prod.yml exec app id
# uid=101(wv) gid=101(wv) groups=101(wv)
```

### Container-level HEALTHCHECK

The Dockerfile declares:

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
    CMD wget -q -O- http://localhost:8080/actuator/health || exit 1
```

Docker reports the container as `(healthy)` / `(unhealthy)` in
`docker compose ps`, and downstream tools (systemd, watchtower, container
orchestrators) can restart an unhealthy container automatically.

That is the single definition — no compose file overrides it. `wget` is used
deliberately: the Alpine JRE runtime image never installs `curl` (the Dockerfile
has no `apk add`), and `docker-compose.prod.yml` used to override the probe with
`["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]`, which left prod
containers reporting `(unhealthy)` permanently while they served fine. The
override is gone and the comment in its place says why, so the two copies cannot
drift apart again.

Container health is still not your only signal: `./wv up` and `./wv status`
probe `http://localhost:${APP_PORT}/actuator/health` from the host over HTTP,
independent of the container's own HEALTHCHECK.

### Image pinning and restart policy

`docker-compose.prod.yml` pins the app to
`wealthview:${WEALTHVIEW_VERSION:?...}` — the `:?` form means Compose refuses
to start without an explicit version, so `:latest` can never be deployed by
accident. The `db` service is pinned by digest (`postgres:16@sha256:...`), as
are all three Dockerfile base images. All three prod services carry
`restart: unless-stopped`.

### No unnecessary port publishing

`docker-compose.prod.yml` publishes exactly one port to the host — the
`app` container's `8080` is mapped to `${APP_PORT}` (default `80`). The `db`
and `backup` containers do not publish any ports; they are only reachable from
other containers on the Compose-managed internal network. (The dev
`docker-compose.yml` *does* publish Postgres on `5433` — that file is for local
development, not for a server.)

If your server has a public IP, either keep the firewall rule on port
`${APP_PORT}` closed (so only nginx or cloudflared can reach it) or bind
the app's published port to loopback only by editing the Compose file:

```yaml
services:
  app:
    ports:
      - "127.0.0.1:${APP_PORT:-8080}:8080"
```

### Resource limits (optional)

Prevent a runaway container from consuming all host memory:

```yaml
services:
  app:
    deploy:
      resources:
        limits:
          memory: 1g
          cpus: '1.5'
  db:
    deploy:
      resources:
        limits:
          memory: 512m
          cpus: '0.5'
```

### Keep Docker updated

```bash
sudo apt-get update && sudo apt-get upgrade -y docker-ce docker-ce-cli containerd.io
```

Rebuild periodically so you pull the latest base-image security updates:

```bash
docker compose -f docker-compose.prod.yml up --build --pull always -d
```

`--pull always` forces Docker to check for newer versions of the parent
images (Temurin JRE, Alpine, PostgreSQL, etc.) before building.

---

## Database

- **No published ports** in production. The `db` container is reachable only
  from `app` and `backup` on the internal Docker network.
- **Strong password.** `ProductionConfigValidator` rejects an unset, blank, or
  `LOCAL_DEV_*` sentinel `DB_PASSWORD`, and `wv_env_check` rejects a leftover
  `CHANGE_ME`. Neither checks length or entropy — a weak-but-non-default
  password passes both. Generate it with `openssl rand` and treat the
  validators as a floor, not a policy.
- **Backups encrypted at rest (optional but recommended).** The dump written by
  `./wv backup` under `./backups/` is plain `.dump`. Use the built-in
  encryption instead of doing it by hand:
  ```bash
  ./wv backup --encrypt          # needs BACKUP_ENCRYPTION_RECIPIENT (an age public key) in .env
  ```
  It replaces the plaintext dump with a `.dump.age` file rather than leaving
  both on disk. When `./wv` is in prod mode and you take a backup *without*
  `--encrypt`, it logs a warning naming the plaintext path — that warning is the
  only guard; nothing forces encryption on. `./wv restore` and `./wv verify`
  need the matching identity via `BACKUP_ENCRYPTION_KEY_FILE`.
  Cloud-side encryption is a fine alternative if you're syncing to
  S3/Backblaze/etc.

---

## Application-level security

The items below describe what the application actually does, so you know what
is and isn't protecting your deployment. Where a control is deliberately absent
or narrower than it sounds, that is called out inline rather than glossed over.

### Authentication

- Access tokens default to **1 hour** (`app.jwt.access-token-expiration:
  3600000` ms) and refresh tokens to **24 hours**
  (`refresh-token-expiration: 86400000` ms). These are the shipped defaults on
  every profile; override them per deployment if you want them tighter.
- Tokens are HMAC-signed with `JWT_SECRET` (jjwt picks HS256/384/512 from the
  key length — a 32-character secret gives HS256). Every token carries `iss`
  and `aud` claims, and parsing *requires* both to match, with a 60-second
  clock-skew allowance.
- Access tokens also carry a session id (`sid`) claim. On **every** request,
  `SessionStateValidator` re-checks that the user still exists and is active,
  the tenant is active, the token generation is current, and the session row
  has not been revoked. This is a database read per request, and it is what
  makes revocation take effect immediately rather than at token expiry.
- Refresh tokens are bound to a per-user `tokenGeneration` counter. Logging out,
  an admin password reset, and every successful refresh all bump the counter,
  invalidating previously-issued tokens. The `users` row carries a separate
  JPA `@Version` column, so two concurrent refreshes conflict and the loser is
  rejected as `race_lost` rather than both being issued.
- Refresh tokens rotate on use and are tracked by JTI. Presenting an
  already-consumed JTI is treated as compromise: the token generation is bumped
  and **all** of that user's refresh tokens are revoked, in a separate
  transaction so it commits even though the request itself fails.
- Per-device sessions are listable and revocable via `/api/v1/auth/sessions`
  (`GET`, `DELETE /{id}`, `DELETE` for all).
- Passwords are hashed with BCrypt at **strength 12** — for login credentials
  and for MFA recovery codes alike. Registration enforces 8–64 characters plus
  a common-password denylist (`common-passwords.txt`); both admin password-reset
  paths apply the same denylist.
- The `/api/v1/auth/register` endpoint validates the invite code **before**
  it queries for email uniqueness. This eliminates a timing / response-code
  difference that could otherwise have been used to enumerate registered
  emails. Login does the equivalent on its side: an unknown email still burns
  a BCrypt comparison against a dummy hash so response timing doesn't leak
  whether the account exists.
- **Account lockout is per email address, not per IP.**
  `LoginAttemptService` blocks further attempts after 5 failures for the same
  email within a rolling 15-minute window; a successful login clears it. The
  counters live in an in-process map, so they reset on restart and are not
  shared across instances. The separate per-IP throttle is the
  [rate limiter](#rate-limiting) below — these are two different mechanisms.

### Multi-factor authentication (TOTP)

MFA is opt-in per user, managed under `/api/v1/auth/mfa/*` (all authenticated,
all operating on the caller's own account).

- Standard TOTP: SHA-1, 6 digits, 30-second period, verification allows ±1
  time step (about ±30 seconds of drift).
- The shared secret is encrypted at rest with **AES-256-GCM** using
  `MFA_ENCRYPTION_KEY`, with a fresh 12-byte IV per encryption prepended to the
  ciphertext. A dump of the `users` table alone does not yield usable TOTP
  secrets.
- Ten single-use 8-character recovery codes are issued at setup (and on
  regenerate), drawn from a confusable-free alphabet by `SecureRandom` and
  stored only as BCrypt hashes.
- After the password check, an MFA-enabled account gets a short-lived challenge
  token instead of session tokens: 5-minute TTL, backed by a persisted
  single-use `mfa_challenges` row bound to the user, so a challenge token can't
  be replayed or redirected to a different account.

### Authorization

The filter chain (`SecurityConfig`) is stateless — `SessionCreationPolicy.STATELESS`,
no server-side HTTP session. The path rules, in order:

| Path | Requirement |
|---|---|
| `/actuator/health` | anonymous |
| `/actuator/**` (all other endpoints) | `SUPER_ADMIN` |
| `/api/v1/auth/**` | anonymous, **except** `me`, `logout`, `sessions*`, and `mfa/*` management, which require authentication |
| `GET /api/v1/app/version-check` | anonymous (mobile force-update check) |
| `/api/v1/admin/prices/**` | `ADMIN` or `SUPER_ADMIN` |
| `/api/v1/admin/**` | `SUPER_ADMIN` |
| `POST`/`PUT`/`DELETE` on `/api/v1/prices/**` | `ADMIN` or `SUPER_ADMIN` |
| `/api/v1/tenant/invite-codes*`, `/api/v1/tenant/users*` | `ADMIN` or `SUPER_ADMIN` |
| `GET /api/v1/**` | authenticated |
| `POST`/`PUT`/`DELETE` on `/api/v1/**` | `ADMIN`, `MEMBER`, or `SUPER_ADMIN` |
| `GET /**` | anonymous — this is the SPA's static bundle |

Two things to be aware of:

- Only `/actuator/health` is anonymous; Prometheus scraping of
  `/actuator/prometheus` and `/actuator/metrics` requires `SUPER_ADMIN`
  credentials. The single exception is the `loadtest` profile, which permits
  those two endpoints anonymously — that profile is for a throwaway local
  synthetic-data stack and must never be activated on a server.
- `GET /api/v1/**` is gated on *authentication only*, not on role. Any
  logged-in member of a tenant can read anything readable in their own tenant,
  including the audit log. Role separation applies to mutations and to the
  admin surface.

### Cookies and CSRF (web client)

- `AuthController` issues both tokens as cookies with `HttpOnly`, `Path=/`,
  `SameSite=Strict`, and `Secure` driven by `app.cookie.secure`. That property
  defaults to **`true`** in `application.yml`, and the `prod` profile does not
  override it — but the **`docker` profile sets `app.cookie.secure: false`**
  (as do `dev` and `it`), because that stack is served over plain HTTP on
  localhost. If you deploy the `docker` profile anywhere reachable over a
  network, your auth cookies are not `Secure`-flagged.
- Tokens never appear in a response body on the cookie transport, and the
  frontend never touches `localStorage` — `frontend/src/api/client.ts` uses the
  shared client's `transport: 'cookie'` with both token getters hardwired to
  `null`.
- CSRF uses Spring's `CookieCsrfTokenRepository.withHttpOnlyFalse()`
  double-submit pattern: the `XSRF-TOKEN` cookie is deliberately readable by
  JavaScript (that is the point) and axios echoes it as `X-XSRF-TOKEN`. The
  cookie's `Secure` flag follows the same `app.cookie.secure` property.
- CSRF is **not** enforced on: `POST /api/v1/auth/login`, `/register`,
  `/refresh`, `/mfa/challenge`, anything under `/api/v1/auth/token/**`, or
  **any request carrying an `Authorization: Bearer ...` header**. The Bearer
  exemption is safe only because a cross-site page cannot set that header;
  it does mean CSRF protection is opt-out by presenting a Bearer token.
- CORS is configured for `/api/**` only: origins from
  `app.cors.allowed-origins` (`CORS_ORIGIN`), methods `GET/POST/PUT/DELETE/OPTIONS`,
  request headers `Authorization`, `Content-Type`, `X-XSRF-TOKEN`, with
  `allowCredentials: true`.

### Mobile transport (Bearer tokens)

`/api/v1/auth/token/*` is a parallel auth surface for the native apps that
returns access and refresh tokens **in the response body**, and those requests
skip CSRF entirely. This is a deliberate trade — native clients don't run
untrusted JavaScript — but it means the XSS-exfiltration protection that the
cookie transport buys applies to the web client only. Secure storage of the
refresh token (Keychain / Keystore) is the mobile client's responsibility.

### Tenant isolation

Service methods that read or write business data filter by `tenant_id`, and the
`tenant_id` is taken from the authenticated user's JWT via
`SecurityContextHolder` (as `TenantUserPrincipal`) — **never** from a request
parameter, path variable, or body field. A cross-tenant read would require
forging a JWT for another tenant, which the signing key prevents.

Be clear about what enforces this: it is a **code convention**, backed by
repository methods that take `tenantId` (`findByTenant_IdAndId(...)`) and by
tests — not by a database mechanism. There is **no PostgreSQL row-level
security** in the schema. A service method that forgets the filter would leak
across tenants, and nothing below the application layer would stop it. Treat
tenant-scoping as a review item on every new query.

### Audit logging

User- and admin-facing mutations (tenant lifecycle, invite codes, user role
changes, user deletes, password resets, data exports, etc.) publish
`AuditEvent` domain events. An async listener persists them into the
`audit_log` table. The details payload of each audit record is bounded by
`AuditDetailsValidator`: anything larger than 8 KB or deeper than 3 nested
levels gets replaced with a marker object, so a malicious caller cannot
amplify one request into gigabytes of audit storage.

### HTTP security headers

Every response from the Spring Boot layer carries:

| Header | Value |
|--------|-------|
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains; preload` |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'` |
| `Permissions-Policy` | `geolocation=(), microphone=(), camera=(), payment=()` |

`Permissions-Policy` is new as of the 2026-04-22 security pass. It tells the
browser to refuse every script on the page (ours or injected) access to
geolocation, microphone, camera, and payment APIs — features the app does
not use at all.

**`Referrer-Policy` is not set by the application.** It is only added by
`nginx-prod.conf` (`strict-origin-when-cross-origin`). If you terminate TLS
somewhere other than that config — Cloudflare Tunnel, your own nginx vhost —
add it there yourself.

If you also add these at the nginx layer (see [tls-and-nginx.md](tls-and-nginx.md)),
that's fine — duplicates are harmless.

### Rate limiting

`RateLimitFilter` counts requests in an in-process `ConcurrentHashMap` over
fixed 60-second windows. What it actually covers:

- **Only paths starting with `/api/`.** `/actuator/**` and the static SPA
  bundle are not rate limited at all.
- `/api/v1/auth/**` (including the mobile `/api/v1/auth/token/**` endpoints) is
  keyed **per client IP** at **60 req/min**.
- Everything else under `/api/` is keyed **per authenticated principal** at
  **300 req/min**, falling back to the client IP when the caller is anonymous.
- **`SUPER_ADMIN` bypasses the limiter entirely** — before any counting happens.
- Every response carries `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and
  `X-RateLimit-Reset`; over-limit requests get `429` with the standard error
  envelope.
- The map is swept of expired windows once a minute, or immediately once it
  exceeds 50,000 tracked keys, so a distributed source-IP flood can't grow it
  without bound.
- The whole filter can be switched off with `app.rate-limit.enabled=false`
  (`application-it.yml` does this so integration tests aren't throttled). Do not
  set it on a server.

Client IP comes from `ClientIpResolver`, which reads `X-Forwarded-For` **only**
when the immediate peer's address appears in `app.rate-limit.trusted-proxies`
(`APP_RATE_LIMIT_TRUSTED_PROXIES`); otherwise it uses the socket address. That
same resolved IP is what login-activity rows record.

> **Gap worth knowing:** neither `docker-compose.yml` nor
> `docker-compose.prod.yml` lists `APP_RATE_LIMIT_TRUSTED_PROXIES` in the
> `app` service's `environment:` block, and neither uses `env_file`. Compose
> uses `.env` for *substitution*, not for injecting arbitrary variables into
> the container — so putting the variable in `.env` alone does **not** reach
> the application. To actually set it, add
> `APP_RATE_LIMIT_TRUSTED_PROXIES: ${APP_RATE_LIMIT_TRUSTED_PROXIES:-}` to the
> `app` service's `environment:` block (a compose override file is the tidy
> way). Until you do, `X-Forwarded-For` is ignored and both the per-IP auth
> limit and login-activity records will show your proxy's address for every
> client.

**The limiter is correct for — and only for — a single application instance.**
WealthView's supported deployment topology (`./wv up`,
`docker-compose.prod.yml`) runs exactly one `app` container, so an in-memory
limiter sees every request and enforces the limit accurately.

If WealthView is ever scaled out to multiple `app` instances behind a load balancer,
this limiter's accounting is **per-instance, not global** — an attacker (or a
misbehaving client) could receive N× the intended limit by having requests spread
across N instances. The same caveat applies to `LoginAttemptService`'s lockout
counters. Revisit before scaling horizontally: move the counters to Redis
(or another shared store) or push rate limiting to the edge proxy (nginx
`limit_req_zone`, Cloudflare rate limiting rules). Not needed for the current
single-host deployment model — no action taken as part of the 2026-07-13 hardening
pass.

### Outbound HTTP timeouts

The Finnhub and Yahoo price clients are built with explicit connect and read
timeouts (5s connect, 15s read). The Zillow scraper client uses
`app.zillow.timeout-ms` (default 10s) and is disabled unless
`app.zillow.enabled=true`. A hung upstream can no longer pin a servlet thread
indefinitely.

### API key transmission

The Finnhub API key is sent as an `X-Finnhub-Token` HTTP header, never as a
query string. This keeps the key out of access logs, proxy logs, and
error-reporting breadcrumbs.

### CSV injection

The CSV exports produced by `DataExportService` — `/api/v1/export/csv/accounts`,
`/csv/transactions`, `/csv/holdings`, `/csv/properties` — are neutralized
against formula-injection attacks: any cell value beginning with `=`, `+`,
`-`, `@`, tab, or CR is prefixed with a single quote and quoted, so Excel /
Numbers / LibreOffice Calc don't execute it as a formula. (There is no
audit-log CSV export; the audit log is JSON only.)

### Subresource Integrity (SRI)

`frontend/index.html` currently loads **zero** third-party CDN assets — no
remote fonts, scripts, or stylesheets. Everything is bundled by Vite and served
same-origin, which is also why the CSP above can get away with a bare
`script-src 'self'`.

That is a property to preserve, not a control that's switched on. The file
carries a comment in `<head>` stating the rule: if a CDN asset is ever added,
it MUST carry `integrity="sha384-..."` and `crossorigin="anonymous"`. Adding a
remote `<script>` without those would silently widen the trust boundary to
whoever runs that CDN, and would need a CSP change too.

---

## Edge proxy

Whether you use nginx-on-host or Cloudflare Tunnel, the edge proxy is the
only thing facing the public internet. Keep it simple and keep it current.

- **TLS 1.2 and 1.3 only.** Disable older protocols. `python3-certbot-nginx`
  does this automatically via `options-ssl-nginx.conf`. Cloudflare terminates
  TLS with modern ciphers by default.
- **HTTP → HTTPS redirect.** Certbot's `--redirect` flag adds this for
  nginx. Cloudflare offers "Always Use HTTPS" in **SSL/TLS → Edge
  Certificates**.
- **Forwarded-header hygiene.** The app honors `X-Forwarded-For` only from
  peers in `APP_RATE_LIMIT_TRUSTED_PROXIES` — and see the compose-plumbing gap
  noted under [Rate limiting](#rate-limiting) before you assume setting it in
  `.env` was enough.

### The nginx configs in this repo

`nginx.conf` and `nginx-prod.conf` sit at the repo root. **Neither is wired
into `docker-compose.yml` or `docker-compose.prod.yml`** — there is no nginx
service in either file; the app container serves the SPA and the API itself.
They are reference templates for an operator-run nginx, and what they actually
contain is:

| | `nginx.conf` | `nginx-prod.conf` |
|---|---|---|
| TLS | none — plain `listen 80` | `listen 443 ssl http2`, `TLSv1.2 TLSv1.3`, `HIGH:!aNULL:!MD5`, `ssl_prefer_server_ciphers on` |
| HTTP → HTTPS | no | yes, `301`, with an ACME challenge location carved out |
| Security headers | **none** | HSTS (`max-age=31536000; includeSubDomains; preload`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin` |
| Forwarded headers | `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto` | same |
| Rate limiting | none | none — no `limit_req_zone` is configured in either file |

If you front the app with `nginx.conf` as-is on anything public, you get no
TLS and no headers from the proxy layer. Use `nginx-prod.conf` (or certbot's
generated vhost) for a real deployment.

**HSTS preload:** the app and `nginx-prod.conf` both send the `preload`
directive, but preloading is not active until you actually submit the domain
at <https://hstspreload.org>. That step can only be done by you, and is easy to
mistake for "done" because the header is present.

For Cloudflare-specific hardening options (Access, WAF, rate limiting at the
edge), see [cloudflared.md](cloudflared.md#security-notes-specific-to-cloudflare-tunnel).

---

## Regular maintenance

### Dependency updates

```bash
# Maven dependencies (backend)
cd backend && mvn versions:display-dependency-updates

# npm dependencies (frontend)
cd frontend && npm audit
```

Watch security advisories for:

- Spring Boot / Spring Security
- PostgreSQL
- nginx
- Let's Encrypt / certbot (the renewal timer email is your friend)
- cloudflared (auto-updates on Debian/Ubuntu if you use the apt repo)

### Review the audit log

The Audit Log lives as a tab inside the `/admin` page (the old `/audit-log`
route now redirects there). The backing endpoint `GET /api/v1/audit-log` is
tenant-scoped and requires the `ADMIN` or `SUPER_ADMIN` role — it used to carry
no matcher at all and fell through to `anyRequest().authenticated()`, so any
member or viewer could read the whole trail directly over HTTP regardless of
what the UI showed. `SecurityConfig` now gates it explicitly; UI-only gating is
not access control. Skim it periodically for entries that don't match
legitimate activity.

Super-admins additionally get `GET /api/v1/admin/login-activity`, which records
successful and failed login attempts with the resolved client IP.

### Rotate credentials periodically

- Rotate `JWT_SECRET` at least annually. All users will be forced to log in
  again after the rotation.
- Rotate `DB_PASSWORD` when team membership changes.
- Rotate `SUPER_ADMIN_PASSWORD` whenever someone with access leaves.
- Do **not** rotate `MFA_ENCRYPTION_KEY` — it decrypts stored TOTP secrets, and
  `./wv rotate-secret` deliberately refuses the name.

To rotate:

```bash
./wv rotate-secret JWT_SECRET          # or SUPER_ADMIN_PASSWORD, DB_PASSWORD
```

This writes the new value into `.env` and restarts what needs restarting.
Take a fresh backup afterwards (`./wv backup --label post-rotate-...`), as the
command itself reminds you.

---

## Security checklist

Use this to verify your deployment:

- [ ] `.env` has `chmod 600` permissions
- [ ] `.env` is listed in `.gitignore`
- [ ] `DB_PASSWORD`, `JWT_SECRET`, `SUPER_ADMIN_PASSWORD`, `MFA_ENCRYPTION_KEY`
      all randomly generated — no `CHANGE_ME` left
- [ ] `JWT_SECRET` is 32+ characters; `MFA_ENCRYPTION_KEY` base64-decodes to 32 bytes
- [ ] `./wv config-check` passes
- [ ] `CORS_ORIGIN` is set to `https://your-domain`
- [ ] `SPRING_PROFILES_ACTIVE` is `prod` **only** — never `prod,docker`
- [ ] `app.cookie.secure` is left at its `true` default (i.e. you are on the
      `prod` profile, not `docker`) so auth cookies carry `Secure`
- [ ] `APP_RATE_LIMIT_TRUSTED_PROXIES` set to the edge proxy's peer IP **and**
      plumbed into the `app` service's `environment:` block — putting it in
      `.env` alone does not reach the container
- [ ] App starts cleanly on the `prod` profile (no `ProductionConfigValidator` errors)
- [ ] `/actuator/prometheus` returns 401/403 to an unauthenticated caller
- [ ] Firewall allows only the ports you need (22, optionally 80 + 443)
- [ ] SSH password auth is disabled; root login disabled
- [ ] Automatic host-OS security updates enabled (`unattended-upgrades`)
- [ ] `db` container has no published ports in production
- [ ] `app` container published port is bound to loopback or firewalled
- [ ] Container runs as `wv` (non-root) — verified via `docker exec ... id`
- [ ] App answers `./wv status`; `docker compose ps` should also show `(healthy)`
      now that the image's own `wget` HEALTHCHECK is the only definition
- [ ] `WEALTHVIEW_VERSION` pinned to a real version, never `latest`
- [ ] TLS end-to-end: `curl -s https://your-domain/actuator/health` returns `{"status":"UP"}`
- [ ] Security headers present: `curl -sI https://your-domain | grep -Ei 'strict-transport|permissions-policy|x-frame-options|x-content-type'`
- [ ] `Referrer-Policy` present — the app does not send it; your proxy must
- [ ] Backups running: `./wv backups` shows recent dumps (encrypted if `--encrypt`)
- [ ] You have tested a restore at least once in a non-prod environment
- [ ] `git config core.hooksPath .githooks` set in every clone you commit from
      (CI's secret scan only runs on `v*` tags)

---

## Hardening pass 2026-07-13

T30 closed out the 10 deployment/env items deferred from the 2026-04-10 security
audit (see the `project_security_audit_deferred` memory note). Much had already
landed as the compose stack, `./wv` dispatcher, and `ProductionConfigValidator`
matured since April; this pass verified each item against the current code and
fixed the two real gaps found.

| # | Item | Disposition | Evidence / change |
|---|------|--------------|--------------------|
| 1 | `.env` / `.env.local` in `.gitignore` | Already done | `.gitignore` lines for `.env`, `.env.*`, `frontend/.env*` |
| 2 | docker-compose secret fallbacks | Done — completed for prod since | **Both** compose files now use `${VAR:?message}` (fail-loud) for `DB_PASSWORD`, `JWT_SECRET`, `SUPER_ADMIN_PASSWORD` and `MFA_ENCRYPTION_KEY`. `docker-compose.prod.yml` originally interpolated all four as plain `${VAR}`, leaving `wv_env_check` as the only prod-side guard and letting a direct `docker compose -f docker-compose.prod.yml up` silently substitute empty strings; it was brought in line with the dev file. Neither file gives any secret a `:-` default, and `WEALTHVIEW_VERSION` still carries `:?` |
| 3 | CORS origins in docker profile | Already done | `application-prod.yml` sets `app.cors.allowed-origins: ${CORS_ORIGIN}` (no default, fails loud unresolved); `ProductionConfigValidator.validateCorsOrigins()` additionally rejects empty/blank/non-`https://` origins under `prod`. The `docker` profile (`application-docker.yml`, hardcoded `http://localhost`) is the dev/demo containerized stack per `docker-compose.yml`'s `SPRING_PROFILES_ACTIVE=docker` — never reachable from the prod compose file, which sets `SPRING_PROFILES_ACTIVE=prod`. Bonus fix: `.env.example`'s `CORS_ORIGIN` comment incorrectly said "optional, leave empty for same-origin" — corrected, since the validator has required it non-empty since an earlier pass (`prodProfile_emptyCorsOrigin_fails` test) |
| 4 | Dev-seed prod assertion | Fixed now | `ProductionConfigValidator.validateNoDevSeedProfileWithProd()` halts startup if `prod` is active alongside `dev`/`docker` (defense-in-depth against `SPRING_PROFILES_ACTIVE=prod,docker` misconfiguration, since `SampleDataInitializer` seeds a hardcoded `demo@wealthview.local`/`demo123` admin that no env-var check can catch). Also generalized the JWT/super-admin/DB-password checks to reject any value with the `LOCAL_DEV_` sentinel prefix, not just the literal strings previously enumerated — closes a real gap where `application-dev.yml`'s 68-char JWT fallback (`LOCAL_DEV_HS256_SIGNING_KEY_...`) was long enough to pass the length check and wasn't in the known-defaults set. TDD: `ProductionConfigValidatorTest` (6 new cases) |
| 5 | Backup encryption | Fixed now | `--encrypt` + `BACKUP_ENCRYPTION_RECIPIENT` already existed; added a `WARN` in `bin/wv-lib/backup.sh` when a backup is written unencrypted while `wv_mode` is `prod`. Covered by 3 new bats cases in `scripts/test/wv.bats` |
| 6 | Docker `:latest` tag | Already done | `docker-compose.prod.yml` pins `image: ${WEALTHVIEW_IMAGE:-ghcr.io/jakefearsd/wealthview}:${WEALTHVIEW_VERSION:?...}` (fails loud without a version, and the `:?` message spells out that `latest` must not be deployed — it defeats `wv rollback`); the dev `docker-compose.yml`'s `app` service has no `image:` line (local `build: .` only, never pulled by tag). Both files pin `db` to `postgres:16@sha256:...` by digest |
| 7 | httpOnly cookie migration | Already done | `AuthController.buildCookie()` (`backend/wealthview-api/.../AuthController.java:139-147`) sets `httpOnly(true)`, `secure(cookieSecure)`, `sameSite("Strict")`; `SecurityConfig` wires CSRF via `CookieCsrfTokenRepository` double-submit (`X-XSRF-TOKEN` header echo); frontend `client.ts` uses `transport: 'cookie'`, no token in `localStorage` |
| 8 | SRI rule | Fixed now (docs only) | `frontend/index.html` has no CDN assets to begin with; added an enshrining comment in `<head>` plus a bullet in `CLAUDE.md`'s Frontend Conventions |
| 9 | HSTS preload | Fixed now | Added `.preload(true)` to `SecurityConfig`'s `httpStrictTransportSecurity` config and `; preload` to `nginx-prod.conf`'s header. **Operator action still required and NOT performed by this pass:** once the production domain is stable, submit it at https://hstspreload.org — that step can't be done from the repo |
| 10 | Rate-limit backend | Documented / accepted | `RateLimitFilter`'s in-memory `ConcurrentHashMap` is correct for the single-instance deployment `./wv` supports. Acceptance + revisit trigger documented under [Rate limiting](#rate-limiting) above |

**Files touched:** `backend/wealthview-app/.../ProductionConfigValidator.java` (+test),
`backend/wealthview-api/.../SecurityConfig.java`, `bin/wv-lib/backup.sh` (+bats tests),
`nginx-prod.conf`, `frontend/index.html`, `.env.example`, `CLAUDE.md`, this file.

### Re-verification 2026-08-16

Every claim in this document was re-checked against the source. Items 1, 3, 4,
5, 7, 8, 9, 10 above still hold as written (item 9's operator step —
submitting the domain at hstspreload.org — remains outstanding, since nothing
in the repo can do it). What changed:

- **Item 2 was overstated,** then closed. The re-verification found that
  `docker-compose.prod.yml` did not use the fail-loud `${VAR:?}` form for its
  secrets; that has since been fixed, so all four now fail loudly in both
  compose files. See [Generate strong secrets](#generate-strong-secrets).
- **Item 6's wording was loose** about `image:` lines; tightened.
- **The prod HEALTHCHECK override was removed.** It called `curl`, which the
  Alpine JRE image does not ship, so prod containers reported `(unhealthy)`
  permanently. The Dockerfile's `wget` probe is now the only definition. See
  [Container-level HEALTHCHECK](#container-level-healthcheck).

Gaps found during this re-verification that are **not yet fixed**, listed here
so they aren't mistaken for coverage:

| Gap | Effect |
|---|---|
| `APP_RATE_LIMIT_TRUSTED_PROXIES` is not in either compose file's `environment:` block | Setting it in `.env` alone never reaches the container; `X-Forwarded-For` stays untrusted and audit/rate-limit IPs show the proxy. See [Rate limiting](#rate-limiting) |
| No `limit_req_zone` in either nginx config | There is no edge-layer rate limiting to back up the in-process limiter |
| `Referrer-Policy` is sent only by `nginx-prod.conf` | Deployments that terminate TLS elsewhere get no `Referrer-Policy` at all |
| CI secret scanning runs only on `v*` tags | Between releases, the opt-in `.githooks/pre-commit` hook is the only secret gate |
| `DB_PASSWORD` has no length/entropy validation anywhere | A short, non-default password passes both `wv_env_check` and `ProductionConfigValidator` |

---

## Related guides

- [Production Setup](production-setup.md) — full deployment walkthrough.
- [TLS and Nginx](tls-and-nginx.md) — host-managed TLS.
- [Cloudflared Deployment](cloudflared.md) — Cloudflare Tunnel TLS.
- [Upgrading](upgrading.md) — keep the app up to date.
