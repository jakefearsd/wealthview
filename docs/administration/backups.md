[← Back to README](../../README.md)

# WealthView Backup Operations Guide

## Architecture Overview

WealthView uses a dedicated Docker container (`backup`) to perform scheduled PostgreSQL backups. The container runs alongside the main application in `docker-compose.prod.yml`.

**How it works:**

- The `backup` container is built from `infra/backup/Dockerfile` — Alpine 3.20 with `postgresql16-client` and busybox `crond`.
- `crond` runs inside the container, executing `/backup.sh` on a schedule defined in `infra/backup/crontab`.
- `backup.sh` uses `pg_dump -Fc` (custom format, compressed) to dump the `wealthview` database to `/backups/` inside the container.
- The `/backups/` directory is bind-mounted to `./backups/` on the host, so dump files persist outside the container.
- After each backup, files older than `BACKUP_RETENTION_DAYS` are automatically deleted.
- The container connects to the `db` service over the Docker network using the standard `PG*` environment variables (`PGHOST=db`, `PGUSER=wv_app`, etc.).

**Backup file naming:** `wealthview_YYYY-MM-DD_HH-MM.dump`

---

## Initial Setup on a VPS

### Prerequisites

- Docker and Docker Compose installed
- The WealthView repo (or at minimum the `docker-compose.prod.yml`, `infra/backup/` directory, and `.env` file) present on the VPS
- A running PostgreSQL container (the `db` service)

### Directory permissions

Create the backups directory on the host and ensure the container can write to it:

```bash
mkdir -p ./backups
chmod 755 ./backups
```

The backup container runs as root (Alpine default), so permissions are typically not an issue. If you run Docker rootless, ensure the mapped UID has write access to `./backups`.

### Environment variables

Your `.env` file (next to `docker-compose.prod.yml`) needs:

```env
DB_PASSWORD=<your-database-password>
BACKUP_RETENTION_DAYS=14          # optional, defaults to 14
```

`DB_PASSWORD` is shared with the `db` and `app` services — the backup container reuses it.

---

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PGHOST` | `db` | Set in compose; the database hostname (Docker service name) |
| `PGUSER` | `wv_app` | Set in compose; the PostgreSQL user |
| `PGPASSWORD` | (from `DB_PASSWORD`) | Set in compose; the database password |
| `PGDATABASE` | `wealthview` | Set in compose; the database name |
| `BACKUP_RETENTION_DAYS` | `14` | How many days to keep old backups before auto-deleting |

All `PG*` variables are set in `docker-compose.prod.yml` and should not need changing unless you've customized your database setup. `BACKUP_RETENTION_DAYS` can be set in your `.env` file.

### Backup Schedule

The default schedule is **daily at 3:00 AM UTC**, defined in `infra/backup/crontab`:

```
0 3 * * * /backup.sh >> /proc/1/fd/1 2>&1
```

Output is redirected to the container's stdout so it appears in `docker compose logs`.

To change the schedule, see [Changing the Schedule](#changing-the-schedule) below.

---

## Starting the Backup Service

The backup service is defined in `docker-compose.prod.yml` alongside the other services. Start it with:

```bash
docker compose -f docker-compose.prod.yml up -d backup
```

Or start everything together:

```bash
docker compose -f docker-compose.prod.yml up -d
```

The backup container waits for the `db` service to be healthy before starting (via `depends_on: db: condition: service_healthy`).

---

## Verifying Backups Are Running

### Check container status

```bash
docker compose -f docker-compose.prod.yml ps backup
```

You should see the backup container with status `Up`.

### Check logs

```bash
docker compose -f docker-compose.prod.yml logs backup
```

Successful backup output looks like:

```
2026-03-11T03:00:00+00:00 Starting backup: wealthview_2026-03-11_03-00.dump
2026-03-11T03:00:05+00:00 Backup complete: wealthview_2026-03-11_03-00.dump (12M)
```

If old backups were cleaned up:

```
2026-03-11T03:00:05+00:00 Cleaned up 1 backup(s) older than 14 days
```

### List backup files

```bash
ls -lh ./backups/
```

### Run a manual backup

To test immediately without waiting for the schedule:

```bash
docker compose -f docker-compose.prod.yml exec backup /backup.sh
```

Then verify the file was created:

```bash
ls -lh ./backups/wealthview_*.dump
```

---

## Restoring from a Backup

### Using `restore.sh` (recommended)

The repo includes an interactive restore script at `infra/backup/restore.sh`. Run it from the project root (where `docker-compose.prod.yml` lives):

```bash
./infra/backup/restore.sh backups/wealthview_2026-03-11_03-00.dump
```

**What it does:**

1. Asks for confirmation (the restore replaces all data in the database)
2. Stops the `app` service to avoid active database connections
3. Runs `pg_restore --clean --if-exists` against the `db` container
4. Restarts the `app` service

**To see available backups:**

```bash
./infra/backup/restore.sh
```

Running with no arguments lists all `.dump` files in `backups/`.

### Manual Restore (without the script)

If you don't have the repo checked out or prefer manual control:

```bash
# 1. Stop the app to drop active connections
docker compose -f docker-compose.prod.yml stop app

# 2. Restore the database
docker compose -f docker-compose.prod.yml exec -T db \
  pg_restore --clean --if-exists -U wv_app -d wealthview < backups/wealthview_2026-03-11_03-00.dump

# 3. Restart the app
docker compose -f docker-compose.prod.yml start app
```

**Notes:**

- `--clean` drops existing objects before restoring, so you get an exact copy of the backed-up state.
- `--if-exists` prevents errors if an object doesn't exist yet (e.g., first-time restore).
- `-T` disables TTY allocation, which is required when piping the file via stdin.
- `pg_restore` with custom format (`.dump`) may emit warnings about objects that don't exist — this is normal and safe to ignore.

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
ls -lt ./backups/wealthview_*.dump | head -5
```

The most recent file should be from the last scheduled run (default: 3 AM UTC today).

**Is the container running?**

```bash
docker compose -f docker-compose.prod.yml ps backup
```

**Any errors in logs?**

```bash
docker compose -f docker-compose.prod.yml logs --tail=20 backup
```

Look for `ERROR: pg_dump failed` lines.

**Disk usage**

```bash
du -sh ./backups/
```

Each dump is typically a few MB for a small database. Monitor disk usage if you increase retention or your data grows significantly.

### Quick health check one-liner

```bash
echo "Last backup:" && ls -lt ./backups/wealthview_*.dump 2>/dev/null | head -1 && echo "Total size:" && du -sh ./backups/
```

---

## Troubleshooting

### Backup container won't start

**Symptom:** Container exits immediately or stays in `Restarting` state.

**Check:**
```bash
docker compose -f docker-compose.prod.yml logs backup
```

**Common causes:**
- The `db` service isn't healthy yet. The backup container depends on `db: condition: service_healthy`, so it won't start until PostgreSQL is ready.
- Missing `.env` file or `DB_PASSWORD` not set.

### `pg_dump` fails with "connection refused"

**Cause:** The database container isn't running or isn't reachable.

**Fix:**
```bash
# Verify db is running and healthy
docker compose -f docker-compose.prod.yml ps db

# Test connectivity from the backup container
docker compose -f docker-compose.prod.yml exec backup pg_isready
```

### `pg_dump` fails with "authentication failed"

**Cause:** `DB_PASSWORD` in `.env` doesn't match what the `db` container was initialized with.

**Note:** PostgreSQL only reads `POSTGRES_PASSWORD` on first initialization. If you changed the password in `.env` after the database was created, the old password is still active. You need to either:
- Change the password inside PostgreSQL: `ALTER USER wv_app PASSWORD 'new_password';`
- Or delete the pgdata volume and reinitialize (destroys all data): `docker compose -f docker-compose.prod.yml down -v`

### No backup files appearing

**Check:**
1. Is the container running? `docker compose -f docker-compose.prod.yml ps backup`
2. Has the scheduled time passed? Default is 3 AM UTC.
3. Run a manual backup to test: `docker compose -f docker-compose.prod.yml exec backup /backup.sh`
4. Check permissions on the host `./backups/` directory.

### Disk full

**Symptom:** `pg_dump` fails or produces a 0-byte file.

**Fix:**
1. Check disk usage: `df -h`
2. Reduce retention: set `BACKUP_RETENTION_DAYS=7` in `.env` and restart the backup container
3. Manually delete old backups: `rm ./backups/wealthview_2026-01-*.dump`
4. Run a manual backup to trigger cleanup: `docker compose -f docker-compose.prod.yml exec backup /backup.sh`

### Restore fails with "database is being accessed by other users"

**Cause:** The app (or another client) still has active connections.

**Fix:** Make sure you stop the app before restoring:
```bash
docker compose -f docker-compose.prod.yml stop app
```

If connections persist (e.g., from a monitoring tool), forcibly terminate them:
```bash
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

This is usually harmless — it means `--clean` tried to drop objects that didn't exist. The data is still restored correctly. Verify by checking the app after restart.
