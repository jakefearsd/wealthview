[<- Back to README](../../README.md)

# Upgrading WealthView

This guide covers how to update your WealthView deployment, how database
migrations work, and how to roll back if something goes wrong.

The whole upgrade is one command — `./wv update` — which takes a pre-update
backup, swaps the image, health-checks the result, and automatically rolls back
if the new version doesn't come up. The sections below explain what it does and
what to do when it doesn't work.

## Pre-Upgrade Checklist

1. **Check for breaking changes.** Read `CHANGELOG.md` for the range you are
   crossing, then skim the commits:
   ```bash
   git fetch origin --tags
   git log --oneline HEAD..v1.2.4
   ```
   Look for commits with `BREAKING CHANGE` in the message or `db(persistence)`
   commits that alter existing tables.

2. **Note the version you are on.** `./wv update` records the running image tag
   automatically, but write it down anyway:
   ```bash
   grep '^WEALTHVIEW_VERSION=' .env
   git describe --tags
   ```

3. **Confirm the stack is up.** `./wv update` refuses to run if the `db`
   container is not running — it cannot take its pre-update backup otherwise.
   ```bash
   ./wv status
   ```

4. **Check you have a recent, restorable backup.** `./wv update` takes its own,
   but a backup you have actually verified is worth more:
   ```bash
   ./wv backups
   ./wv verify backups/wealthview_<ts>.dump
   ```

## Standard Upgrade Procedure

```bash
cd /opt/wealthview

# 1. Get the new code and check out the release tag.
git fetch origin --tags
git checkout v1.2.4

# 2. Pin the matching image tag in .env.
#    This is what docker-compose.prod.yml interpolates into
#    image: wealthview:${WEALTHVIEW_VERSION}
$EDITOR .env        # WEALTHVIEW_VERSION=1.2.4

# 3. Upgrade.
./wv update
```

### What `./wv update` does

1. Validates `.env` — every required variable present, none still `CHANGE_ME`.
2. Confirms the `db` container is running.
3. **Step 1/5** — takes a pre-update backup, labelled `pre-update`, into your
   backups directory as `wealthview_<UTC-timestamp>_pre-update.dump`.
4. **Step 2/5** — records the currently-running app image tag to
   `.wv-previous-image` (path overridable via `WV_PREVIOUS_IMAGE_FILE`), so
   `./wv rollback` has a target. If it cannot determine the image, it warns and
   continues — rollback will be unavailable.
5. **Step 3/5** — `docker compose build app`. Skipped with `--no-build`, in
   which case compose uses whatever `wealthview:${WEALTHVIEW_VERSION}` resolves
   to on the host (a locally loaded image, for example).
6. **Step 4/5** — `docker compose up -d --no-deps app`. Only the app container
   is recreated; the database keeps running and its volume is untouched.
7. **Step 5/5** — polls `/actuator/health` for up to 180 seconds. Flyway
   migrations run during this window, so a large migration can legitimately use
   most of it.

If step 5 fails, `update` rolls the app container back to the recorded image
automatically. Pass `--no-rollback` to leave the failing container in place for
inspection.

Exit codes:

| Code | Meaning |
|---|---|
| `0` | Update succeeded and the app is healthy. |
| `1` | Update failed; rollback succeeded (or was disabled). |
| `2` | Update failed **and** rollback also failed — manual intervention needed. |

Check that the upgrade succeeded:

```bash
./wv status
./wv logs --tail 50 --no-follow app
```

Flyway prints `Successfully applied N migrations to schema "public"` (or
`Schema "public" is up to date` when there was nothing new), and Spring Boot
prints `Started WealthviewApplication in <seconds>`.

### Upgrading a host with no source tree

If the server has only the containers and a system-wide `wv` install, there is
nothing to `git pull`. Load the new image onto the host first (see
[production-setup.md Appendix B](production-setup.md#appendix-b--deploysh-build-here-run-there)),
bump `WEALTHVIEW_VERSION` in the env file, and run:

```bash
sudo wv update --no-build
```

---

## How Flyway Migrations Work

WealthView uses [Flyway](https://flywaydb.org/) for database schema management.
Migrations live in
`backend/wealthview-persistence/src/main/resources/db/migration/` and are baked
into the app image, so they run automatically at startup. There is never a
manual SQL step.

### Versioned Migrations

Files named `V<NNN>__<description>.sql` run exactly once, in version order.
The current range is `V001__create_tenants_table.sql` through
`V080__create_mortality_rates.sql`. Flyway records which versions have been
applied in the `flyway_schema_history` table.

On each startup:
- Flyway checks which migrations have already been applied
- Only new migrations (higher version numbers) are executed
- Already-applied migrations are skipped

### Repeatable Migrations

Files named `R__<description>.sql` re-run whenever their content changes;
Flyway tracks them by checksum. WealthView ships nine of them, all seed data
for reference tables — tax brackets, LTCG brackets, IRMAA tiers, standard
deductions, state tax brackets, asset-class returns, security asset classes,
mortality rates, and stock prices. Expect them to re-apply on any release that
refreshes those tables.

### Immutability

Versioned migrations are immutable once committed. If Flyway detects that a
previously applied migration has been modified (checksum mismatch), the
application refuses to start. This is intentional — it prevents silent data
corruption.

If you see a checksum mismatch error, it means a migration file was altered
after it was applied. The correct fix is to restore from backup and reapply
with the original migration, or create a new migration to fix the issue.

---

## Rollback Procedure

Flyway does **not** support automatic schema rollback. `./wv rollback` reverts
the *application image* only — the database is not rewound. If the new version
applied schema changes you cannot live with, restore the pre-update dump first.

### Image-only rollback

```bash
./wv rollback
```

This reads `.wv-previous-image` (written by the last `./wv update`), recreates
the app container pinned to that tag, and waits up to 120 seconds for the
health check. It fails with a clear error if no previous image was ever
recorded.

Afterwards, set `WEALTHVIEW_VERSION` in `.env` back to the old tag so a later
`./wv up` doesn't silently pull you forward again.

### Full rollback (image + database)

```bash
# 1. Find the pre-update dump.
./wv backups

# 2. Restore it. This prompts, stops the app, terminates lingering
#    connections, runs pg_restore --clean --if-exists, restarts the app,
#    and waits for the health check.
./wv restore backups/wealthview_<ts>_pre-update.dump

# 3. Put the old image back.
./wv rollback

# 4. Revert the checkout and the pin so the next `./wv up` is consistent.
git checkout <previous-tag>
$EDITOR .env        # WEALTHVIEW_VERSION=<previous-version>
```

`.dump.age` files are decrypted on the fly using `BACKUP_ENCRYPTION_KEY_FILE`.
For unattended use, `WV_ASSUME_YES=1 ./wv restore <file>` skips the prompt.

### Verify

```bash
./wv status
curl -s https://wealthview.example.com/actuator/health
```

---

## Checking the Current Version

The image tag in use is the authority:

```bash
grep '^WEALTHVIEW_VERSION=' .env
docker compose -f docker-compose.prod.yml images app
```

On a source checkout you can also read the git tag:

```bash
git describe --tags
```

And the startup logs carry the Spring Boot version and the Flyway summary:

```bash
./wv logs --no-follow app | grep -E "(Started|Successfully applied|up to date)"
```

---

## What CI does and does not do

`.github/workflows/backend-verify.yml` runs **only** on a `v*` tag push (plus a
manual `workflow_dispatch`). It is a three-job pipeline:

1. `mvn clean verify -DskipITs` — unit tests, `@DataJpaTest` Testcontainers
   tests, and the enforced PMD / CPD / SpotBugs / Checkstyle / JaCoCo gates.
2. The full `wealthview-app` Failsafe HTTP integration suite against a
   Testcontainers PostgreSQL.
3. `docker build -t wealthview:<tag> .` — proves the release image assembles
   from verified code.

The workflows for the web frontend, shared workspace, mobile app, admin scripts
(shellcheck + bats), and the gitleaks secret scan use the same tag-only
trigger.

**There is no auto-deploy and no image registry push.** A green tag build tells
you the release is sound; you still deploy it yourself on the server with
`./wv update`.

---

## Troubleshooting Failed Upgrades

### Migration Failure

**Symptom:** The app container exits on startup with a Flyway error, and
`./wv update` reports exit code 1 after auto-rolling back.

```bash
./wv logs --no-follow app | grep -i flyway
```

**Fix:** Do **not** modify the failed migration file. You have two options:

1. **Fix forward:** Create a new migration that corrects the issue, cut a new
   tag, and re-run `./wv update`.
2. **Restore from backup:** Follow the full rollback procedure above, fix the
   migration in your development environment, and redeploy.

Inspect `flyway_schema_history` directly if you need to see which migration
stopped:

```bash
./wv psql
# \x on
# SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;
```

### App Won't Start After Upgrade

**Symptom:** The app container keeps restarting or the health check fails.

```bash
./wv logs --tail 100 --no-follow app
```

Common causes:

- **Configuration validation.** `ProductionConfigValidator` aborts startup with
  a `SECURITY:` prefixed message when a secret is blank, too short, a known
  development default, or `LOCAL_DEV_*`-prefixed; when `CORS_ORIGIN` is empty
  or not `https://` under `prod`; or when `SPRING_PROFILES_ACTIVE` combines
  `prod` with `dev`/`docker`.
- **Missing environment variable.** A new version may require a new variable.
  Diff your `.env` against the new `.env.example`:
  ```bash
  diff <(grep -oE '^[A-Z_]+' .env | sort -u) \
       <(grep -oE '^#? ?[A-Z_]+=' .env.example | tr -d '#= ' | sort -u)
  ```
- **Database connectivity.** Confirm `db` is healthy (`./wv status`) and that
  `DB_PASSWORD` in `.env` matches the role password in the running database. If
  they drifted, `./wv psql` then
  `ALTER USER wv_app WITH PASSWORD '<value-from-env>';`.
- **Out of memory.** Check `docker stats` and free memory on the host.

### Container Build Fails

**Symptom:** `./wv update` fails at Step 3/5 with a build error.

```bash
docker system df
df -h
```

**Fix:** Free up Docker disk space, then re-run:

```bash
docker system prune -f
docker image prune -a -f       # more aggressive: removes all unused images
```

The build is three stages — a Node 24 Alpine frontend build, a
`maven:3.9-eclipse-temurin-25` backend build, and an
`eclipse-temurin:25-jre-alpine` runtime — so it needs network access for both
npm and Maven and several GB of scratch space.

### Health Check Fails After a Successful Start

**Symptom:** Containers are running but `/actuator/health` returns an error or
times out.

**Fix:** Give it 30–60 seconds; the container HEALTHCHECK has a 60-second start
period and Flyway may still be applying migrations. If it persists, check the
logs for database connection errors. Note that `./wv status` probes
`http://localhost:${APP_PORT}/actuator/health` — if your app is bound to
loopback on a non-default port behind a proxy, set `WV_APP_PORT` or
`WV_HEALTH_URL` in `wv.conf` so the probe targets the right URL.

### Rollback Also Failed (exit code 2)

The new image is unhealthy *and* the old one would not come back. The
pre-update backup is still on disk. Recover manually:

```bash
./wv backups
./wv restore backups/wealthview_<ts>_pre-update.dump
# then pin .env to a known-good WEALTHVIEW_VERSION and:
./wv up --no-build
```

---

## Related Guides

- [Production Setup](production-setup.md) — initial deployment
- [Operations Handbook](operations.md) — every `wv` subcommand in detail
- [Cloudflare Tunnel](cloudflared.md) — self-hosted deployment via cloudflared
- [TLS and Nginx](tls-and-nginx.md) — host-managed TLS
- [Security Hardening](security-hardening.md) — securing your deployment
