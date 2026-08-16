[← Back to README](../../README.md)

# Maintenance

This guide covers the update/rollback flow, scheduled jobs, database maintenance, config
validation, secret rotation, disk space management, certificate renewal, Docker image
updates, performance tuning, and capacity planning.

Routine operations go through the `./wv` admin command. In a source checkout, run it as
`./wv <subcommand>`; on a production server where `wv` is installed to `/usr/local/bin/wv`,
drop the `./`. `wv help` is the full operator man page, and
[Operations Handbook](../deployment/operations.md) is the long-form companion to this page.

---

## Updating the Application

`./wv update` is the supported in-place upgrade. It refuses to start if the env file is
incomplete, and it takes a backup before it touches anything.

In production it **pulls** the release image CI published to
`ghcr.io/<owner>/wealthview:<version>` — it does not build. The host needs neither the
source tree nor a JDK, only Docker and read access to the registry.

```bash
# 1. Pin the release you want in the env file (production only)
#    WEALTHVIEW_VERSION=1.3.0
# 2. Run the update
./wv update
```

**What it does, in order:**

1. Validates the env file (`DB_PASSWORD`, `JWT_SECRET`, `SUPER_ADMIN_PASSWORD`,
   `MFA_ENCRYPTION_KEY` present and not `CHANGE_ME`).
2. Refuses to continue unless the `db` container is running.
3. Takes a pre-update backup labelled `pre-update` into the backups directory.
4. Records the currently-running app image reference — repository **and** tag — to
   `.wv-previous-image` (path overridable with `WV_PREVIOUS_IMAGE_FILE`).
5. Pulls the app image (`docker compose pull app`) in prod mode, or builds it in dev mode
   — the dev compose file builds from source and has nothing to pull. Unless `--no-pull`
   was passed.
6. Recreates the app container (`docker compose up -d --no-deps app`).
7. Polls `/actuator/health` for up to 180 seconds.
8. On health failure, automatically rolls back to the recorded image and re-checks health,
   unless `--no-rollback` was passed.

If step 5 fails, nothing has been swapped — the old container is still serving. The two
usual causes are a `WEALTHVIEW_VERSION` that names no published release, and a host that
cannot read the registry (the GHCR package starts out **private**, even for a public repo,
so it needs `docker login ghcr.io` with a `read:packages` token until someone makes it
public). See
[Upgrading](../deployment/upgrading.md#before-your-first-pull-registry-access).

**Exit codes:**

| Code | Meaning |
|---|---|
| `0` | Update succeeded and the app is healthy |
| `1` | Update failed; rollback succeeded (when enabled) |
| `2` | Update failed **and** rollback failed — manual intervention needed |

**Options:**

| Flag | Effect |
|---|---|
| `--no-pull` | Skip the fetch; reuse whatever image is already on the host for the resolved tag. |
| `--no-build` | Deprecated alias for `--no-pull`. Still works, prints a warning. |
| `--build` | Build locally instead of pulling. Requires a compose file whose `app` service has a `build:` key — `docker-compose.prod.yml` has none, and `update` rejects the flag rather than letting `docker compose build` no-op into a stale deploy. |
| `--no-rollback` | Leave the failing container in place for inspection. |

Whatever the outcome, the pre-update backup stays on disk. If the new version applied
schema changes and you need to rewind data as well as code, restore it — see
[Backups](backups.md#restoring-from-a-backup).

---

## Rolling Back

```bash
./wv rollback
```

Reads `.wv-previous-image` (written by the last `./wv update`) and restarts the app
container pinned to that reference. In prod mode it re-runs compose with **both**
`WEALTHVIEW_IMAGE=<previous repository>` and `WEALTHVIEW_VERSION=<previous tag>`; in dev
mode it simply recreates the app service. It then waits up to 120 seconds for the health
endpoint.

Both halves are restored because the reference is registry-qualified now
(`ghcr.io/<owner>/wealthview:1.2.5`). Re-pinning only the tag would silently move a
mirrored or forked deployment back onto the default upstream repository. This is also why
`WEALTHVIEW_VERSION` must be a real version and never `latest` — a moving tag gives
rollback nothing to recover to.

**Rollback reverts the app image only. The database is not rewound.** If the failed
version ran migrations, restore the matching `pre-update` backup first:

```bash
./wv backups
./wv restore backups/wealthview_<timestamp>_pre-update.dump
```

If no previous image was ever recorded, `./wv rollback` fails loudly rather than guessing.

---

## Restarting and Validating Configuration

```bash
./wv restart                 # wv down && wv up (data volumes preserved)
./wv restart --no-build      # extra args after `restart` are forwarded to `up`
./wv status                  # container status + one-shot health probe
./wv logs app --tail 100     # tail logs for one service
./wv psql                    # interactive psql as wv_app against the wealthview database
```

`./wv config-check` validates the whole configuration surface before you need it in anger:

```bash
./wv config-check
```

It reports:

- the resolved config file, env file, compose file (plus override, if any), backups
  directory, compose project name, and remote host
- required tools (`docker`, `curl`, `python3`, `openssl`) and optional ones (`age`,
  `age-keygen`, `gitleaks`, `rsync`, `aws`)
- missing or placeholder (`CHANGE_ME`) env vars
- whether the compose file parses (`docker compose config -q`)
- a gitleaks scan of the staged diff, when `gitleaks` is installed

It exits non-zero if any required check fails, so it is safe to run from a pre-deploy
script.

---

## Rotating Secrets

```bash
./wv rotate-secret JWT_SECRET
./wv rotate-secret DB_PASSWORD
./wv rotate-secret SUPER_ADMIN_PASSWORD
./wv rotate-secret <NAME> --dry-run
```

| Secret | New value | What happens |
|---|---|---|
| `JWT_SECRET` | 48-char base64 | Written to the env file, app recreated. **All existing JWTs become invalid** — everyone logs in again |
| `DB_PASSWORD` | 24-char base64 | Written to the env file **and** applied with `ALTER USER wv_app` against the live database, then the app restarts |
| `SUPER_ADMIN_PASSWORD` | 24-char base64 | Written to the env file; the `users` row is updated directly when `python3` has the `bcrypt` module available |

Only those three names are accepted; anything else is rejected. In particular
`MFA_ENCRYPTION_KEY` is **not** rotatable through this command — rotating it would strand
every stored TOTP secret.

**Caveat on `SUPER_ADMIN_PASSWORD`:** the database row is only updated when `python3 -c
"import bcrypt"` works. Without it, the command warns and restarts the app, but
`SuperAdminInitializer` only creates the account when it is missing — it does not reset an
existing password. Verify you can still log in, and if not, install `python3-bcrypt` and
re-run, or set the password from the admin UI.

Take a fresh backup after any rotation so it reflects the new state:

```bash
./wv backup --label post-rotate
```

---

## Scheduled Jobs

Scheduling is enabled application-wide (`SchedulingConfig` with `@EnableScheduling`).

### Price Sync

| | |
|---|---|
| **Schedule** | Weekdays (Mon--Fri) at **6:00 PM America/New_York**, once. The trigger lives on `PriceSyncService.syncDailyPrices()` itself; the cron is overridable via `app.finnhub.sync-cron` |
| **What it does** | Fetches a Finnhub quote for every distinct symbol held across all tenants and upserts it into the `prices` table for today's date |
| **Requirement** | `FINNHUB_API_KEY` must be set. Both the Finnhub client beans and `PriceSyncService` are conditional on a non-empty key — with no key the job does not exist at all |

There is exactly one daily trigger. A second scheduled wrapper used to fire the same
sweep at 4:30 PM as well, doubling the Finnhub quota burn for no benefit; it was removed,
and `PriceSyncSchedulingTest` now fails the build if anything reintroduces one.

**Verification:**

```bash
docker compose exec db psql -U wv_app wealthview -c "
  SELECT symbol, close_price, date
  FROM prices
  WHERE date = CURRENT_DATE
  ORDER BY symbol;"
```

(`./wv psql` opens an interactive shell against the same database and takes no other
arguments — use it when you want to poke around rather than run a single query.)

**If prices are missing:**

1. Check that `FINNHUB_API_KEY` is set: `grep FINNHUB_API_KEY .env`
2. Check app logs for API errors: `./wv logs app --tail 500 --no-follow | grep -i finnhub`
3. Individual symbol failures are logged at WARN level. One symbol failing does not stop
   the others.
4. The job retries on its next scheduled run. No manual intervention needed — or trigger
   one immediately from the admin UI (`/admin` → Prices) or
   `POST /api/v1/admin/prices/sync`.

**Notes:**
- The job does not run on weekends or market holidays. Missing weekend prices is expected.
- Requests are throttled by `app.finnhub.rate-limit-ms` (default 1100ms) to stay inside
  Finnhub's free-tier limit of 60 requests/minute. With many symbols the sync takes
  several minutes.

### Stock Split Sync

| | |
|---|---|
| **Schedule** | Daily at **2:00 AM America/New_York** (`app.stock-splits.sync-cron`) |
| **What it does** | Fetches splits from Finnhub for every distinct symbol in transactions, and applies any split not already recorded — adjusting transactions, holdings, and historical prices |
| **Requirement** | `FINNHUB_API_KEY` (same conditional wiring as price sync) |

Manual entry and un-apply live under `/api/v1/admin/stock-splits` and the `/admin` →
Stock Splits tab. See [Stock Splits](../operations/stock-splits.md).

### Zillow Valuation Sync

| | |
|---|---|
| **Schedule** | Sundays at **6:00 AM server time** (`app.zillow.sync-cron`, no timezone override — this one follows the JVM default zone) |
| **What it does** | For every property in every tenant, looks up a Zestimate by street address and records a `property_valuations` row with source `zillow` |
| **Requirement** | `ZILLOW_ENABLED=true` in `.env` (default `false`). The client bean — and therefore the whole sync service — only exists when enabled |

The scheduled sweep is **address-based**. The stored `zillow_zpid` is used by the on-demand
refresh (`POST /api/v1/properties/{id}/valuations/refresh` and `.../valuations/select-zpid`)
so you can disambiguate when Zillow returns multiple matches.

**Verification:**

```bash
docker compose exec db psql -U wv_app wealthview -c "
  SELECT p.address, pv.value, pv.valuation_date
  FROM property_valuations pv
  JOIN properties p ON p.id = pv.property_id
  ORDER BY pv.valuation_date DESC
  LIMIT 10;"
```

**If valuations are missing:**

1. Verify `ZILLOW_ENABLED=true` in `.env` and that the app was restarted afterwards.
2. Check logs for Zillow errors: `./wv logs app --tail 500 --no-follow | grep -i zillow`
3. Zillow may block or rate-limit scraping. Failures are logged and the property's value
   remains unchanged — no data is lost.

---

## Database Maintenance

PostgreSQL autovacuum runs automatically and handles routine maintenance in most cases.
Manual intervention is rarely needed.

### Checking Database Size

**Overall database size:**

```bash
docker compose exec db psql -U wv_app wealthview -c "SELECT pg_size_pretty(pg_database_size('wealthview'));"
```

**Top 10 largest tables:**

```bash
docker compose exec db psql -U wv_app wealthview -c "
  SELECT schemaname, tablename,
         pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
  FROM pg_tables
  WHERE schemaname = 'public'
  ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC
  LIMIT 10;"
```

On a production install where the compose file is not in the current directory, either add
`-f /etc/wealthview/docker-compose.prod.yml` or run the query from the `./wv psql` shell.

### Manual VACUUM ANALYZE

Run this if you notice degraded query performance, especially after large imports or bulk
deletes:

```bash
docker compose exec db psql -U wv_app wealthview -c "VACUUM ANALYZE;"
```

This reclaims dead row space and updates query planner statistics. It is safe to run while
the application is live.

### Flyway Migrations

The schema is managed by Flyway: **80 versioned migrations (V001--V080)** plus **9
repeatable seed migrations** (`R__seed_*` for prices, tax brackets, LTCG brackets,
standard deductions, IRMAA tiers, state tax brackets, asset-class returns, security asset
classes, and mortality rates). They live in
`backend/wealthview-persistence/src/main/resources/db/migration/` and run automatically on
application startup (`spring.flyway.enabled: true`).

To check the current migration state:

```bash
docker compose exec db psql -U wv_app wealthview -c "
  SELECT version, description, installed_on, success
  FROM flyway_schema_history
  ORDER BY installed_rank;"
```

Never modify a migration that has already been applied — Flyway validates checksums and
the app will refuse to start. If a migration needs correction, add a new versioned one.

---

## Disk Space Management

### Backup Retention

After each run the `backup` container deletes only the dumps it created itself —
`wealthview_auto_*.dump[.age]`, plus the legacy `wealthview_<YYYY-MM-DD_HH-MM>.dump[.age]`
naming for scheduled files already on disk — older than `BACKUP_RETENTION_DAYS`
(default 14). On-demand `./wv backup` dumps (`wealthview_<ISO-8601-UTC>[_label].dump`)
are never swept: the operator owns their lifecycle, including the `--encrypt` `.age`
files. Prune those yourself.

```bash
# Check backup directory size
du -sh ./backups/

# List backups with size and age
./wv backups

# Count backup files
ls ./backups/*.dump 2>/dev/null | wc -l
```

To reduce disk usage, lower `BACKUP_RETENTION_DAYS` in the env file and restart the backup
container, and delete stale `.age` files by hand.

### Docker Cleanup

```bash
# Check Docker disk usage
docker system df

# Remove stopped containers, unused networks, dangling images
docker system prune -f

# Remove ALL unused images (aggressive — will require re-downloading base images)
docker image prune -a -f
```

### Log Rotation

See [monitoring-and-logging.md](monitoring-and-logging.md#log-rotation) for Docker log
driver configuration.

---

## Certificate Renewal

TLS is **not** part of the compose stack — there is no nginx or certbot container. If you
followed [TLS & Nginx](../deployment/tls-and-nginx.md), nginx and certbot are host packages
managed by systemd; if you followed [Cloudflared](../deployment/cloudflared.md), Cloudflare
terminates TLS and there is nothing to renew locally.

For the host-nginx setup, the certbot package installs a systemd timer that renews
certificates automatically (Let's Encrypt certs last 90 days; certbot renews at ~60 days):

```bash
systemctl list-timers | grep certbot     # confirm the timer is scheduled
sudo certbot renew --dry-run             # validate renewal without calling Let's Encrypt
```

**Verify certificate expiry:**

```bash
echo | openssl s_client -connect yourdomain.com:443 2>/dev/null | openssl x509 -noout -dates
```

**If renewal fails:**

1. Confirm port 80 is open and reachable from the internet (HTTP-01 challenge).
2. Verify DNS still points at this server: `dig +short yourdomain.com`
3. Check the renewal logs: `sudo journalctl -u certbot` and
   `/var/log/letsencrypt/letsencrypt.log`
4. Validate the nginx config and reload: `sudo nginx -t && sudo systemctl reload nginx`

---

## Docker Image Updates

`./wv update` pulls the CI-published app image and swaps it in with a health check and
auto-rollback — that is the normal path for shipping a new WealthView version.

Base images are **pinned by digest** (node 24-alpine, maven eclipse-temurin 25,
eclipse-temurin 25-jre-alpine in `Dockerfile`; postgres 16 in both compose files;
alpine 3.20 in `infra/backup/Dockerfile`). A rebuild therefore does *not* silently pick up
newer base images — that is deliberate, and it means base-image security updates are an
explicit, reviewable change. Because the app image now comes from the registry, that
change has to go through a release: update the digest, cut a `v*` tag so CI builds and
publishes, then pin the new version on the server.

```bash
# Example for the runtime base image, on a development machine
docker pull eclipse-temurin:25-jre-alpine
docker inspect --format='{{index .RepoDigests 0}}' eclipse-temurin:25-jre-alpine
# Paste the new digest into the FROM line, commit, and tag the release. Then on the server:
#   WEALTHVIEW_VERSION=<new version>
./wv update
```

Each pinned `FROM` line in `Dockerfile` carries this recipe as a comment.

The `db` and `backup` images are separate: `db` is digest-pinned directly in the compose
files (edit the digest and `./wv up` to adopt it), and `backup` is still built on the host
from `infra/backup/`.

---

## Performance Tuning

### PostgreSQL Memory

For larger datasets, customize PostgreSQL settings via a Docker volume mount to a custom
`postgresql.conf`. Key parameters:

| Setting | Recommendation | Default |
|---------|---------------|---------|
| `shared_buffers` | 25% of available RAM | 128 MB |
| `work_mem` | 4--16 MB | 4 MB |
| `effective_cache_size` | 50--75% of available RAM | 4 GB |

### Connection Pool

The app uses HikariCP with `maximum-pool-size: 20`, `minimum-idle: 5`, a 10s connection
timeout, and a 10-minute max lifetime (`application.yml`). PostgreSQL's default
`max_connections` is 100, so a single app instance has plenty of headroom.

### JVM Heap

The image's entrypoint is `ENTRYPOINT ["java", "-jar", "app.jar"]` — an exec-form
entrypoint with no shell, so a `JAVA_OPTS` environment variable is **ignored**. Use
`JAVA_TOOL_OPTIONS`, which the JVM itself reads:

```yaml
services:
  app:
    environment:
      JAVA_TOOL_OPTIONS: "-Xmx512m -Xms256m"
```

Start with `-Xmx512m` and increase if needed. The JVM prints
`Picked up JAVA_TOOL_OPTIONS: ...` on startup, which is a handy confirmation the setting
took effect. Monitor with `docker stats`.

---

## Capacity Planning

### Growth Estimates

| Data | Growth Rate |
|------|-------------|
| Prices table | ~252 rows/year per tracked symbol (one row per weekday per symbol; the two daily runs upsert the same row) |
| Transactions | Depends on import frequency — a typical household adds ~200-500/year |
| Property valuations | ~52 rows/year per property (weekly Zillow sync) |
| Backups | ~1-5 MB per daily backup for a small/medium dataset |

### When to Scale Up

| Indicator | Action |
|-----------|--------|
| Database queries consistently > 500ms | Add indexes, tune PostgreSQL memory, or upgrade server CPU/RAM |
| `docker stats` shows sustained high CPU or memory pressure | Increase container resource limits or upgrade server |
| Disk usage above 80% | Expand disk, reduce backup retention, clean Docker images |
| Database size exceeds available RAM | Increase `shared_buffers` and `effective_cache_size` |

---

## Related Docs

- [Operations Handbook](../deployment/operations.md) — the full `wv` command surface
- [Backups](backups.md) — on-demand and scheduled backups, verification, restore
- [Monitoring & Logging](monitoring-and-logging.md) — health, metrics, log parsing
- [Troubleshooting](troubleshooting.md) — symptom-driven diagnostics
