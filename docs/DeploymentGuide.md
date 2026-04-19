# WealthView Deployment Guide

This is the single end-to-end guide for installing WealthView on a fresh
machine. It covers everything you need from a bare host to a running
production system, plus day-2 operations.

If you only want a 5-minute local trial, jump to [Quick start (local
evaluation)](#quick-start-local-evaluation). For TLS, automated backups,
and an internet-facing deployment, follow [Production install](#production-install).

For depth on any single topic, the modular docs under `docs/deployment/`
and `docs/administration/` go further than this guide.

---

## 1. What you are deploying

WealthView is a self-hosted, multi-tenant personal-finance application
(investments, rental properties, retirement projections). One Docker
image bundles the Spring Boot 3.5 backend (Java 21) and the Vite-built
React 19 frontend; PostgreSQL 16 holds all state. Flyway runs schema
migrations on each app start, so there is no manual SQL step.

Two compose files live at the repo root:

- `docker-compose.yml` — local evaluation. Two services (`db`, `app`)
  with developer-friendly defaults and the `docker` Spring profile, which
  seeds a demo tenant.
- `docker-compose.prod.yml` — production. Adds the `backup` service
  (daily `pg_dump`), runs the `prod` Spring profile (no demo data, strict
  config validation), and enables container restart policies.

Both pull all secrets from a `.env` file at the repo root. A template
lives in `.env.example`.

---

## 2. Prerequisites

### 2.1 Host

| Requirement | Minimum | Recommended |
|---|---|---|
| CPU | 1 vCPU | 2 vCPU |
| RAM | 1 GB | 2 GB |
| Disk | 10 GB | 20 GB (room for backups) |
| OS | Linux (any modern distro) | Ubuntu 22.04 / 24.04 LTS |

The container heap stays under ~512 MB at typical load. The largest
disk consumer is daily backups (~few MB each, 14-day default retention).

### 2.2 Software

Install on the host before you start:

- Docker Engine 24+ with the Compose v2 plugin
- `git`, `curl`, `openssl`
- (Production only) a domain name pointing at the host, ports 80 and 443
  reachable from the internet for Let's Encrypt

A non-root user in the `docker` group is sufficient for everything
below; nothing requires `sudo` apart from the initial Docker install.

### 2.3 Source

```bash
git clone https://github.com/<your-org>/wealthview.git
cd wealthview
```

Replace `<your-org>` with your GitHub user/org. The build needs the full
working tree because the Dockerfile compiles backend Maven modules and
the frontend Vite bundle in multi-stage builds.

The `infra/backup/` directory (used by `docker-compose.prod.yml` for
nightly `pg_dump`) is part of the repo. Per-host overrides live under
`infra/local/`, which is `.gitignore`d.

---

## 3. Quick start (local evaluation)

The fastest path to a running app on your laptop. Uses the `docker`
profile, which seeds a demo tenant and a demo user — no production
hardening.

```bash
cd wealthview

# Generate a .env with random secrets
cat > .env <<EOF
DB_PASSWORD=$(openssl rand -base64 24)
JWT_SECRET=$(openssl rand -base64 32)
SUPER_ADMIN_PASSWORD=$(openssl rand -base64 16)
EOF

docker compose up --build -d
docker compose logs -f app   # wait for "Started WealthviewApp"
```

Then browse to <http://localhost> and log in as either:

| Account | Email | Password |
|---|---|---|
| Super admin | `admin@wealthview.local` | from `.env` (`SUPER_ADMIN_PASSWORD`) |
| Demo tenant member | `demo@wealthview.local` | `demo123` (hardcoded in seeder) |

Stop with `docker compose down`. The `pgdata` volume persists between
runs.

---

## 4. Production install

This section walks through a real internet-facing deployment with TLS,
strict secrets, and automated backups.

### 4.1 Provision the host and DNS

1. Spin up a Linux VM (1 GB RAM minimum) with Docker installed.
2. Open inbound TCP 22 (SSH), 80, and 443 in your firewall.
3. Point an `A` record (e.g. `wealthview.example.com`) at the host's
   public IP and confirm it resolves before continuing.

If you are not exposing this to the public internet (e.g. you run it
behind a Cloudflare Tunnel or on a private Tailscale network), skip the
firewall and DNS work and treat the app's HTTP port as the public
surface — but still set the secrets below.

### 4.2 Generate secrets

Production refuses to start if any required secret is missing or matches
a known development default. Generate strong values once and keep them
in a password manager.

```bash
cd wealthview
cp .env.example .env
```

Fill in `.env`:

```dotenv
DB_PASSWORD=<32+ random chars>
JWT_SECRET=<32+ random chars, base64 fine>
SUPER_ADMIN_PASSWORD=<16+ random chars>
FINNHUB_API_KEY=<your key, or leave blank>
ZILLOW_ENABLED=false
CORS_ORIGIN=https://wealthview.example.com
APP_PORT=80
BACKUP_RETENTION_DAYS=14
```

The `prod` compose file refuses to start if `DB_PASSWORD`, `JWT_SECRET`,
or `SUPER_ADMIN_PASSWORD` is unset (the `${VAR:?...}` syntax in
`docker-compose.yml`). `ProductionConfigValidator` then re-validates the
JWT secret length and rejects all known development defaults at app
startup.

Helpers:

```bash
openssl rand -base64 32   # for DB_PASSWORD / JWT_SECRET
openssl rand -base64 16   # for SUPER_ADMIN_PASSWORD
```

`chmod 600 .env` after editing.

### 4.3 Start the stack

```bash
docker compose -f docker-compose.prod.yml up --build -d
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app
```

You should see Flyway run all migrations through `V055`, then
`Started WealthviewApp in N seconds`. The first build takes ~2-3 minutes
on a 2 vCPU box; subsequent rebuilds are faster thanks to layer caching.

Verify:

```bash
curl http://localhost/actuator/health        # {"status":"UP"...}
curl -I http://localhost/                    # 200 OK, serves index.html
```

### 4.4 TLS (Let's Encrypt + nginx)

The production compose does not include nginx by default. The most
reliable pattern is to run nginx + certbot on the host (or in a separate
compose file) and reverse-proxy to the WealthView container on
`localhost:${APP_PORT}`. The full walkthrough — including a complete
nginx config, certbot renewal loop, and HTTP→HTTPS redirect — lives in
[`docs/deployment/tls-and-nginx.md`](deployment/tls-and-nginx.md).

If you are using Cloudflare Tunnel or another edge proxy that
terminates TLS for you, leave `CORS_ORIGIN` set to the public URL and
skip this section entirely.

### 4.5 First login

Browse to `https://wealthview.example.com/login` and sign in as
`admin@wealthview.local` with the `SUPER_ADMIN_PASSWORD` you put in
`.env`. From the admin console you can:

1. Create your first tenant (Admin → Tenants → New).
2. Issue an invite code for that tenant (valid for 7 days).
3. Register a user account against that invite code from the public
   `/register` page — that user becomes the first non-admin member.

Detailed guidance: [`docs/administration/tenant-and-user-management.md`](administration/tenant-and-user-management.md).

---

## 5. Configuration reference

Every variable WealthView reads, where it comes from, and whether
production requires it.

### 5.1 Required in production

| Variable | Purpose | Notes |
|---|---|---|
| `DB_PASSWORD` | PostgreSQL password for the `wv_app` user | 24+ chars; passed to both `db` and `app` services. |
| `JWT_SECRET` | HMAC-SHA256 signing key for access/refresh tokens | Minimum 32 chars. `ProductionConfigValidator` rejects both historical defaults (`default-secret-key-...` and `production-secret-key-...`). |
| `SUPER_ADMIN_PASSWORD` | Initial password for `admin@wealthview.local` | Created by `SuperAdminInitializer` on first start. `ProductionConfigValidator` also rejects the dev demo passwords (`admin123`, `demo123`, `DevPass123!`). Change immediately after first login. |

### 5.2 Optional / tunable

| Variable | Default | Purpose |
|---|---|---|
| `FINNHUB_API_KEY` | _(empty)_ | Live stock-price sync. When blank the price job is disabled and existing seeded prices are used. Sign up at [finnhub.io](https://finnhub.io) for a free tier. |
| `CORS_ORIGIN` | _(empty)_ | Allowed origin for `/api/*`. Set to your public URL when the SPA is served same-origin via nginx; required only when frontend and backend live on different hosts. |
| `APP_PORT` | `80` | Host port mapped to the container's `8080`. |
| `BACKUP_RETENTION_DAYS` | `14` | Days of `pg_dump` files to keep in `./backups/`. |
| `ZILLOW_ENABLED` | `false` | Toggle the weekly Zillow property valuation sync. Wired through both compose files to Spring's `app.zillow.enabled`. |
| `APP_RATE_LIMIT_TRUSTED_PROXIES` | _(empty)_ | Comma-separated peer IPs whose `X-Forwarded-For` header should be trusted for rate limiting and login audit. Set to your reverse-proxy / Cloudflare Tunnel egress IP when running behind one. Leave empty when the app is internet-facing without a proxy. |
| `SPRING_PROFILES_ACTIVE` | set by compose | `prod` for production, `docker` for local eval, `dev` for `mvn spring-boot:run`. |

### 5.3 Spring profiles

| Profile | Activates |
|---|---|
| `dev` | DEBUG logging, demo + admin seed users (`demo-admin`, `demo-member`), formatted SQL, CORS allows `http://localhost:5173`. |
| `docker` | Demo tenant + `demo@wealthview.local` user, JSON logs, CORS allows `http://localhost`. |
| `prod` | No seed data beyond the super admin, JSON logs, `ProductionConfigValidator` runs at startup and aborts on weak secrets, slow-query threshold raised to 500 ms. |
| `it` | Integration tests against Testcontainers PostgreSQL. |

You should never run a public deployment with anything other than
`prod`.

### 5.4 What gets seeded

| Profile | User | Password | Role |
|---|---|---|---|
| All | `admin@wealthview.local` | `${SUPER_ADMIN_PASSWORD}` | Super admin |
| `docker` | `demo@wealthview.local` | `demo123` | Member of "Demo Family" tenant |
| `dev` | `demo-admin@wealthview.local` | `demo123` | Tenant admin |
| `dev` | `demo-member@wealthview.local` | `demo123` | Tenant member |

The `dev` and `docker` initializers also create sample accounts,
holdings, transactions, and properties so you can click around without
importing data.

---

## 6. Day-2 operations

### 6.1 Backups

The `backup` service in `docker-compose.prod.yml` runs `pg_dump -Fc`
nightly at 03:00 UTC and writes timestamped `.dump` files to
`./backups/` on the host. Older files are pruned per
`BACKUP_RETENTION_DAYS`.

Manual backup at any time:

```bash
docker compose -f docker-compose.prod.yml exec backup /backup.sh
ls -la backups/
```

For development databases, the `./dev-backup.sh` and `./dev-restore.sh`
scripts at the repo root work against the local `db` container without
touching production volumes.

Full lifecycle (offsite copy, verification, retention strategy):
[`docs/administration/backups.md`](administration/backups.md).

### 6.2 Restore

```bash
./infra/backup/restore.sh backups/wealthview_2026-04-19_03-00.dump
```

The script stops the `app` container, runs `pg_restore --clean --if-exists`,
and restarts the app. It prompts before destroying the current database.

### 6.3 Upgrades

```bash
cd wealthview
git pull
docker compose -f docker-compose.prod.yml up --build -d
docker compose -f docker-compose.prod.yml logs -f app
```

Flyway runs new migrations automatically. Migrations are immutable —
once a `V<NNN>__*.sql` file is in `main`, it is never edited. If a new
release fails on startup, the most reliable rollback is:

```bash
docker compose -f docker-compose.prod.yml down
git checkout <previous-tag>
./infra/backup/restore.sh backups/<latest-good>.dump
docker compose -f docker-compose.prod.yml up --build -d
```

The full pre/post-upgrade checklist is in
[`docs/deployment/upgrading.md`](deployment/upgrading.md).

### 6.4 Logs

All containers log to stdout; Docker captures them.

```bash
docker compose -f docker-compose.prod.yml logs -f app
docker compose -f docker-compose.prod.yml logs --tail=200 db
```

Production uses structured JSON logging. Pipe to `jq` for readability:

```bash
docker compose -f docker-compose.prod.yml logs app | jq -r 'select(.level=="ERROR") | "\(.timestamp) \(.message)"'
```

Configure Docker daemon log rotation in `/etc/docker/daemon.json` to
prevent the journal from filling the disk:

```json
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "5" }
}
```

### 6.5 Health and metrics

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /actuator/health` | Public | Container healthcheck and external probes. |
| `GET /actuator/prometheus` | Super admin only | Prometheus scrape endpoint. Use a long-lived super-admin JWT in your scrape config's `Authorization: Bearer ...` header. |
| `GET /actuator/metrics`, `/actuator/info`, etc. | Super admin only | Internal observability. |

The Docker `healthcheck` block on the `app` service polls
`/actuator/health` every 30 s; a failure marks the container unhealthy
and your monitoring stack can react.

### 6.6 Stock prices and Zillow

- **Finnhub price sync** runs weekdays at 16:30 America/New_York. With
  no `FINNHUB_API_KEY` the bean is not created and the seeded prices
  remain static.
- **Zillow valuation sync** runs Sundays at 06:00 UTC and is **off by
  default**. To enable it, set `ZILLOW_ENABLED=true` in `.env`. Both
  compose files map this to `APP_ZILLOW_ENABLED`, which Spring's
  relaxed binding resolves to `app.zillow.enabled`.

---

## 7. Troubleshooting

| Symptom | First check |
|---|---|
| App container won't start | `docker compose logs app`. The most common cause is `JWT_SECRET` or `SUPER_ADMIN_PASSWORD` missing — `ProductionConfigValidator` aborts startup with a clear message. |
| `relation "..." does not exist` | A migration failed to apply. Inspect the `flyway_schema_history` table; the failed migration row will show `success=false`. |
| 502 / connection refused via nginx | Confirm `docker compose ps` shows the app container `healthy`, then check that nginx upstreams `localhost:${APP_PORT}` (not the container hostname). |
| TLS cert renewal failing | Port 80 must reach the host from the public internet for the `http-01` challenge. See `docs/deployment/tls-and-nginx.md`. |
| All login activity rows show a Docker bridge / proxy IP | Set `APP_RATE_LIMIT_TRUSTED_PROXIES` to the immediate peer IP (e.g. your nginx container or Cloudflare Tunnel egress). The login endpoint and the rate limiter only honor `X-Forwarded-For` when the request arrives from a trusted peer. |

The full diagnostic catalog (15+ scenarios with copy-paste commands) is
in [`docs/administration/troubleshooting.md`](administration/troubleshooting.md).

---

## 8. Going further

| Need | Read |
|---|---|
| TLS certificate provisioning end-to-end | [`docs/deployment/tls-and-nginx.md`](deployment/tls-and-nginx.md) |
| Hardening checklist (firewall, SSH, container isolation) | [`docs/deployment/security-hardening.md`](deployment/security-hardening.md) |
| Backup verification and offsite replication | [`docs/administration/backups.md`](administration/backups.md) |
| Tenant lifecycle, invite codes, audit log | [`docs/administration/tenant-and-user-management.md`](administration/tenant-and-user-management.md) |
| Postgres tuning, JVM heap, autovacuum | [`docs/administration/maintenance.md`](administration/maintenance.md) |
| Slow-query logging and metric tags | [`docs/administration/monitoring-and-logging.md`](administration/monitoring-and-logging.md) |
| Architecture, data model, API surface | [`docs/reference/`](reference/) |

---

## Appendix A — File and port map

| Path / port | Purpose |
|---|---|
| `./.env` | All runtime secrets. `chmod 600`. Never commit. |
| `./backups/` | `pg_dump` output, host-bind-mounted from the `backup` container. |
| `./infra/backup/` | `Dockerfile` + `backup.sh` + `restore.sh` + `crontab` for the backup container. Tracked in the repo. |
| `pgdata` (named volume) | PostgreSQL data directory. Survives `docker compose down`; destroyed by `docker compose down -v`. |
| Container `8080` | Spring Boot HTTP listener. Mapped to host `${APP_PORT}` (default 80). |
| Container `5432` | PostgreSQL. Not published to the host by default. |
