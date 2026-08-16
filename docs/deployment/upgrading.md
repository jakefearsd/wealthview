[<- Back to README](../../README.md)

# Upgrading WealthView

This guide covers how to update your WealthView deployment, how database
migrations work, and how to roll back if something goes wrong.

**Production pulls a published image; it does not build one.** CI publishes
`ghcr.io/<owner>/wealthview:<version>` on every `v*` tag, after the unit tests,
quality gates and the full integration suite pass, and cuts a GitHub Release
alongside it. The server needs neither the source tree nor a JDK — only Docker,
the compose file, the env file, and read access to the registry.

The whole upgrade is one command — `./wv update` — which takes a pre-update
backup, pulls the new image, swaps the container, health-checks the result, and
automatically rolls back if the new version doesn't come up. The sections below
explain what it does and what to do when it doesn't work.

## Before your first pull: registry access

The first CI push **creates the GHCR package as private, even for a public
repository.** Until that is changed, `./wv update` fails at the pull step with
an `unauthorized` / `denied` error. Sort this out once, before you need it:

**Option A — make the package public (simplest for a public repo).**
On GitHub, go to the repository → **Packages** → the `wealthview` package →
**Package settings** → **Change visibility** → *Public*. Anonymous pulls then
work from any host with no login at all.

**Option B — authenticate the host.** Keep the package private and give the
server a Personal Access Token (classic) with the **`read:packages`** scope
only:

```bash
# On the deployment host. Paste the token when prompted — do not put it on the
# command line, where it lands in shell history.
docker login ghcr.io -u <your-github-username>
```

Docker stores the credential in `~/.docker/config.json` for the user that ran
it, so log in as the same user (or `root`) that runs `wv`. Nothing about the
token belongs in `.env` or `wv.conf`.

## Pre-Upgrade Checklist

1. **Check for breaking changes.** Read the GitHub Release notes for the
   version you are moving to — CI builds them from the matching
   `## [<version>]` section of `CHANGELOG.md`, at
   `https://github.com/<owner>/wealthview/releases` — or read `CHANGELOG.md`
   directly for the whole range you are crossing. Look for `BREAKING CHANGE`
   entries or schema changes to existing tables.

2. **Note the version you are on.** `./wv update` records the running image
   reference automatically, but write it down anyway:
   ```bash
   grep '^WEALTHVIEW_VERSION=' .env
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
# 1. Pin the release you want in the env file. This is what
#    docker-compose.prod.yml interpolates into
#    image: ${WEALTHVIEW_IMAGE:-ghcr.io/jakefearsd/wealthview}:${WEALTHVIEW_VERSION}
$EDITOR /etc/wealthview/.env        # WEALTHVIEW_VERSION=1.2.5

# 2. Upgrade.
wv update
```

That is the whole procedure. There is no `git pull` and no build step: the
image referenced by the new tag already exists in the registry.

> **Never pin `WEALTHVIEW_VERSION=latest`.** CI publishes a `:latest` tag as a
> convenience pointer, but deploying it defeats `./wv rollback`, which recovers
> by re-pinning the tag that was running before the update — and a moving tag
> gives you no way to say which build that was. The compose file's error
> message says the same thing when the variable is unset.

### Using a fork or a private mirror

`WEALTHVIEW_IMAGE` (optional) overrides the repository half of the image
reference, so an air-gapped registry or a fork's package can be used without
editing the compose file:

```dotenv
WEALTHVIEW_IMAGE=registry.internal:5000/wealthview
WEALTHVIEW_VERSION=1.2.5
```

`./wv rollback` re-pins **both** halves from the recorded image reference, so a
mirrored deployment rolls back to the mirror rather than silently reverting to
the upstream default.

### What `./wv update` does

1. Validates `.env` — every required variable present, none still `CHANGE_ME`.
2. Confirms the `db` container is running.
3. **Step 1/5** — takes a pre-update backup, labelled `pre-update`, into your
   backups directory as `wealthview_<UTC-timestamp>_pre-update.dump`.
4. **Step 2/5** — records the currently-running app image tag to
   `.wv-previous-image` (path overridable via `WV_PREVIOUS_IMAGE_FILE`), so
   `./wv rollback` has a target. If it cannot determine the image, it warns and
   continues — rollback will be unavailable.
5. **Step 3/5** — `docker compose pull app`, fetching the image the compose file
   resolves for the pinned `WEALTHVIEW_VERSION`. This is the default **in prod
   mode**; in dev mode `update` builds instead, because the dev compose file
   builds from source and has no image to pull. See
   [Image acquisition flags](#image-acquisition-flags) to override it.
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

### Image acquisition flags

Step 3/5 is the only part of the sequence you can change:

| Flag | Effect |
|---|---|
| *(none)* | Follows the deployment mode: **prod pulls**, dev builds. Prod is image-only and must pull; the dev compose file's `app` service has no `image:` key at all and cannot be pulled. |
| `--no-pull` | Skip the fetch entirely and reuse whatever image is already on the host for the resolved tag. Use it after a `docker load`, or on a box with no registry access. |
| `--no-build` | **Deprecated alias for `--no-pull`.** Still works and does the same thing — under a pull-based flow, "don't build" means "don't fetch" — but it prints a deprecation warning. Update your runbooks. |
| `--build` | Build the image locally instead of pulling. |

`--build` **requires a compose file whose `app` service has a `build:` key, and
`docker-compose.prod.yml` deliberately has none.** `wv update` checks first and
refuses loudly, because `docker compose build` on a service with no build
section exits 0 having done nothing — it would deploy a stale image while
reporting success. Since building is already the dev default, passing `--build`
explicitly only matters on a host that must build locally against a
build-capable compose file (an air-gapped box, or bisecting an unreleased
commit).

Check that the upgrade succeeded:

```bash
./wv status
./wv logs --tail 50 --no-follow app
```

Flyway prints `Successfully applied N migrations to schema "public"` (or
`Schema "public" is up to date` when there was nothing new), and Spring Boot
prints `Started WealthviewApplication in <seconds>`.

### Upgrading a host with no source tree

This is now the normal case, not a special one. A server with only the
containers, the compose file, the env file and a system-wide `wv` install needs
nothing extra: bump `WEALTHVIEW_VERSION` in the env file and run
`sudo wv update`. The image comes from the registry.

The only variant worth knowing is the offline one. If the host cannot reach
GHCR, transfer the image yourself and skip the fetch:

```bash
# On a machine that can reach the registry:
docker pull ghcr.io/<owner>/wealthview:1.2.5
docker save ghcr.io/<owner>/wealthview:1.2.5 | gzip > wealthview-1.2.5.tar.gz
scp wealthview-1.2.5.tar.gz you@server:/tmp/

# On the server:
gunzip -c /tmp/wealthview-1.2.5.tar.gz | docker load
sudo $EDITOR /etc/wealthview/.env      # WEALTHVIEW_VERSION=1.2.5
sudo wv update --no-pull
```

The loaded image must carry the same repository name the compose file resolves
(`WEALTHVIEW_IMAGE`, default `ghcr.io/jakefearsd/wealthview`), or compose will
not find it locally and will try to pull after all.

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
the app container pinned to that reference, and waits up to 120 seconds for the
health check. It fails with a clear error if no previous image was ever
recorded.

In prod mode it re-pins **both** `WEALTHVIEW_IMAGE` and `WEALTHVIEW_VERSION`
from the recorded reference. Both halves matter now that the reference is
registry-qualified: restoring only the tag would move a mirrored or forked
deployment back onto the default upstream repository. If the recorded reference
has no parseable tag, rollback stops and tells you to set `WEALTHVIEW_VERSION`
by hand and run `wv up` — it will not guess. (A registry host:port such as
`registry.internal:5000/wealthview` is correctly treated as *untagged*, not as
a tag named `5000`.)

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

# 4. Revert the pin so the next `./wv up` is consistent.
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

The running container also carries the OCI labels CI stamped on it, including
the exact commit the image was built from:

```bash
docker inspect --format '{{json .Config.Labels}}' \
  "$(docker compose -f docker-compose.prod.yml ps -q app)"
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
3. Builds the release image with Buildx and **publishes it**: pushed to GHCR as
   `ghcr.io/<owner>/wealthview:<version>` and `:latest`, followed by a GitHub
   Release whose notes are the matching `## [<version>]` section of
   `CHANGELOG.md` plus a short "Deploying this release" footer. The job holds
   `contents: write` and `packages: write` for exactly those two steps.

Publishing happens **only for `refs/tags/v*`**. A manual `workflow_dispatch`
still builds the image — proving it assembles — but never pushes, so it cannot
move `:latest` or claim a version number that was never tagged.

The published image is **`linux/amd64` only**. The `Dockerfile` compiles the
whole Maven backend inside the build stage, so an emulated `arm64` build would
run the entire reactor under QEMU and take 30–60+ minutes. An arm64 host cannot
run the published image.

The workflows for the web frontend, shared workspace, mobile app, admin scripts
(shellcheck + bats), and the gitleaks secret scan use the same tag-only
trigger.

**There is still no auto-deploy.** CI publishes the artifact; a GitHub-hosted
runner cannot reach your server. You deploy it yourself by pinning
`WEALTHVIEW_VERSION` and running `./wv update`.

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

### Image Pull Fails

**Symptom:** `./wv update` stops at Step 3/5 with "Image pull failed" and a
hint about `WEALTHVIEW_VERSION` and registry access. Nothing was swapped — the
old container is still running and the pre-update backup is on disk.

Reproduce the raw error to see which of the three causes it is:

```bash
docker compose -f docker-compose.prod.yml pull app
```

| Docker says | Cause | Fix |
|---|---|---|
| `denied`, `unauthorized`, or `authentication required` | The GHCR package is private (**the default for the first CI push, even on a public repo**) and this host has no credential. | Make the package public, or `docker login ghcr.io` with a `read:packages` PAT — see [Before your first pull](#before-your-first-pull-registry-access). |
| `manifest unknown` / `not found` | `WEALTHVIEW_VERSION` names a tag that was never published — a typo, or a tag whose CI run failed before the publish step. | Check the repo's Releases and Packages pages for the tags that exist, then correct `WEALTHVIEW_VERSION`. |
| `no matching manifest for linux/arm64` | The host is ARM; the published image is `linux/amd64` only. | Run WealthView on an x86-64 host, or build your own image on the ARM box and load it, then `wv update --no-pull`. |
| DNS or TLS errors reaching `ghcr.io` | The host has no outbound HTTPS, or a proxy is in the way. | Fix egress, or use the offline transfer in [Upgrading a host with no source tree](#upgrading-a-host-with-no-source-tree). |

Also confirm the reference `wv` actually resolved — a stale `WEALTHVIEW_IMAGE`
pointing at a mirror you no longer run produces the same symptoms:

```bash
./wv config-check
docker compose -f docker-compose.prod.yml config | grep 'image:'
```

Disk space is worth ruling out too; a pull needs room to unpack layers:

```bash
docker system df
df -h
docker image prune -a -f       # removes all unused images
```

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
./wv up
```

---

## Related Guides

- [Production Setup](production-setup.md) — initial deployment
- [Operations Handbook](operations.md) — every `wv` subcommand in detail
- [Cloudflare Tunnel](cloudflared.md) — self-hosted deployment via cloudflared
- [TLS and Nginx](tls-and-nginx.md) — host-managed TLS
- [Security Hardening](security-hardening.md) — securing your deployment
