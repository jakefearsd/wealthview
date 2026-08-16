# WealthView Deployment Guide

> **This page is an index, not a manual.** It used to be a standalone
> end-to-end guide that duplicated the pages under `docs/deployment/`, and the
> two drifted apart. The detailed instructions now live in one place each —
> follow the links below. What remains here is the short orientation: what you
> are deploying, where each answer lives, and the handful of facts worth
> stating once.

---

## 1. What you are deploying

WealthView is a self-hosted, multi-tenant personal-finance application
(investments, rental properties, retirement projections).

One Docker image bundles everything. The multi-stage `Dockerfile` at the repo
root builds the Vite/React frontend on `node:24-alpine`, builds the Maven
backend on `maven:3.9-eclipse-temurin-25`, and ships a runtime layer of
`eclipse-temurin:25-jre-alpine` that serves both the API and the built SPA from
a single Spring Boot process on container port 8080, as the non-root user `wv`.
All three base images are pinned by digest. PostgreSQL 16 (also digest-pinned)
holds all state; Flyway runs schema migrations at app start, so there is never
a manual SQL step.

**Dev builds that image locally; production pulls it.** CI publishes
`ghcr.io/<owner>/wealthview:<version>` (linux/amd64) on every `v*` tag, so a
production host needs Docker, the compose file, the env file and registry
access — no source tree and no JDK.

Stack: Java 25, Spring Boot 4.1, Hibernate 7, PostgreSQL 16, React 19, Vite,
PostgreSQL JDBC + Flyway 13. Latest released version: **v1.2.6**.

Three Compose files live at the repo root:

| File | Services | Profile | Notes |
|---|---|---|---|
| `docker-compose.yml` | `db`, `app` | `docker` | Local evaluation. Seeds a demo tenant. DB published on host **5433**, app on host **80** (both hardcoded — `APP_PORT` is ignored here). |
| `docker-compose.prod.yml` | `db`, `app`, `backup` | `prod` | Production. No seed data, strict startup validation, `restart: unless-stopped`. App on `${APP_PORT:-80}`; DB port **not** published. Requires `WEALTHVIEW_VERSION`. The `app` service has **no `build:` key** — it pulls the CI-published image. |
| `docker-compose.observability.yml` | `prometheus`, `grafana` | — | Optional overlay for the prod file. Needs `GRAFANA_ADMIN_PASSWORD`. |

All secrets come from a `.env` file next to the compose file. `.env.example` is
the template; `.env` is gitignored.

---

## 2. Which guide do I want?

| I want to… | Read |
|---|---|
| Try it on my laptop in 5 minutes | [`deployment/quickstart.md`](deployment/quickstart.md) |
| Install it properly on a server | [`deployment/production-setup.md`](deployment/production-setup.md) |
| Run day-2 operations (backup, restore, verify, migrate, rotate) | [`deployment/operations.md`](deployment/operations.md) |
| Upgrade or roll back | [`deployment/upgrading.md`](deployment/upgrading.md) |
| Put TLS in front of it with nginx + Let's Encrypt | [`deployment/tls-and-nginx.md`](deployment/tls-and-nginx.md) |
| Expose it with no open ports (Cloudflare Tunnel) | [`deployment/cloudflared.md`](deployment/cloudflared.md) |
| Harden the host and the app | [`deployment/security-hardening.md`](deployment/security-hardening.md) |
| Test the mobile app on a physical Android phone | [`deployment/mobile-android-testing.md`](deployment/mobile-android-testing.md) |
| Look up any environment variable or Spring property | [`reference/configuration.md`](reference/configuration.md) |
| Manage tenants, invite codes, the audit log | [`administration/tenant-and-user-management.md`](administration/tenant-and-user-management.md) |
| Set up offsite backups and verification | [`administration/backups.md`](administration/backups.md) |
| Wire up metrics, logs, alerting | [`administration/monitoring-and-logging.md`](administration/monitoring-and-logging.md) and [`OBSERVABILITY.md`](OBSERVABILITY.md) |
| Tune Postgres, JVM heap, autovacuum | [`administration/maintenance.md`](administration/maintenance.md) |
| Diagnose a problem | [`administration/troubleshooting.md`](administration/troubleshooting.md) |
| Understand the architecture, data model, or API | [`reference/`](reference/) |

---

## 3. Facts worth stating once

### `./wv` is the command surface

Every routine operation goes through one entry point. The dispatcher is
`bin/wv`, one library per subcommand under `bin/wv-lib/`, and `./wv` at the
repo root is a thin shim. It runs from a source checkout with no configuration
at all, and system-wide on a server via `/etc/wealthview/wv.conf`.

```
up  down  restart  status  logs  psql
backup  backups (list-backups)  restore  verify
update  rollback
migrate-out  migrate-in
rotate-secret  config-check  help
```

Global flags: `--config FILE`, `--host USER@HOST`. `wv help` is the full
operator man page; `wv <subcommand> --help` is the per-subcommand detail.

Mode is auto-detected: `WEALTHVIEW_VERSION` set in `.env` selects
`docker-compose.prod.yml`, unset selects `docker-compose.yml`.

### Required environment variables

Four secrets are mandatory in every profile. Compose uses `${VAR:?message}` and
refuses to start without them; `./wv` additionally refuses while any is still
literally `CHANGE_ME`.

| Variable | Generate with |
|---|---|
| `DB_PASSWORD` | `openssl rand -base64 24` |
| `JWT_SECRET` (32+ chars) | `openssl rand -base64 48` |
| `SUPER_ADMIN_PASSWORD` | `openssl rand -base64 18` |
| `MFA_ENCRYPTION_KEY` (base64 32 bytes) | `openssl rand -base64 32` |

Production adds two more: `WEALTHVIEW_VERSION` (the release to pull — **never**
`latest`, which defeats `wv rollback`) and `CORS_ORIGIN` (must be a non-empty
`https://` value).

Optional: `WEALTHVIEW_IMAGE` (registry/repository holding the app image;
defaults to the upstream GHCR package — set it only for a fork, a private
mirror, or an air-gapped registry), `FINNHUB_API_KEY`, `ZILLOW_ENABLED`, `APP_PORT`,
`BACKUP_RETENTION_DAYS`, `BACKUP_ENCRYPTION_RECIPIENT`,
`BACKUP_ENCRYPTION_KEY_FILE`, `BACKUP_REMOTE_DEST`. Full table with YAML
property names and defaults: [`reference/configuration.md`](reference/configuration.md).

`ProductionConfigValidator` (active on `prod` and `docker`) enforces the rest
at startup: JWT length, no known development defaults, nothing beginning
`LOCAL_DEV_`, a valid `CORS_ORIGIN`, and no `prod` profile combined with `dev`
or `docker`.

### Seed data by profile

| Profile | Seeded accounts | Initializer |
|---|---|---|
| `prod` | `admin@wealthview.local` only | `SuperAdminInitializer` |
| `docker` | super admin + `demo@wealthview.local` / `demo123` and its sample data | `SampleDataInitializer` |
| `dev` | the above plus `demo-admin@wealthview.local` and `demo-member@wealthview.local`, both `demo123` | `DevDataInitializer` |

### Ports

| Port | What |
|---|---|
| Container `8080` | Spring Boot. Published as host `${APP_PORT:-80}` by the prod compose file, and as host `80` unconditionally by the dev one. |
| Container `5432` | PostgreSQL. Published as host **5433** by the dev compose file (so it doesn't collide with a native PostgreSQL on 5432); **not published at all** by the prod file. |
| 9090 / 3000 | Prometheus / Grafana, only with the observability overlay. Overridable via `PROMETHEUS_PORT` / `GRAFANA_PORT`. Never expose these publicly. |

### CI publishes; it does not deploy

Every workflow in `.github/workflows/` (`backend-verify`, `web`, `shared`,
`mobile`, `scripts`, `secret-scan`) triggers **only** on `push:` of a `v*` tag,
plus a manual `workflow_dispatch`. Nothing runs on ordinary pushes or pull
requests. `backend-verify.yml` is a three-job chain — unit tests and quality
gates, then the full `wealthview-app` Testcontainers integration suite, then
build-and-publish.

The third job pushes the release image to GHCR as
`ghcr.io/<owner>/wealthview:<version>` and `:latest`, then cuts a **GitHub
Release** whose notes come from the matching `## [<version>]` section of
`CHANGELOG.md`. Publishing happens only for `refs/tags/v*`; a manual
`workflow_dispatch` builds the image to prove it assembles but never pushes.
Platform is `linux/amd64` only — the Dockerfile compiles the whole Maven
backend in-stage, so an emulated arm64 build would take 30-60+ minutes.

**There is still no auto-deploy.** A GitHub-hosted runner cannot reach your
server. You pin `WEALTHVIEW_VERSION` in the env file and run `./wv update`,
which pulls the published image.

> **The first CI push creates the GHCR package as private**, even for a public
> repo. Make it public in the repo's Packages settings, or give the server a
> `docker login ghcr.io` with a `read:packages` token — otherwise the first
> deploy after this change fails on an unauthorized pull. Details in
> [`deployment/upgrading.md`](deployment/upgrading.md#before-your-first-pull-registry-access).

### Health and metrics

| Endpoint | Auth |
|---|---|
| `GET /actuator/health` | Public — used by the container HEALTHCHECK and by `wv`'s health wait. |
| `GET /actuator/prometheus`, `GET /actuator/metrics` | `SUPER_ADMIN` role by default. Setting `app.observability.anonymous-metrics=true` (env `APP_OBSERVABILITY_ANONYMOUS_METRICS`) serves **these two paths with no authentication at all** — that is how the bundled Prometheus overlay scrapes, since the app accepts only a JWT and never HTTP Basic. Default is `false`; only switch it on where the app's port is not internet-reachable and `/actuator` is blocked at the proxy. |
| Everything else under `/actuator/**` | `SUPER_ADMIN` role. |

Only `health`, `prometheus`, and `metrics` are exposed over HTTP at all
(`management.endpoints.web.exposure.include`).

### Scheduled jobs

| Job | Schedule | Requires |
|---|---|---|
| Finnhub price sync | `app.finnhub.sync-cron`, default `0 0 18 * * MON-FRI` `America/New_York` — one trigger, on `PriceSyncService.syncDailyPrices()` itself | `FINNHUB_API_KEY` (no key, no bean, no job) |
| Stock split sync | `app.stock-splits.sync-cron`, default `0 0 2 * * *` `America/New_York` | `FINNHUB_API_KEY` |
| Zillow valuation sync | `app.zillow.sync-cron`, default `0 0 6 * * SUN` (container timezone) | `ZILLOW_ENABLED=true` |
| Nightly `pg_dump` | `0 3 * * *` in the `backup` container | prod compose only |

---

## 4. The 60-second version

```bash
git clone https://github.com/<your-org>/wealthview.git && cd wealthview
cp .env.example .env && $EDITOR .env    # fill the four required secrets
chmod 600 .env
./wv up
```

Then open <http://localhost> and sign in as `admin@wealthview.local` with your
`SUPER_ADMIN_PASSWORD`, or as `demo@wealthview.local` / `demo123`.

For anything beyond that, start at
[`deployment/production-setup.md`](deployment/production-setup.md).
