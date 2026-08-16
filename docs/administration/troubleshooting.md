[← Back to README](../../README.md)

# Troubleshooting

This guide covers diagnosis and resolution of common WealthView issues. Each section
follows a symptom/cause/fix structure with copy-paste commands.

Commands are written for a source checkout (`./wv ...`). On a production server where `wv`
is installed to `/usr/local/bin/wv`, drop the `./`. Where a raw `docker compose` command is
shown, add `-f docker-compose.prod.yml` if you are on the production stack and the file is
not the default one in your working directory.

---

## Diagnostic Commands Quick Reference

| What | Command |
|------|---------|
| Container status + health probe | `./wv status` |
| Resolved config, tools, env, compose validity | `./wv config-check` |
| App logs (last 50 lines) | `./wv logs app --tail 50 --no-follow` |
| DB logs (last 50 lines) | `./wv logs db --tail 50 --no-follow` |
| Backup container logs (prod only) | `./wv logs backup --tail 50 --no-follow` |
| Health check | `curl -sf http://localhost/actuator/health` |
| Open a psql shell | `./wv psql` |
| Database size | `docker compose exec db psql -U wv_app wealthview -c "SELECT pg_size_pretty(pg_database_size('wealthview'));"` |
| Active DB connections | `docker compose exec db psql -U wv_app wealthview -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'wealthview';"` |
| List backups | `./wv backups` |
| Disk space | `df -h` |
| Docker disk usage | `docker system df` |
| Container resource usage | `docker stats --no-stream` |

**Service names:** the dev stack (`docker-compose.yml`) has `db` and `app`. The production
stack (`docker-compose.prod.yml`) adds `backup`. There is no `nginx` or `certbot`
container — if you terminate TLS with nginx it runs on the host (see
[TLS & Nginx](../deployment/tls-and-nginx.md)).

**Ports:** the app is published on `${APP_PORT:-80}` → 8080 in the container. The dev
compose file also publishes PostgreSQL on host port **5433** (→ 5432 in the container) so
local backends and IDE run configs can connect; the production compose file publishes no
database port at all.

---

## App Won't Start

### Start Here

```bash
./wv config-check          # env vars, compose syntax, required tools
./wv status                # what is actually running
./wv logs app --tail 100 --no-follow
```

### Database Not Ready

**Symptom:** App container restarts repeatedly. Logs show "Connection refused" or a
connection error against `db:5432`.

**Cause:** PostgreSQL has not finished initializing. Common on first startup, when
PostgreSQL runs `initdb`.

**Fix:** The app depends on the db health check (`pg_isready`) and retries automatically.
Wait 1--2 minutes. If it persists:

```bash
./wv status
./wv logs db --tail 100 --no-follow
```

Common db startup failures: disk full, corrupted data directory, wrong
`POSTGRES_PASSWORD` on first init.

### Migration Failure

**Symptom:** App logs show "Flyway migration failed", "Migration checksum mismatch", or a
SQL error during startup.

**Cause:** either a versioned migration file was modified after it was already applied, or
a new migration contains a SQL error.

**Fix:**

- **Checksum mismatch:** a previously applied migration was edited. This is never allowed.
  Restore the original content and redeploy. Check history:
  `git log --oneline -- backend/wealthview-persistence/src/main/resources/db/migration/`
- **SQL error in a new migration:** fix the SQL and redeploy.
- **Database in a bad state (partially applied migration):** restore the pre-update backup
  that `./wv update` took, then investigate — see
  [Backups](backups.md#restoring-from-a-backup).

To check migration status:

```bash
docker compose exec db psql -U wv_app wealthview -c "
  SELECT version, description, success
  FROM flyway_schema_history
  ORDER BY installed_rank DESC
  LIMIT 10;"
```

The schema is at V080 plus 9 repeatable seed migrations; repeatable (`R__`) migrations
re-run whenever their checksum changes, which is normal.

### Production Config Validation Failure

**Symptom:** The app boots, then exits with `IllegalStateException: SECURITY: ...`.

**Cause:** `ProductionConfigValidator` runs on the `prod` and `docker` profiles and
fail-fasts on unsafe configuration:

| Message contains | Meaning |
|---|---|
| `JWT_SECRET must be set to a unique value of at least 32 characters` | Missing, too short, a known default, or a `LOCAL_DEV_*` sentinel |
| `SUPER_ADMIN_PASSWORD must be set to a unique value` | Blank, `admin123`, `demo123`, or a dev sentinel |
| `DB_PASSWORD must be set to a unique value` | Blank or a dev sentinel |
| `MFA_ENCRYPTION_KEY must be set to a unique base64-encoded 32-byte value` | Blank, or the dev sentinel. Generate with `openssl rand -base64 32` |
| `CORS_ORIGIN must be set to a non-empty list` | `CORS_ORIGIN` empty in prod/docker |
| `CORS_ORIGIN entries must use the https:// scheme` | Under the `prod` profile every origin must be `https://` |
| `the 'prod' profile must not be combined with dev/demo-seed profile(s)` | `SPRING_PROFILES_ACTIVE` contains `prod` **and** `dev` or `docker`. Set it to `prod` only |

**Fix:** correct the value in the env file, then `./wv restart`. `./wv config-check`
catches the missing/placeholder cases before you deploy.

### Port Conflict

**Symptom:** startup fails with "Bind for 0.0.0.0:80: address already in use" or similar.

**Fix:**

```bash
# Find what's using the app port
ss -tlnp | grep :80

# Dev stack also binds PostgreSQL on 5433
ss -tlnp | grep :5433
```

Stop the conflicting service, or set `APP_PORT` in the env file to a free port (the prod
compose file publishes `${APP_PORT:-80}:8080`). If you change it, update `WV_APP_PORT` (or
`WV_HEALTH_URL`) in `wv.conf` so health checks still point at the right place.

### Out of Memory

**Symptom:** the app container is killed by the OOM killer — `docker compose ps` shows
exit code 137.

**Fix:**

1. Check system memory: `free -h`
2. Check container memory usage: `docker stats --no-stream`
3. Cap the JVM heap. The image entrypoint is `ENTRYPOINT ["java", "-jar", "app.jar"]` —
   exec form with no shell, so `JAVA_OPTS` is **ignored**. Use `JAVA_TOOL_OPTIONS`:
   ```yaml
   services:
     app:
       environment:
         JAVA_TOOL_OPTIONS: "-Xmx384m -Xms256m"
   ```
   The JVM logs `Picked up JAVA_TOOL_OPTIONS: ...` at startup, confirming it applied.
4. Check for other processes consuming memory on the host.

### Environment Variable Missing

**Symptom:** the app starts but crashes immediately with errors about missing
configuration, or compose itself refuses to start with
`DB_PASSWORD must be set in .env`.

**Fix:**

```bash
./wv config-check
```

It reports missing and placeholder (`CHANGE_ME`) values for all four required variables.

| Variable | Requirement |
|----------|------------|
| `DB_PASSWORD` | Required. Must match the password PostgreSQL was initialized with |
| `JWT_SECRET` | Required. At least 32 characters |
| `SUPER_ADMIN_PASSWORD` | Required. Password for the auto-created super-admin account |
| `MFA_ENCRYPTION_KEY` | Required. Base64 32-byte AES key (`openssl rand -base64 32`) |
| `CORS_ORIGIN` | Required on the prod profile; must be `https://` |
| `WEALTHVIEW_VERSION` | Required by `docker-compose.prod.yml` — the release tag to pull; also flips `./wv` into prod mode. Never `latest` |
| `WEALTHVIEW_IMAGE` | Optional — registry/repository holding the app image. Defaults to `ghcr.io/jakefearsd/wealthview`; set only for a fork, mirror, or air-gapped registry |
| `FINNHUB_API_KEY` | Optional — price and split sync disabled without it |
| `ZILLOW_ENABLED` | Optional — defaults to `false` |
| `BACKUP_RETENTION_DAYS` | Optional — defaults to 14 |
| `APP_PORT` | Optional — defaults to 80 |

---

## Can't Log In

### Wrong Credentials

**Symptom:** login returns 401 Unauthorized.

**Fix:** verify the email and password:

- **Super-admin:** `admin@wealthview.local` with the value of `SUPER_ADMIN_PASSWORD`
- **Demo user:** `demo@wealthview.local` / `demo123` — seeded by `SampleDataInitializer`,
  which only runs on the `dev` and `docker` profiles. It does **not** exist on a `prod`
  deployment (and `ProductionConfigValidator` refuses to start if `prod` is combined with
  a seed profile).

Passwords are case-sensitive. Check for trailing whitespace in the env file.

### Account Temporarily Locked

**Symptom:** correct credentials still return 401, right after several failed attempts.

**Cause:** `LoginAttemptService` blocks an email address after **5 failed attempts within
15 minutes**. Every failure mode returns the same generic error, so the response does not
say "locked".

**Fix:** wait for the 15-minute window to roll off, or restart the app (the counter is
in-memory):

```bash
./wv restart --no-build
```

### User or Tenant Deactivated

**Symptom:** one user — or every user in a tenant — gets 401 with correct credentials.

**Cause:** `users.is_active` or `tenants.is_active` is `false`.

**Fix:**

```bash
docker compose exec db psql -U wv_app wealthview -c "SELECT id, name, is_active FROM tenants;"
docker compose exec db psql -U wv_app wealthview -c "SELECT email, role, is_active FROM users ORDER BY email;"
```

Re-enable through the UI: log in as super-admin, go to `/admin` → **Tenants** (or
**Users**) and toggle the entry. The equivalent API calls are
`PUT /api/v1/admin/tenants/{id}/active` and `PUT /api/v1/admin/users/{userId}/active`,
both with body `{ "active": true }`.

### MFA Blocking Login

**Symptom:** login returns `{"mfa_required": true, "mfa_token": "..."}` instead of a
session, and the user no longer has their authenticator app.

**Fix:** the user completes login by posting the TOTP code (or one of their recovery codes)
to `POST /api/v1/auth/mfa/challenge`. If both the device and the recovery codes are lost,
a super-admin can clear MFA for that account directly:

```bash
./wv psql
# then:
# UPDATE users SET mfa_enabled = false, mfa_secret_encrypted = NULL, mfa_setup_at = NULL
#  WHERE email = 'user@example.com';
```

Have the user re-enrol afterwards from their account settings.

### Sessions Suddenly Invalid

**Symptom:** previously logged-in users get 401 on every API call. New logins work fine.

**Causes:**

- `JWT_SECRET` was rotated (`./wv rotate-secret JWT_SECRET` says so explicitly) — every
  previously issued token is now unverifiable.
- The user's `token_generation` was bumped: a role change or an admin password reset does
  this deliberately to invalidate existing sessions.
- The session was revoked from `/api/v1/auth/sessions`.

**Fix:** this is expected behaviour. Users log in again; old tokens cannot be refreshed or
repaired.

### Rate Limited (429)

**Symptom:** repeated login attempts or a busy client start returning HTTP 429.

**Cause:** `RateLimitFilter` allows **60 auth requests per minute per IP** and **300 API
requests per minute per authenticated user**, in 60-second windows. Super-admin requests
are exempt.

**Fix:** back off for a minute. The filter can be disabled with
`app.rate-limit.enabled=false` for diagnostics, but leave it on in production.

### CORS Errors

**Symptom:** the browser console shows "CORS policy" errors. API calls fail from the
frontend but work with `curl`.

**Cause:** the requesting origin is not in the allow-list.

**Fix:**

- `docker` profile allows `http://localhost` — reach the app at `http://localhost`, not an
  IP or custom hostname.
- `prod` profile allows exactly what is in `CORS_ORIGIN` (comma-separated), and every
  entry must be `https://`. Update it and `./wv restart`.
- `dev` profile (running the backend outside Docker) allows `http://localhost:5173`.

---

## Import Failures

### Wrong Format Selected

**Symptom:** import completes but rows fail validation or produce incorrect data.

**Fix:** the format must match the brokerage that produced the file. The available parsers
are Fidelity, Fidelity positions, Vanguard, and Schwab for CSV, plus OFX/QFX:

- Fidelity transaction CSV: select "Fidelity"
- Fidelity positions CSV: use the positions import
- Vanguard CSV: select "Vanguard"
- Schwab CSV: select "Schwab"
- OFX/QFX file: select "OFX"

Each parser expects specific column headers and data formats.

### File Encoding Issues

**Symptom:** parse errors, or imported data contains garbled characters.

**Fix:** ensure the CSV is UTF-8. Most brokerages export UTF-8 by default. To convert:

```bash
iconv -f ISO-8859-1 -t UTF-8 original.csv > converted.csv
```

### Missing Required Columns

**Symptom:** the import fails immediately with a parse error mentioning expected headers.

**Fix:** open the file and verify it has the expected headers for the selected format. The
file may be truncated, or the brokerage may have changed their export layout.

### All Rows Rejected as Duplicates

**Symptom:** the import completes with 0 successful rows; everything is reported as a
duplicate.

**Cause:** the same content was already imported. WealthView deduplicates on a per-row
content hash stored in `transactions.import_hash`.

**Fix:** this is working as intended — only genuinely new transactions are inserted. To
confirm the data really is present:

```bash
docker compose exec db psql -U wv_app wealthview -c "
  SELECT symbol, type, quantity, amount, date
  FROM transactions
  ORDER BY created_at DESC
  LIMIT 20;"
```

---

## Prices Not Updating

### No Finnhub API Key

**Symptom:** no new rows in `prices`, and no price-related log entries at all.

**Cause:** the Finnhub client beans and `PriceSyncService` are conditional on a non-empty
`app.finnhub.api-key`. With no key, the sync job does not exist — there is nothing to log.

**Fix:**

```bash
grep FINNHUB_API_KEY .env
```

Set the key, then recreate the app so it picks up the new environment:

```bash
./wv restart --no-build
```

### Finnhub Rate Limit

**Symptom:** some symbols get prices, others do not. Logs show HTTP 429 or per-symbol
warnings.

**Cause:** Finnhub's free tier allows 60 requests/minute. The app self-throttles with
`app.finnhub.rate-limit-ms` (default 1100ms), but a large symbol list still takes minutes
and can hit provider-side limits.

**Fix:**

1. Reduce the number of tracked symbols
2. Upgrade to a paid Finnhub plan
3. Wait — failed symbols are retried on the next run, and you can trigger one immediately
   from `/admin` → **Prices**

### Weekends and Holidays

**Symptom:** no prices for Saturday or Sunday.

**Cause:** the sync only runs Monday--Friday (18:00 America/New_York, once). Markets
are closed on weekends.

**Fix:** expected behaviour. No action needed.

---

## Zillow Not Working

### Not Enabled

**Symptom:** no property valuations appear, and no Zillow log entries.

**Cause:** the Zillow client bean is conditional on `app.zillow.enabled=true`, which the
compose files drive from `ZILLOW_ENABLED` (default `false`). When disabled, the sync
service does not exist.

**Fix:**

```bash
grep ZILLOW_ENABLED .env
./wv restart --no-build
```

### No Match for the Address

**Symptom:** the weekly sync runs (visible in the logs) but a given property never gets a
valuation.

**Cause:** the scheduled sweep looks properties up by **street address**. If Zillow returns
nothing for that address string, the property is skipped.

**Fix:** check what is stored, and use the on-demand refresh, which searches and lets you
pick the right match:

```bash
docker compose exec db psql -U wv_app wealthview -c "SELECT id, address, zillow_zpid FROM properties;"
```

Then, from the property page in the UI, run a valuation refresh
(`POST /api/v1/properties/{id}/valuations/refresh`). When the search returns multiple
candidates, selecting one (`POST /api/v1/properties/{id}/valuations/select-zpid`) stores
the `zillow_zpid`, and subsequent refreshes go straight to that listing.

### Blocked by Zillow

**Symptom:** logs show timeouts, HTTP errors, or blocked responses from Zillow.

**Cause:** Zillow blocks or rate-limits scraping, especially from datacenter IPs.

**Fix:** no reliable workaround — Zillow has no official free API. The property's existing
value is left unchanged on failure (no data loss). Update values manually in the UI.

---

## Database Issues

### Connection Refused

**Symptom:** app logs show "Connection refused" to PostgreSQL.

**Fix:**

```bash
./wv status
./wv logs db --tail 50 --no-follow
```

Common causes: the db container crashed, disk is full, or data files are corrupted. Note
the app connects to the host name `db` inside the Docker network — not `localhost`.

### Authentication Failed

**Symptom:** app logs show "password authentication failed for user wv_app".

**Cause:** `DB_PASSWORD` does not match the password PostgreSQL was initialized with.
PostgreSQL only reads `POSTGRES_PASSWORD` when the data directory is first created.

**Fix:** put both sides back in sync in one step:

```bash
./wv rotate-secret DB_PASSWORD
```

That generates a new value, writes it to the env file, applies it with
`ALTER USER wv_app`, and restarts the app. To set a specific value instead:

```bash
./wv psql
# then: ALTER USER wv_app WITH PASSWORD '<the value already in your env file>';
```

As a last resort, delete the `pgdata` volume and reinitialize — **this destroys all
data**; restore from backup afterwards.

### Disk Full

**Symptom:** inserts fail, backups fail, logs show "could not write to file" or "No space
left on device".

**Fix:**

```bash
df -h
docker system df
du -sh ./backups/
```

Immediate remediation:

1. Clean Docker: `docker system prune -f`
2. Reduce backup retention: lower `BACKUP_RETENTION_DAYS` in the env file
3. Delete old backups by hand — `./wv backups` to see what you have. Retention only sweeps
   the cron container's own `wealthview_auto_*.dump[.age]` files (plus the legacy
   `wealthview_<YYYY-MM-DD_HH-MM>` scheduled naming); everything from `./wv backup`,
   encrypted or not, is yours to prune
4. Expand the disk if the above is not enough

### Too Many Connections

**Symptom:** app logs show "too many clients already", or requests fail intermittently.

**Fix:**

```bash
docker compose exec db psql -U wv_app wealthview -c "
  SELECT state, count(*)
  FROM pg_stat_activity
  WHERE datname = 'wealthview'
  GROUP BY state;"
```

The app's Hikari pool is capped at 20 connections (`maximum-pool-size: 20`), well under
PostgreSQL's default `max_connections = 100`. A count far above 20 means something else is
connecting — another app instance, a leftover `psql`, or a monitoring tool. Terminate
strays:

```bash
docker compose exec db psql -U wv_app -d postgres -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'wealthview' AND pid <> pg_backend_pid();"
```

---

## Docker Issues

### Container Keeps Restarting

**Symptom:** `./wv status` shows a container restarting or with an uptime that keeps
resetting.

**Fix:**

```bash
./wv logs <service> --tail 100 --no-follow
```

Common causes by service:

| Service | Common causes |
|---------|--------------|
| `app` | Migration failure, database not ready, missing/invalid env vars, config validation failure, OOM |
| `db` | Disk full, corrupted data directory, wrong permissions on the volume |
| `backup` (prod) | Disk full, wrong database credentials, db not healthy |

All three are `restart: unless-stopped` in the production compose file, so a crash loop
keeps looping until you fix the cause.

### Docker Disk Full

**Symptom:** builds or container starts fail. `docker system df` shows high usage.

**Fix:**

```bash
# See what's using space
docker system df

# Remove stopped containers, unused networks, dangling images
docker system prune -f

# Aggressive: remove ALL unused images (will need re-download on next build)
docker image prune -a -f

# Check for large volumes
docker volume ls
docker system df -v
```

Do not prune volumes blindly — `pgdata` lives there.

### Network Issues Between Containers

**Symptom:** the app cannot reach the database. Logs show connection timeouts between
services.

**Fix:**

```bash
./wv restart
```

Containers communicate by service name (`db`, `app`). Verify the app is configured to
connect to `db` (not `localhost`) — `SPRING_DATASOURCE_URL` in both compose files is
`jdbc:postgresql://db:5432/wealthview`.

### Compose File Not Found / Wrong Stack

**Symptom:** `docker compose` fails with "no configuration file provided", or commands act
on the wrong stack.

**Fix:** use `./wv`, which resolves the compose file for you — dev mode uses
`docker-compose.yml`, and prod mode (any deployment with `WEALTHVIEW_VERSION` set in the
env file, or an explicit `WV_COMPOSE_FILE` in `wv.conf`) uses `docker-compose.prod.yml`.
Confirm what it resolved with:

```bash
./wv config-check
```

Raw compose calls need the file spelled out:

```bash
docker compose -f docker-compose.prod.yml ps
```

---

## Update and Rollback Issues

### Image Pull Failed (Step 3/5)

**Symptom:** `./wv update` stops at Step 3/5 with `Image pull failed` and a hint about
`WEALTHVIEW_VERSION` and registry access. Nothing was swapped — the old container is still
serving and the pre-update backup is on disk.

Production pulls the image CI published to `ghcr.io/<owner>/wealthview:<version>`; it does
not build one. Reproduce the raw error to see which cause it is:

```bash
docker compose -f docker-compose.prod.yml pull app
```

| Docker says | Cause | Fix |
|---|---|---|
| `denied` / `unauthorized` / `authentication required` | The GHCR package is private. **This is how the first CI push leaves it, even for a public repo.** | Make the package public (repo → Packages → the package → Package settings → Change visibility), or `docker login ghcr.io -u <username>` on this host with a `read:packages` Personal Access Token. |
| `manifest unknown` / `not found` | `WEALTHVIEW_VERSION` names a tag that was never published — a typo, or a tag whose CI run failed before the publish step. | Check the repo's Releases and Packages pages for the tags that exist; correct the pin. |
| `no matching manifest for linux/arm64` | The published image is `linux/amd64` only. | Run on x86-64, or build your own image on the ARM host and deploy it with `./wv update --no-pull`. |
| DNS / TLS errors reaching `ghcr.io` | No outbound HTTPS, or a proxy in the way. | Fix egress, or transfer the image manually and use `--no-pull`. |

Confirm which reference `wv` actually resolved — a stale `WEALTHVIEW_IMAGE` pointing at a
mirror you no longer run gives the same symptoms:

```bash
./wv config-check
docker compose -f docker-compose.prod.yml config | grep 'image:'
```

Credentials are stored per-user in `~/.docker/config.json`, so log in as the same user
(or `root`) that runs `wv`. Nothing about the token belongs in `.env` or `wv.conf`.

### `./wv update --build` Refuses to Run

**Symptom:** `--build was requested, but the app service in <file> has no 'build:' key`.

**Cause:** `docker-compose.prod.yml` is image-only by design — production runs the
CI-verified artifact. `update` checks for the key first because `docker compose build` on
a service with no build section exits 0 having done nothing, which would deploy a stale
image while reporting success.

**Fix:** drop `--build` and let it pull. If the image is already on the host (loaded from
a tarball, or built locally against a compose file that supports it), use `--no-pull`
instead. `--no-build` still works as a deprecated alias for `--no-pull`.

### Update Rolled Back Automatically

**Symptom:** `./wv update` reports a failed health check and reverts to the previous image
(exit code 1).

**Fix:** the new build is starting but not becoming healthy. Read the failure:

```bash
./wv logs app --tail 200 --no-follow
```

Then fix the cause and re-run `./wv update`. To keep the failing container up for
inspection instead, use `./wv update --no-rollback`.

### Rollback Also Failed (exit code 2)

**Symptom:** both the new and the previous image fail the health check.

**Cause:** usually the database, not the image — e.g. the new version applied a migration
the old image cannot validate against.

**Fix:** restore the pre-update backup that `./wv update` took before it started, then roll
the image back:

```bash
./wv backups
./wv restore backups/wealthview_<timestamp>_pre-update.dump
./wv rollback
```

### `./wv rollback` Says There Is No Previous Image

**Cause:** `.wv-previous-image` does not exist (no successful `./wv update` has run on this
host) or the image reference could not be determined at update time.

**Fix:** pin the known-good tag in `WEALTHVIEW_VERSION` and redeploy with `./wv update`.

A related failure: rollback aborts with *"has no parseable tag, so it cannot be
re-pinned"*. That means the recorded reference carries no tag at all — typically because
the app was running an untagged or digest-only image. Set `WEALTHVIEW_VERSION` (and
`WEALTHVIEW_IMAGE`, if you use a mirror) by hand and run `./wv up`.

---

## Backup and Restore Issues

### Backup Not Running

**Symptom:** no new files in the backups directory.

**Fix:**

```bash
./wv status                                  # is the backup container up? (prod only)
./wv logs backup --tail 20 --no-follow
./wv backups                                 # what does wv actually see?
./wv config-check                            # is WV_BACKUPS_DIR the directory you think?
```

The scheduled job runs daily at 3:00 AM UTC and only exists on the production stack. In dev
there is no `backup` container — take dumps with `./wv backup`.

### Restore Procedure

```bash
./wv backups                                                  # list with size + age
./wv verify backups/wealthview_auto_2026-08-16_03-00.dump          # prove it restores
./wv restore backups/wealthview_auto_2026-08-16_03-00.dump         # prompts before mutating
```

`./wv restore` stops the app, terminates lingering connections, runs
`pg_restore --clean --if-exists`, restarts the app, and waits for the health check. Files
ending in `.age` are decrypted on the fly using `BACKUP_ENCRYPTION_KEY_FILE`.

Deeper backup diagnostics live in [Backups](backups.md#troubleshooting).

---

## Cross-References

- For backup-specific troubleshooting (pg_dump failures, encryption, restore issues), see
  [Backups](backups.md#troubleshooting).
- For the full `wv` command surface, see the
  [Operations Handbook](../deployment/operations.md) or run `wv help`.
- For TLS/certificate issues, see [TLS & Nginx](../deployment/tls-and-nginx.md).
- For upgrade problems, see
  [Upgrading](../deployment/upgrading.md#troubleshooting-failed-upgrades).
- For log configuration, metrics, and parsing, see
  [Monitoring and Logging](monitoring-and-logging.md).
- For scheduled jobs and database maintenance, see [Maintenance](maintenance.md).
