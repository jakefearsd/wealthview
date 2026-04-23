[<- Back to README](../../README.md)

# Upgrading WealthView

This guide covers how to update your WealthView deployment, how database migrations
work, and how to roll back if something goes wrong.

## Pre-Upgrade Checklist

Before upgrading, complete these steps:

1. **Check for breaking changes.** Review the commit history since your current version:
   ```bash
   git fetch origin main
   git log --oneline HEAD..origin/main
   ```
   Look for commits with `BREAKING CHANGE` in the message or `db(persistence)` commits
   that alter existing tables.

2. **Verify a recent backup exists.** Check that the automatic backup ran recently:
   ```bash
   ls -lt backups/ | head -5
   ```
   If the latest backup is stale, run a manual backup:
   ```bash
   docker compose -f docker-compose.prod.yml exec backup \
     pg_dump -Fc -U wv_app -h db wealthview > backups/wealthview_pre_upgrade.dump
   ```

3. **Note the current version.** Record it in case you need to roll back:
   ```bash
   git log --oneline -1
   ```

## Standard Upgrade Procedure

The upgrade process is straightforward -- pull new code and rebuild:

```bash
cd /path/to/wealthview

# Pull latest changes
git pull origin main

# Rebuild and restart all containers
docker compose -f docker-compose.prod.yml up --build -d
```

That's it. Here is what happens automatically:

- Docker rebuilds the application image with the latest frontend and backend code
- The app container starts and Flyway runs any new database migrations
- The React frontend is rebuilt as part of the Docker image build
- No manual SQL or migration commands are ever needed

Check that the upgrade succeeded:

```bash
# Verify all containers are running
docker compose -f docker-compose.prod.yml ps

# Check the health endpoint
curl -s https://yourdomain.com/actuator/health

# Review startup logs for migration output
docker compose -f docker-compose.prod.yml logs --tail 50 app
```

---

## How Flyway Migrations Work

WealthView uses [Flyway](https://flywaydb.org/) for database schema management.
Understanding how it works helps you troubleshoot upgrade issues.

### Versioned Migrations

Files named `V<NNN>__<description>.sql` (e.g., `V001__create_tenants_table.sql`
through `V034__add_property_financial_fields.sql`) run exactly once, in version order.
Flyway tracks which versions have been applied in a `flyway_schema_history` table.

On each startup:
- Flyway checks which migrations have already been applied
- Only new migrations (higher version numbers) are executed
- Already-applied migrations are skipped

### Repeatable Migrations

Files named `R__<description>.sql` (e.g., `R__seed_standard_deductions.sql`) re-run
whenever their content changes. Flyway tracks them by checksum. These are used for
seed data that may be updated over time.

### Immutability

Versioned migrations are immutable once committed. If Flyway detects that a
previously applied migration has been modified (checksum mismatch), the application
will refuse to start. This is intentional -- it prevents silent data corruption.

If you see a checksum mismatch error, it means a migration file was altered after
it was applied. The correct fix is to restore from backup and reapply with the
original migration, or create a new migration to fix the issue.

---

## Rollback Procedure

Flyway does **not** support automatic rollback. If an upgrade causes problems, you
must restore from a database backup and revert the code.

### Step 1: Stop the Application

```bash
docker compose -f docker-compose.prod.yml stop app
```

This stops the app while keeping the database running (needed for restore).

### Step 2: Restore the Database

Use the restore script with your most recent pre-upgrade backup:

```bash
./infra/backup/restore.sh backups/wealthview_YYYY-MM-DD_HH-MM.dump
```

Replace the filename with the actual backup file from before the upgrade.

### Step 3: Revert the Code

Check out the previous working version:

```bash
git checkout <previous-commit-hash>
```

Use the commit hash you noted in the pre-upgrade checklist.

### Step 4: Rebuild and Restart

```bash
docker compose -f docker-compose.prod.yml up --build -d
```

### Step 5: Verify

```bash
docker compose -f docker-compose.prod.yml ps
curl -s https://yourdomain.com/actuator/health
```

---

## Checking the Current Version

On the server:

```bash
git log --oneline -1
```

You can also check the application startup logs for the Spring Boot version and
Flyway migration summary:

```bash
docker compose -f docker-compose.prod.yml logs app | grep -E "(Started|Successfully applied)"
```

---

## Troubleshooting Failed Upgrades

### Migration Failure

**Symptom:** App container exits on startup with a Flyway error in the logs.

```bash
docker compose -f docker-compose.prod.yml logs app | grep -i flyway
```

**Fix:** Do **not** modify the failed migration file. You have two options:

1. **Fix forward:** Create a new migration that corrects the issue, then redeploy.
2. **Restore from backup:** Follow the rollback procedure above, fix the migration
   in your development environment, and redeploy.

### App Won't Start After Upgrade

**Symptom:** The app container keeps restarting or the health check fails.

**Diagnose:**
```bash
docker compose -f docker-compose.prod.yml logs --tail 100 app
```

Common causes:
- **Database connectivity:** Verify the database is running and `.env` credentials
  are correct
- **Missing environment variable:** A new version may require a new `.env` variable.
  Check the release notes.
- **Out of memory:** Check `docker stats` and increase container memory limits if
  needed

### Container Build Fails

**Symptom:** `docker compose up --build` fails during the image build.

**Diagnose:**
```bash
# Check available disk space
docker system df
df -h
```

**Fix:** Free up Docker disk space:
```bash
# Remove unused images, containers, and build cache
docker system prune -f

# For more aggressive cleanup (removes all unused images)
docker image prune -a -f
```

### Health Check Fails After Successful Start

**Symptom:** Containers are running but `/actuator/health` returns an error or
times out.

**Fix:** Wait 30-60 seconds for the application to fully initialize. If it persists,
check the logs for database connection errors or migration issues.

---

## Related Guides

- [Production Setup](production-setup.md) -- initial deployment
- [Cloudflare Tunnel](cloudflared.md) -- self-hosted deployment via cloudflared
- [TLS and Nginx](tls-and-nginx.md) -- host-managed TLS
- [Security Hardening](security-hardening.md) -- securing your deployment
