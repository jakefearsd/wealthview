[← Back to README](../../README.md)

# Configuration Reference

All configuration is via environment variables passed through the compose files (`docker-compose.yml`, `docker-compose.prod.yml`, `docker-compose.observability.yml`) or set in the shell for development. `.env.example` is the authoritative list of variables; copy it to `.env` (gitignored) and fill in real values. `direnv` auto-loads `.env` via the committed `.envrc`.

Backend defaults live in `backend/wealthview-app/src/main/resources/application.yml` with per-profile overlays (`application-dev.yml`, `application-docker.yml`, `application-prod.yml`, `application-loadtest.yml`) and a test-only `application-it.yml` under `src/test/resources`.

**Fail-loud vs. sentinel fallbacks.** The base `application.yml` references `${DB_PASSWORD}`, `${JWT_SECRET}` and `${MFA_ENCRYPTION_KEY}` with **no fallback** — the application refuses to start if they are unset. Only the `dev` profile supplies `LOCAL_DEV_*` sentinels, and only `application-it.yml` hardcodes `INTEGRATION_TEST_*` values, so `mvn test` works without an environment. On the `prod` and `docker` profiles, `ProductionConfigValidator` additionally rejects blank, too-short, known-default and `LOCAL_DEV_*`-prefixed values at startup.

## Core Settings

| Variable | YAML property | Required | Default | Description |
|----------|---------------|----------|---------|-------------|
| `DB_PASSWORD` | `spring.datasource.password` | Yes | none (dev: `LOCAL_DEV_NOT_A_REAL_PASSWORD_OVERRIDE_VIA_ENV`) | PostgreSQL password, used by both the database container and the application |
| `JWT_SECRET` | `app.jwt.secret` | Yes | none (dev sentinel only) | HMAC-SHA256 signing key for JWTs. **Must be at least 32 characters.** |
| `MFA_ENCRYPTION_KEY` | `app.mfa.encryption-key` | Yes | none (dev sentinel only) | Base64-encoded 32-byte AES-256-GCM key that encrypts TOTP shared secrets at rest. Generate with `openssl rand -base64 32`. |
| `SUPER_ADMIN_PASSWORD` | `app.super-admin.password` | Yes on `dev`/`docker`/`prod` | none (dev sentinel only) | Password for the auto-created `admin@wealthview.local` account (`SuperAdminInitializer`) |
| `CORS_ORIGIN` | `app.cors.allowed-origins` | Yes on `prod` | none on `prod`; `http://localhost` on `docker`; `http://localhost:5173` on `dev` | Comma-separated allowed origins. Under `prod` every entry must start with `https://` or startup aborts. |
| `FINNHUB_API_KEY` | `app.finnhub.api-key` | No | *(empty)* | Finnhub API key. When empty, `PriceSyncService` and the stock-split sync beans are not created at all. |
| `ZILLOW_ENABLED` | `app.zillow.enabled` (compose passes `APP_ZILLOW_ENABLED`) | No | `false` (`dev` profile sets `true`) | Enables Zillow valuation scraping and the weekly sync job |
| `WEALTHVIEW_VERSION` | *(compose only)* | Yes for prod | none | The release tag `docker-compose.prod.yml` **pulls** from the registry (production has no `build:` for `app`). Setting it also flips `./wv` into prod mode. Never `latest` — CI publishes that tag, but deploying it defeats `wv rollback`, which recovers by re-pinning the tag that was running before the update. |
| `WEALTHVIEW_IMAGE` | *(compose only)* | No | `ghcr.io/jakefearsd/wealthview` | Registry/repository half of the app image reference in `docker-compose.prod.yml`. Set it only to use a fork, a private mirror, or an air-gapped registry. `wv rollback` re-pins this alongside `WEALTHVIEW_VERSION`, so a mirrored deployment rolls back to the mirror. |
| `APP_PORT` | *(compose only)* | No | `80` | Host port bound to the container's 8080 in `docker-compose.prod.yml` |
| `BACKUP_RETENTION_DAYS` | *(compose only)* | No | `14` | Retention for the `backup` sidecar in `docker-compose.prod.yml` |

Standard Spring relaxed binding applies, so any property below can be overridden by its upper-snake-case env name. The compose files use this for `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_PROFILES_ACTIVE` and `APP_ZILLOW_ENABLED`.

## Database & Connection Pool

Set in `application.yml`; usually overridden only by the compose `SPRING_DATASOURCE_*` variables.

| Setting | Default | Description |
|---------|---------|-------------|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/wealthview` | Local dev points at the compose DB on 5433; containers get `jdbc:postgresql://db:5432/wealthview` |
| `spring.datasource.username` | `wv_app` | Application database role |
| `spring.datasource.hikari.maximum-pool-size` | `20` | Max pooled connections |
| `spring.datasource.hikari.minimum-idle` | `5` | Idle connections kept warm |
| `spring.datasource.hikari.connection-timeout` | `10000` | Milliseconds to wait for a connection |
| `spring.datasource.hikari.idle-timeout` | `300000` | Milliseconds before an idle connection is retired |
| `spring.datasource.hikari.max-lifetime` | `600000` | Maximum connection lifetime in milliseconds |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Schema is owned by Flyway; Hibernate only validates it |
| `spring.jpa.open-in-view` | `false` | No session-in-view; services own their transactions |
| `spring.flyway.locations` | `classpath:db/migration` | Migrations ship in `wealthview-persistence` |

## Server & Requests

| Setting | Default | Description |
|---------|---------|-------------|
| `server.port` | `8080` | Container port (published as 80 by both compose files) |
| `server.shutdown` | `graceful` | In-flight requests drain before exit; send SIGTERM, not SIGKILL |
| `spring.lifecycle.timeout-per-shutdown-phase` | `25s` | Cap on the graceful-shutdown drain |
| `spring.servlet.multipart.max-file-size` | `10MB` | Upload limit for CSV/OFX imports |
| `spring.servlet.multipart.max-request-size` | `10MB` | Total multipart request limit |

## JWT & Cookies

| Setting | Default | Description |
|---------|---------|-------------|
| `app.jwt.access-token-expiration` | `3600000` (1 hour) | Access token lifetime in milliseconds |
| `app.jwt.refresh-token-expiration` | `86400000` (24 hours) | Refresh token lifetime in milliseconds |
| `app.jwt.issuer` | `wealthview-api` | `iss` claim, validated on every token |
| `app.jwt.audience` | `wealthview-web` | `aud` claim, validated on every token |
| `app.cookie.secure` | `true` | Adds the `Secure` flag to the auth and CSRF cookies. The `dev`, `docker` and `it` profiles set `false` so plain-HTTP local development works. |

## Rate Limiting

`RateLimitFilter` is active unless explicitly disabled. Limits are compile-time constants, not properties.

| Setting | Default | Description |
|---------|---------|-------------|
| `app.rate-limit.enabled` | *(on when unset)* | Set `false` to remove the filter entirely. `application-it.yml` does this so integration tests aren't throttled. |
| `app.rate-limit.trusted-proxies` | *(empty list)* | Proxy IPs whose `X-Forwarded-For` header `ClientIpResolver` will trust |

Fixed limits: 300 requests/minute per authenticated principal on `/api/**`, 60 requests/minute per IP on `/api/v1/auth/**`. `SUPER_ADMIN` requests bypass the filter. Responses carry `X-RateLimit-Limit`, `X-RateLimit-Remaining` and `X-RateLimit-Reset`; over-limit requests get `429` with the standard error envelope.

## Finnhub Settings

The whole Finnhub stack is conditional on a non-empty `app.finnhub.api-key`.

| Setting | Default | Description |
|---------|---------|-------------|
| `app.finnhub.api-key` | *(empty)* | Finnhub API key |
| `app.finnhub.base-url` | `https://finnhub.io` | Finnhub API base URL |
| `app.finnhub.rate-limit-ms` | `1100` | Delay between API calls (free tier: 60 req/min) |
| `app.finnhub.sync-cron` | `0 0 18 * * MON-FRI` | Cron for `PriceSyncService.syncDailyPrices()`, `America/New_York`. This is the only trigger for the daily sweep — `PriceSyncSchedulingTest` fails the build if a second scheduled bean starts firing it too. |

## Yahoo Finance Settings

Used by the admin price-backfill endpoints under `/api/v1/admin/prices/yahoo/*`. No API key required.

| Setting | Default | Description |
|---------|---------|-------------|
| `app.yahoo.base-url` | `https://query1.finance.yahoo.com` | Yahoo chart API base URL |
| `app.yahoo.rate-limit-ms` | `500` | Delay between requests |

## Stock Split Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `app.stock-splits.sync-cron` | `0 0 2 * * *` | Daily split detection sync, `America/New_York`. Requires a Finnhub API key (the sync bean is conditional on the split-detection client). |
| `app.stock-splits.adjust-historical-prices` | `true` | Also split-adjust stored historical prices when a split is applied |
| `app.stock-splits.backfill-auto-run` | `true` | Run `StockSplitBackfillRunner` asynchronously on startup. `application-it.yml` sets `false` so integration tests can drive it explicitly. |

## Zillow Settings

`ZillowConfig` (and therefore the scraper client) only exists when `app.zillow.enabled` is `true`.

| Setting | Default | Description |
|---------|---------|-------------|
| `app.zillow.enabled` | `false` (`dev`: `true`) | Enable/disable Zillow valuation scraping |
| `app.zillow.timeout-ms` | `10000` | HTTP timeout for Zillow requests |
| `app.zillow.rate-limit-ms` | `5000` | Delay between scrape requests |
| `app.zillow.sync-cron` | `0 0 6 * * SUN` | Cron schedule for automatic valuation sync (Sunday 6 AM, server timezone) |

## Observability

Actuator and metrics are on by default; tracing is opt-in. See [Observability](../OBSERVABILITY.md).

| Setting / Variable | Default | Description |
|--------------------|---------|-------------|
| `management.endpoints.web.exposure.include` | `health,prometheus,metrics` | Exposed actuator endpoints (`it` also exposes `info`) |
| `management.endpoint.health.show-details` | `when-authorized` | Detailed health only for authenticated callers |
| `management.prometheus.metrics.export.enabled` | `true` | Serves `/actuator/prometheus` |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | `true` | HTTP latency buckets, set in `application.yml` for every profile. The Grafana latency panel and the `WealthViewHttpP99Latency` alert both need them; costs per-URI cardinality |
| `app.observability.anonymous-metrics` / `APP_OBSERVABILITY_ANONYMOUS_METRICS` | `false` | **Security-relevant.** When `true`, `/actuator/prometheus` and `/actuator/metrics` are served with **no authentication** so an in-network Prometheus can scrape them (the app accepts a JWT only — HTTP Basic was never enabled, so a scraper cannot authenticate). Default `false`, leaving them `SUPER_ADMIN`-only. Only enable it where the app's port is not internet-reachable, and keep `/actuator` blocked at the reverse proxy |
| `MANAGEMENT_TRACING_ENABLED` | `false` | Turns on OpenTelemetry tracing |
| `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | `0.1` | Trace sample rate; set `1.0` while investigating an incident |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP trace endpoint, used only when tracing is enabled |

The optional `docker-compose.observability.yml` stack adds Prometheus and Grafana. It sets
`APP_OBSERVABILITY_ANONYMOUS_METRICS: "true"` on the app service itself, so the scrape needs
no credential — and the two metrics endpoints are unauthenticated on any deployment running
that overlay:

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GRAFANA_ADMIN_PASSWORD` | Yes | none | Grafana admin login; Grafana refuses to start without it |
| `PROMETHEUS_PORT` | No | `9090` | Host port for Prometheus |
| `GRAFANA_PORT` | No | `3000` | Host port for Grafana |

## Backups

Read by `./wv backup` / `restore` / `verify` and the prod `backup` sidecar. See [Operations](../deployment/operations.md).

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `BACKUP_RETENTION_DAYS` | No | `14` | Days of dumps kept by the backup container |
| `BACKUP_ENCRYPTION_RECIPIENT` | For `--encrypt` | none | age public key used by `./wv backup --encrypt` |
| `BACKUP_ENCRYPTION_KEY_FILE` | To read `.age` dumps | none | age identity file used by `./wv restore` / `./wv verify`; chmod 600 |
| `BACKUP_REMOTE_DEST` | For `--remote` | none | Off-host destination (`s3://`, `user@host:/path`, `rsync://`). Best-effort — a failed upload does not fail the backup. |

## `wv` Operator Config

On a production host `bin/wv` is installed to `/usr/local/bin/wv` and driven by a shell-syntax `KEY=VALUE` file — **no secrets in it**; secrets live in `WV_ENV_FILE`. Resolution order: `--config` → `$WV_CONFIG_FILE` → `/etc/wealthview/wv.conf` → `$XDG_CONFIG_HOME/wealthview/wv.conf` → `~/.config/wealthview/wv.conf` → source-tree fallback. Template: `bin/wv.conf.example`.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `WV_COMPOSE_FILE` | Yes (prod) | none | Absolute path to `docker-compose.prod.yml` |
| `WV_ENV_FILE` | Yes (prod) | none | Absolute path to the env file compose reads (0600, root-owned) |
| `WV_BACKUPS_DIR` | Yes (prod) | none | Where dumps are written and `wv backups` looks |
| `WV_COMPOSE_OVERRIDE_FILE` | No | none | Optional second `-f` for site-specific compose tweaks |
| `WV_COMPOSE_PROJECT` | No | `wealthview` | Compose project name |
| `WV_APP_PORT` | No | `80` | Host port used to build the default health-check URL |
| `WV_HEALTH_URL` | No | `http://<host>:<WV_APP_PORT>/actuator/health` | Explicit health-check URL |
| `WV_PREVIOUS_IMAGE_FILE` | No | none | Where `wv update` records the prior image reference (`repository:tag`) for `wv rollback` |
| `WV_HOST` | No | none | Drives docker against a remote daemon over `ssh://` |

## Load-Test Profile

Used only by the isolated stack in `loadtest/docker-compose.loadtest.yml` (`SPRING_PROFILES_ACTIVE=loadtest`). It never points at the dev database.

| Variable | Default | Description |
|----------|---------|-------------|
| `LOADTEST_DB_URL` | `jdbc:postgresql://loadtest-db:5432/wealthview_loadtest` | Dedicated load-test database |
| `LOADTEST_DB_USER` | `wv_loadtest` | Load-test database role |
| `LOADTEST_DB_PASSWORD` | *(throwaway local sentinel)* | Password for the throwaway load-test database |
| `LOADTEST_HIKARI_MAX` | `20` | Connection pool size |
| `LOADTEST_TENANTS` | `25` | Tenants seeded by `LoadTestDataSeeder` |
| `LOADTEST_TXNS_PER_TENANT` | `1500` | Transactions seeded per tenant |
| `LOADTEST_MANIFEST_PATH` | `/loadtest/results/manifest.json` | Where the seeder writes its manifest |
| `LOADTEST_TENANT_PASSWORD` | *(throwaway local sentinel)* | Password given to every seeded tenant user |

## Spring Profiles

No profile is active by default — `dev` must be requested explicitly (`-Dspring-boot.run.profiles=dev`), and the compose files set `SPRING_PROFILES_ACTIVE` for the container stacks.

| Profile | Activated By | Behavior |
|---------|-------------|----------|
| `dev` | `-Dspring-boot.run.profiles=dev` / IDE run config | DEBUG logging for `com.wealthview`, formatted SQL, 100 ms slow-query threshold, CORS allows `localhost:5173`, insecure cookies, Zillow enabled, `LOCAL_DEV_*` secret fallbacks |
| `docker` | `docker-compose.yml` | INFO logging, CORS allows `http://localhost`, insecure cookies, super-admin auto-created, JSON logs, `ProductionConfigValidator` active |
| `prod` | `docker-compose.prod.yml` | INFO logging (`org.springframework` at WARN), CORS from `${CORS_ORIGIN}` (https-only, fail-loud), secure cookies, super-admin auto-created, JSON logs, `ProductionConfigValidator` active |
| `it` | `wealthview-app` Failsafe runs | Integration tests: rate limiting off, insecure cookies, split backfill auto-run off, Finnhub/Zillow off, `INTEGRATION_TEST_*` JWT secret, WARN logging |
| `loadtest` | `loadtest/docker-compose.loadtest.yml` | Isolated load-test database, batch-tuned Hibernate, anonymous metrics scraping (`app.observability.anonymous-metrics: true`), `LoadTestDataSeeder`. Per-URI latency histograms are no longer profile-specific — `application.yml` enables them everywhere |

### Startup Initializers by Profile

| Initializer | Profiles | What it does |
|-------------|----------|--------------|
| `SuperAdminInitializer` | `dev`, `docker`, `prod` | Creates `admin@wealthview.local` from `SUPER_ADMIN_PASSWORD` |
| `SampleDataInitializer` | `dev`, `docker` | Seeds the `demo@wealthview.local` / `demo123` demo tenant |
| `DevDataInitializer` | `dev` only | Seeds a developer tenant with `demo-admin@wealthview.local` and `demo-member@wealthview.local` (both `demo123`) |
| `SystemConfigInitializer` | all | Seeds `system_config` defaults (see below) |
| `ProductionConfigValidator` | `prod`, `docker` | Fail-fast secret/CORS validation on `ApplicationReadyEvent` |
| `LoadTestDataSeeder` | `loadtest` only | Bulk-seeds tenants and transactions |

`ProductionConfigValidator` also aborts startup if `prod` is active alongside `dev` or `docker`, because the demo seeders use hardcoded passwords that no env-var check can catch.

## Runtime Settings (`system_config` table)

`SystemConfigInitializer` seeds these into the database on first start; super-admins then view and edit them at **Admin → System Config** (`GET /api/v1/admin/config`, `PUT /api/v1/admin/config/{key}`). Values already present in the table are never overwritten by the seeder, and API-key changes take effect after the next restart.

| Key | Seeded from | Default |
|-----|-------------|---------|
| `finnhub.api-key` | `app.finnhub.api-key` (only if non-blank) | *(not seeded when empty)* |
| `finnhub.rate-limit-ms` | `app.finnhub.rate-limit-ms` | `1100` |
| `yahoo.rate-limit-ms` | `app.yahoo.rate-limit-ms` | `500` |
| `zillow.scraper.enabled` | *(literal)* | `false` |

## Frontend

The SPA takes no runtime environment variables — it calls the API at the relative path `/api/v1` and relies on HttpOnly auth cookies, so it works unchanged behind any hostname. Build-time configuration lives in `frontend/vite.config.ts`:

| Setting | Value | Description |
|---------|-------|-------------|
| `server.port` | `5173` | Vite dev server port |
| `server.proxy['/api']` | `http://localhost:8080` | Dev-server proxy to the local backend |
| `import.meta.env.DEV` | build-time | The only env flag the app reads — `ErrorBoundary` shows stack traces in dev builds and a generic message in production builds |

---

## Related Docs

- [Deployment Guide](../deployment/production-setup.md) — Production security checklist and resource requirements
- [Operations Handbook](../deployment/operations.md) — `./wv` subcommands, backups, updates, rollback
- [Observability](../OBSERVABILITY.md) — Metrics, tracing, and the Prometheus/Grafana stack
- [Security Hardening](../deployment/security-hardening.md) — Secret handling and hardening checklist
- [Development Guide](../development.md) — Local setup and build commands
