[← Back to README](../../README.md)

# WealthView Backup Operations Guide

## Architecture Overview

WealthView has two backup paths, and they coexist:

1. **On-demand backups via `./wv backup`** — the admin command surface (`bin/wv` +
   `bin/wv-lib/`). This is what you run before an experiment, before an upgrade, or any
   time you want a dump right now. It can encrypt with `age` and copy off-host.
2. **Scheduled backups via the `backup` container** — a small cron container defined in
   `docker-compose.prod.yml` (production stack only). It takes an unattended nightly dump.

Both write into the same backups directory, and `./wv backups` / `./wv restore` /
`./wv verify` operate on files produced by either.

> Run `./wv <subcommand>` from a source checkout. On a production server where `wv` is
> installed to `/usr/local/bin/wv`, drop the `./` — the subcommands are identical. See
> [Operations Handbook](../deployment/operations.md) for the install layout and
> `wv help` for the operator man page.

**How the scheduled container works:**

- The `backup` container is built from `infra/backup/Dockerfile` — Alpine 3.20 (pinned by
  digest) with `postgresql16-client` and busybox `crond`.
- `crond` runs inside the container, executing `/backup.sh` on the schedule in
  `infra/backup/crontab`.
- `backup.sh` uses `pg_dump -Fc` (custom format, compressed) to dump the `wealthview`
  database into `/backups/` inside the container.
- `/backups/` is bind-mounted to `./backups/` **relative to the compose file**, so dump
  files persist outside the container.
- After each run, the container deletes only the dumps it produced itself —
  `wealthview_auto_*.dump[.age]`, plus the legacy `wealthview_YYYY-MM-DD_HH-MM.dump[.age]`
  naming for scheduled files already on disk — older than `BACKUP_RETENTION_DAYS`.
- The container reaches the `db` service over the Docker network using the standard `PG*`
  environment variables (`PGHOST=db`, `PGUSER=wv_app`, ...), all set in the compose file.

**Backup file naming:**

| Producer | Pattern | Example |
|---|---|---|
| `backup` container | `wealthview_auto_YYYY-MM-DD_HH-MM.dump` | `wealthview_auto_2026-08-16_03-00.dump` |
| `./wv backup` | `wealthview_<UTC-timestamp>.dump` | `wealthview_2026-08-16T09-12-44Z.dump` |
| `./wv backup --label X` | `wealthview_<UTC-timestamp>_X.dump` | `wealthview_2026-08-16T09-12-44Z_pre-update.dump` |
| `./wv backup --encrypt` | ...with `.age` appended | `wealthview_2026-08-16T09-12-44Z.dump.age` |

The `auto_` marker is load-bearing: it is how retention tells the container's own dumps
apart from operator-initiated ones. The `wealthview_` prefix is kept so the existing
listing globs in `bin/wv-lib/backups.sh` and `infra/backup/restore.sh` still find them.

**Two retention facts worth knowing up front:**

- The cleanup sweep matches only `wealthview_auto_*.dump[.age]` and the legacy
  `wealthview_YYYY-MM-DD_HH-MM.dump[.age]` scheduled naming. On-demand `./wv backup`
  dumps in the same directory are never swept — their ISO-8601 timestamps always contain
  a `T` and a `Z`, so they cannot collide with the legacy pattern either. The sweep used
  to use a bare `wealthview_*.dump` glob, which aged pre-change safety dumps out from
  under the operator.
- That means **you** own the lifecycle of everything `./wv backup` writes, including the
  encrypted `.dump.age` files from `--encrypt`. Nothing prunes them automatically. Delete
  them yourself or you will slowly fill the disk.

---

## Where Backups Live

`./wv` resolves the backups directory in this order:

1. `WV_BACKUPS_DIR` from the config file (`/etc/wealthview/wv.conf` or `--config FILE`)
2. Source-tree fallback: `<repo>/backups/`

The `backup` container is independent of that: it always writes to `./backups` next to
`docker-compose.prod.yml`. On a production install where the compose file lives in
`/etc/wealthview/`, that means `/etc/wealthview/backups`. **Point `WV_BACKUPS_DIR` at the
same directory** (or symlink one to the other) so `./wv backups` sees the nightly dumps.

Check what is actually resolved:

```bash
./wv config-check
```

It prints the config file, env file, compose file, backups dir, project name, and whether
you are operating locally or against a remote host.

---

## Initial Setup on a VPS

### Prerequisites

- Docker and Docker Compose installed
- `docker-compose.prod.yml`, the `infra/backup/` directory, and a filled-in `.env` present
  on the VPS
- A running PostgreSQL container (the `db` service)
- Optional but recommended: `age` installed, for encrypted backups

### Directory permissions

```bash
mkdir -p ./backups
chmod 755 ./backups
```

The backup container runs as root (Alpine default), so permissions are rarely an issue. If
you run Docker rootless, make sure the mapped UID can write to `./backups`.

### Environment variables

Your `.env` file (next to the compose file, or `WV_ENV_FILE` on a production install)
needs at least:

```env
DB_PASSWORD=CHANGE_ME
BACKUP_RETENTION_DAYS=14
```

`DB_PASSWORD` is shared with the `db` and `app` services — the backup container reuses it.

Optional backup-related settings (all documented in `.env.example`):

| Variable | Used by | Description |
|---|---|---|
| `BACKUP_RETENTION_DAYS` | `backup` container | Days to keep `*.dump` files. Default `14` |
| `BACKUP_ENCRYPTION_RECIPIENT` | `./wv backup --encrypt`, `./wv migrate-out` | An `age` **public** key (`age1...`) |
| `BACKUP_ENCRYPTION_KEY_FILE` | `./wv restore`, `./wv verify`, `./wv migrate-in` | Path to the matching `age` identity file (`chmod 600`) |
| `BACKUP_REMOTE_DEST` | `./wv backup --remote` | `s3://bucket/path/`, `user@host:/dir/`, or `rsync://...` |

Generate an age key pair with:

```bash
age-keygen -o /etc/wealthview/backup.key   # prints "# public key: age1..." on stdout
chmod 600 /etc/wealthview/backup.key
```

Put the **public** key in `BACKUP_ENCRYPTION_RECIPIENT` and the **path to the identity
file** in `BACKUP_ENCRYPTION_KEY_FILE`. The identity file must never be committed.

---

## Configuration

### Backup container environment

| Variable | Default | Description |
|----------|---------|-------------|
| `PGHOST` | `db` | Set in compose; the database hostname (Docker service name) |
| `PGUSER` | `wv_app` | Set in compose; the PostgreSQL user |
| `PGPASSWORD` | (from `DB_PASSWORD`) | Set in compose; the database password |
| `PGDATABASE` | `wealthview` | Set in compose; the database name |
| `BACKUP_RETENTION_DAYS` | `14` | How many days to keep old `*.dump` files |

All `PG*` variables are set in `docker-compose.prod.yml` and should not need changing.
`BACKUP_RETENTION_DAYS` belongs in your `.env`.

### Backup schedule

The default schedule is **daily at 3:00 AM UTC**, defined in `infra/backup/crontab`:

```
0 3 * * * /backup.sh >> /proc/1/fd/1 2>&1
```

Output is redirected to the container's stdout so it appears in `./wv logs backup`.

To change it, see [Changing the Schedule](#changing-the-schedule) below.

---

## On-Demand Backups

```bash
./wv backup                                   # plain dump into the backups dir
./wv backup --encrypt                         # age-encrypted (needs BACKUP_ENCRYPTION_RECIPIENT)
./wv backup --label pre-experiment            # append _pre-experiment to the filename
./wv backup --encrypt --remote                # also copy to BACKUP_REMOTE_DEST
./wv backup --dry-run                         # print what would happen, change nothing
```

Behaviour worth knowing:

- The `db` container must be running — the dump streams through
  `docker compose exec -T db pg_dump -Fc`.
- The final path is printed on stdout as the last line, so scripts can capture it.
- With `--encrypt`, the plaintext dump is deleted after `age` succeeds; only the `.age`
  file remains.
- Without `--encrypt`, running in **prod mode** (i.e. `WEALTHVIEW_VERSION` is set in the
  env file) prints a warning that financial data is now sitting in plaintext on disk.
- `--remote` is best effort: a failed upload logs a warning but does not fail the backup.
  `s3://` needs the `aws` CLI; `user@host:/path` and `rsync://` need `rsync`.
- `--label` accepts letters, digits, dash, and underscore only.

`dev-backup.sh` still works — it is now a thin shim that execs `./wv backup`. New
automation should call `./wv backup` directly.

---

## Listing Backups

```bash
./wv backups          # or the alias: ./wv list-backups
```

Prints one row per `wealthview_*.dump` / `wealthview_*.dump.age` file in the backups
directory, newest first, with human-readable size and age:

```
FILE                                                     SIZE  AGE
wealthview_2026-08-16T09-12-44Z_pre-update.dump           12M  4m ago
wealthview_auto_2026-08-16_03-00.dump                          12M  6h 12m ago
```

Plain `ls -lh ./backups/` also works; `./wv backups` is just easier to read and honours
`WV_BACKUPS_DIR`.

---

## Verifying a Backup

A backup you have never restored is a hypothesis. `./wv verify` turns it into a fact
without touching production data:

```bash
./wv verify backups/wealthview_2026-08-16T09-12-44Z.dump
```

What it does:

1. Decrypts the file first if it ends in `.age` (needs `BACKUP_ENCRYPTION_KEY_FILE`).
2. Launches a throwaway `postgres:16` container with a random name and password.
3. Waits up to 30s for it to accept connections.
4. Runs `pg_restore --clean --if-exists` into it.
5. Prints the top 5 tables by live row count, then asserts at least 5 public tables exist.
6. Tears the container (and any temporary plaintext) down on exit.

Exit code `0` means a clean round-trip. Non-zero means the dump is empty, truncated, or
otherwise unusable — take a fresh backup and investigate disk space at backup time.

Verify at least the backup you are about to rely on, e.g. before a risky upgrade or a host
migration.

---

## Restoring from a Backup

### Using `./wv restore` (recommended)

```bash
./wv restore backups/wealthview_auto_2026-08-16_03-00.dump
./wv restore backups/wealthview_2026-08-16T09-12-44Z.dump.age   # auto-decrypts
./wv restore <file> --dry-run                                    # show the plan only
```

**What it does:**

1. Prints the target file and warns that this REPLACES all data, then prompts for
   confirmation (`WV_ASSUME_YES=1` skips the prompt for cron / scripted use).
2. Decrypts `.age` files to a temporary file using `BACKUP_ENCRYPTION_KEY_FILE`, and
   removes that temporary file on exit.
3. Stops the `app` service so it stops holding connections.
4. Terminates any remaining connections to the `wealthview` database.
5. Runs `pg_restore --clean --if-exists` inside the `db` container.
6. Starts `app` again and polls `/actuator/health` for up to 90 seconds.

Running `./wv restore` with no file argument prints the usage line and then lists the
available backups.

`dev-restore.sh` still works: with a file argument it execs `./wv restore`, and with no
arguments it execs `./wv backups`.

### Manual restore (without `./wv`)

If you are on a host without the admin scripts:

```bash
# 1. Stop the app to drop active connections
docker compose -f docker-compose.prod.yml stop app

# 2. Restore the database
docker compose -f docker-compose.prod.yml exec -T db \
  pg_restore --clean --if-exists -U wv_app -d wealthview < backups/wealthview_auto_2026-08-16_03-00.dump

# 3. Restart the app
docker compose -f docker-compose.prod.yml start app
```

**Notes:**

- `--clean` drops existing objects before restoring, so you get an exact copy of the
  backed-up state.
- `--if-exists` prevents errors when an object does not exist yet (e.g. first-time restore).
- `-T` disables TTY allocation, which is required when piping the file via stdin.
- `pg_restore` on a custom-format dump may emit warnings about objects that do not exist —
  this is normal and safe to ignore.
- The legacy helper `infra/backup/restore.sh` performs exactly these three steps against
  `docker-compose.prod.yml`. It predates `./wv` and knows nothing about encryption,
  connection termination, or the post-restore health check — prefer `./wv restore`.

---

## Moving to Another Host

`./wv migrate-out` packages everything a new host needs; `./wv migrate-in` unpacks it.

**On the source host:**

```bash
./wv migrate-out                       # writes backups/migrations/wealthview-bundle-<ts>.tar.gz
./wv migrate-out --dest /mnt/usb       # choose the output directory
./wv migrate-out --no-encrypt          # NOT recommended: the bundle contains the database
```

The bundle contains:

- an encrypted database backup taken at migration time (labelled `migration`)
- `.env.example` — a skeleton to fill in on the new host
- `infra/` — the backup container source
- `VERSION.pin` — the `WEALTHVIEW_VERSION` the dump was taken against
- `README.txt` — restore instructions

Encryption is the default and requires `BACKUP_ENCRYPTION_RECIPIENT`.

**On the destination host** (stack already up, `.env` populated, and
`BACKUP_ENCRYPTION_KEY_FILE` pointing at the matching age identity):

```bash
scp source-host:/path/to/wealthview-bundle-*.tar.gz .
./wv up
./wv migrate-in wealthview-bundle-*.tar.gz
```

`migrate-in` extracts the bundle, finds the dump inside it, prints the recorded
`WEALTHVIEW_VERSION` so you can pin `.env`, and then runs the same restore flow as
`./wv restore` (including the confirmation prompt).

---

## Starting the Scheduled Backup Service

The `backup` service is part of `docker-compose.prod.yml`, so it starts with the rest of
the production stack:

```bash
./wv up
```

To start only that container:

```bash
docker compose -f docker-compose.prod.yml up -d backup
```

The backup container waits for the `db` service to be healthy before starting (via
`depends_on: db: condition: service_healthy`) and is configured `restart: unless-stopped`.

Note that the dev stack (`docker-compose.yml`) has **no** `backup` service — it defines
only `db` and `app`. In dev, take backups with `./wv backup`.

---

## Verifying Backups Are Running

### Check container status

```bash
./wv status
```

You should see the `backup` container listed and `Up`.

### Check logs

```bash
./wv logs backup --tail 20 --no-follow
```

Successful backup output looks like:

```
2026-08-16T03:00:00+00:00 Starting backup: wealthview_auto_2026-08-16_03-00.dump
2026-08-16T03:00:05+00:00 Backup complete: wealthview_auto_2026-08-16_03-00.dump (12M)
```

If old backups were cleaned up:

```
2026-08-16T03:00:05+00:00 Cleaned up 1 backup(s) older than 14 days
```

### List backup files

```bash
./wv backups
```

### Run a manual backup

To exercise the container immediately without waiting for the schedule:

```bash
docker compose -f docker-compose.prod.yml exec backup /backup.sh
```

Or, more simply, take an on-demand dump through the admin command:

```bash
./wv backup --label manual-test
./wv backups
```

---

## Changing the Schedule

The cron schedule is baked into the container image via `infra/backup/crontab`.

1. Edit `infra/backup/crontab`:

   ```
   # Example: twice daily at 3 AM and 3 PM UTC
   0 3,15 * * * /backup.sh >> /proc/1/fd/1 2>&1
   ```

   Standard cron syntax: `minute hour day-of-month month day-of-week`

2. Rebuild and restart the backup container:

   ```bash
   docker compose -f docker-compose.prod.yml up -d --build backup
   ```

The `--build` flag forces a rebuild of the image with the updated crontab.

---

## Monitoring Backup Health

### Regular checks

**Are backups recent?**

```bash
./wv backups | head -5
```

The most recent file should be from the last scheduled run (default 3 AM UTC today) or
from your last manual `./wv backup`.

**Is the container running?**

```bash
./wv status
```

**Any errors in the logs?**

```bash
./wv logs backup --tail 20 --no-follow
```

Look for `ERROR: pg_dump failed` lines.

**Disk usage**

```bash
du -sh ./backups/
```

Each dump is typically a few MB for a small database. Watch disk usage if you raise
retention, accumulate `./wv backup` dumps (never auto-pruned, encrypted or not), or your
data grows significantly.

### Restore drill

Recency is not integrity. Periodically prove the newest dump actually restores:

```bash
./wv verify "$(ls -t backups/wealthview_*.dump | head -1)"
```

---

## Troubleshooting

### Backup container won't start

**Symptom:** Container exits immediately or stays in `Restarting` state.

**Check:**
```bash
./wv logs backup --tail 50 --no-follow
```

**Common causes:**
- The `db` service isn't healthy yet. The backup container depends on
  `db: condition: service_healthy`, so it won't start until PostgreSQL is ready.
- Missing env file or `DB_PASSWORD` not set. Run `./wv config-check`.

### `pg_dump` fails with "connection refused"

**Cause:** The database container isn't running or isn't reachable.

**Fix:**
```bash
# Verify db is running and healthy
./wv status

# Test connectivity from the backup container
docker compose -f docker-compose.prod.yml exec backup pg_isready
```

### `pg_dump` fails with "authentication failed"

**Cause:** `DB_PASSWORD` in the env file doesn't match what the `db` container was
initialized with. PostgreSQL only reads `POSTGRES_PASSWORD` on first initialization.

**Fix:** either change the password inside PostgreSQL to match your env file —

```bash
./wv psql
# then: ALTER USER wv_app WITH PASSWORD '<value from your env file>';
```

— or use `./wv rotate-secret DB_PASSWORD`, which generates a new value, writes it to the
env file, runs `ALTER USER` against the live database, and restarts the app so both sides
stay in sync.

### `./wv backup --encrypt` refuses to run

**Symptom:** `--encrypt requires BACKUP_ENCRYPTION_RECIPIENT ... in .env or env.`

**Fix:** generate an age key pair (`age-keygen -o /etc/wealthview/backup.key`), put the
public key in `BACKUP_ENCRYPTION_RECIPIENT`, and keep the identity file readable only by
the admin user. `./wv config-check` reports whether `age` is installed.

### `./wv restore` can't decrypt a `.age` file

**Symptom:** `Encrypted backup requires BACKUP_ENCRYPTION_KEY_FILE pointing to an age
identity file.`

**Fix:** set `BACKUP_ENCRYPTION_KEY_FILE` to the age **identity** file that pairs with the
recipient used at backup time. A public key in that variable will not work.

### No backup files appearing

**Check:**
1. Is the container running? `./wv status`
2. Has the scheduled time passed? Default is 3 AM UTC.
3. Run a manual backup to test: `./wv backup --label manual-test`
4. Is `WV_BACKUPS_DIR` pointing at the same directory the container bind-mounts? Run
   `./wv config-check` and compare with the `./backups` path next to the compose file.
5. Check permissions and free space on the host backups directory.

### Disk full

**Symptom:** `pg_dump` fails or produces a 0-byte file.

**Fix:**
1. Check disk usage: `df -h`
2. Reduce retention: set `BACKUP_RETENTION_DAYS=7` in the env file and restart the backup
   container
3. Delete old dumps by hand — remember everything `./wv backup` wrote, `.dump` and
   `.dump.age` alike, is never auto-pruned: `ls -lt backups/`, then remove the oldest
4. Trigger a run so cleanup executes:
   `docker compose -f docker-compose.prod.yml exec backup /backup.sh`

### Restore fails with "database is being accessed by other users"

**Cause:** The app (or another client) still has active connections.

**Fix:** `./wv restore` already stops `app` and terminates leftover connections. If you are
restoring manually, do the same:

```bash
docker compose -f docker-compose.prod.yml stop app

docker compose -f docker-compose.prod.yml exec db \
  psql -U wv_app -d postgres -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'wealthview' AND pid <> pg_backend_pid();"
```

Then retry the restore.

### Restore shows warnings but no errors

`pg_restore --clean` may emit warnings like:

```
pg_restore: warning: errors ignored on restore: X
```

This is usually harmless — `--clean` tried to drop objects that didn't exist. The data is
still restored correctly. `./wv restore` says as much and continues to the health check;
verify by logging in after the app comes back.

---

## Related Docs

- [Operations Handbook](../deployment/operations.md) — the full `wv` command surface
- [Maintenance](maintenance.md) — updates, rollback, disk space, scheduled jobs
- [Troubleshooting](troubleshooting.md) — diagnostics for the whole stack
