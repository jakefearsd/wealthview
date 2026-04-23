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
openssl rand -base64 48    # JWT_SECRET (must be 32+ chars for HMAC-SHA256)
openssl rand -base64 18    # SUPER_ADMIN_PASSWORD
```

The `prod` Spring profile runs `ProductionConfigValidator` at startup and
**aborts** the application if it detects any of the following:

- `JWT_SECRET` shorter than 32 characters, unset, or matching a known dev
  default (`default-secret-key-...`, `production-secret-key-...`).
- `SUPER_ADMIN_PASSWORD` matching `admin123`, `demo123`, or `DevPass123!`.
- `CORS_ORIGIN` empty, or not a comma-separated list of `https://...` URLs
  (the one exception is the `docker` profile, which also allows
  `http://localhost` for local evaluation).

This is intentional. If you see the app fail to start with a
`ProductionConfigValidator` error, fix the `.env` value rather than
bypassing the check.

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

### No unnecessary port publishing

`docker-compose.prod.yml` publishes exactly one port to the host — the
`app` container's `8080` is mapped to `${APP_PORT}`. The `db` and `backup`
containers do not publish any ports; they are only reachable from other
containers on the Compose-managed internal network.

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
- **Strong password.** `DB_PASSWORD` must be cryptographically random; the
  `prod` profile doesn't validate it explicitly, but exposing a weak one
  defeats the point of network isolation.
- **Backups encrypted at rest (optional but recommended).** The nightly
  `pg_dump` under `./backups/` is plain `.dump`. For anything sensitive,
  encrypt before offsite copy:
  ```bash
  age -r <your-public-key> backups/wealthview_2026-04-22_03-00.dump \
    > backups/wealthview_2026-04-22_03-00.dump.age
  ```
  or use cloud-side encryption if you're syncing to S3/Backblaze/etc.

---

## Application-level security

The items below are all already implemented; they are listed here so you
know what is actually protecting your deployment.

### Authentication

- Short-lived access tokens (15 minute default) + refresh tokens stored
  per-user. Tokens are HMAC-SHA256 signed; every token carries `iss` and
  `aud` claims that are checked on every request.
- Refresh tokens are bound to a per-user `tokenGeneration` counter. Logging
  out or triggering a password reset bumps the counter and invalidates every
  previously-issued refresh token. The counter field uses JPA `@Version`
  optimistic locking so concurrent refresh calls can't double-issue tokens.
- Password reset flows always hash the password with BCrypt (cost 12).
- The `/api/v1/auth/register` endpoint validates the invite code **before**
  it queries for email uniqueness. This eliminates a timing / response-code
  difference that could otherwise have been used to enumerate registered
  emails.
- Login attempts are rate-limited per client IP. The client IP is only
  trusted from `X-Forwarded-For` when the request arrives from a peer in
  `APP_RATE_LIMIT_TRUSTED_PROXIES` — set this to your nginx / cloudflared
  host IP.

### Tenant isolation

Every service method that reads or writes business data filters by
`tenant_id`. The `tenant_id` is taken from the authenticated user's JWT
via `SecurityContextHolder` — **never** from a request parameter, path
variable, or body field. A cross-tenant read would require forging a JWT
for another tenant, which the signing key prevents.

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
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'` |
| `Permissions-Policy` | `geolocation=(), microphone=(), camera=(), payment=()` |

`Permissions-Policy` is new as of the 2026-04-22 security pass. It tells the
browser to refuse every script on the page (ours or injected) access to
geolocation, microphone, camera, and payment APIs — features the app does
not use at all.

If you also add these at the nginx layer (see [tls-and-nginx.md](tls-and-nginx.md)),
that's fine — duplicates are harmless.

### Outbound HTTP timeouts

Every outbound HTTP client (Finnhub, Zillow, etc.) has connect and read
timeouts configured (5s connect, 15s read). A hung upstream can no longer
pin a servlet thread indefinitely.

### API key transmission

The Finnhub API key is sent as an `X-Finnhub-Token` HTTP header, never as a
query string. This keeps the key out of access logs, proxy logs, and
error-reporting breadcrumbs.

### CSV injection

Exported CSV files (audit log export, holdings export, etc.) are neutralized
against formula-injection attacks — any cell value beginning with `=`, `+`,
`-`, `@`, `\t`, or `\r` is prefixed with a single quote so Excel / Numbers /
LibreOffice Calc don't execute it as a formula.

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
  peers in `APP_RATE_LIMIT_TRUSTED_PROXIES`. Set this correctly or audit
  records lose their value.

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

The super-admin UI has an Audit Log page (`/admin/audit-log`) showing every
tenant / user / export mutation. Skim it periodically for entries that
don't match legitimate activity.

### Rotate credentials periodically

- Rotate `JWT_SECRET` at least annually. All users will be forced to log in
  again after the rotation.
- Rotate `DB_PASSWORD` when team membership changes.
- Rotate `SUPER_ADMIN_PASSWORD` whenever someone with access leaves.

To rotate:

1. Edit `.env`.
2. `docker compose -f docker-compose.prod.yml up -d app` (for app-level
   secrets) or `docker compose -f docker-compose.prod.yml up -d` (for DB
   password — requires recreating both `app` and `db` with the new value).

---

## Security checklist

Use this to verify your deployment:

- [ ] `.env` has `chmod 600` permissions
- [ ] `.env` is listed in `.gitignore`
- [ ] `DB_PASSWORD`, `JWT_SECRET`, `SUPER_ADMIN_PASSWORD` all randomly generated
- [ ] `JWT_SECRET` is 32+ characters
- [ ] `CORS_ORIGIN` is set to `https://your-domain`
- [ ] `APP_RATE_LIMIT_TRUSTED_PROXIES` set to the edge proxy's peer IP
- [ ] App starts cleanly on the `prod` profile (no `ProductionConfigValidator` errors)
- [ ] Firewall allows only the ports you need (22, optionally 80 + 443)
- [ ] SSH password auth is disabled; root login disabled
- [ ] Automatic host-OS security updates enabled (`unattended-upgrades`)
- [ ] `db` container has no published ports in production
- [ ] `app` container published port is bound to loopback or firewalled
- [ ] Container runs as `wv` (non-root) — verified via `docker exec ... id`
- [ ] HEALTHCHECK shows `(healthy)` in `docker compose ps`
- [ ] TLS end-to-end: `curl -s https://your-domain/actuator/health` returns `{"status":"UP"}`
- [ ] Security headers present: `curl -sI https://your-domain | grep -Ei 'strict-transport|permissions-policy|x-frame-options|x-content-type'`
- [ ] Nightly backups running: `ls -la backups/` shows recent dumps
- [ ] You have tested a restore at least once in a non-prod environment

---

## Related guides

- [Production Setup](production-setup.md) — full deployment walkthrough.
- [TLS and Nginx](tls-and-nginx.md) — host-managed TLS.
- [Cloudflared Deployment](cloudflared.md) — Cloudflare Tunnel TLS.
- [Upgrading](upgrading.md) — keep the app up to date.
