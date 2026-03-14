[← Back to README](../../README.md)

# Troubleshooting

This guide covers diagnosis and resolution of common WealthView production issues. Each section follows a symptom/cause/fix structure with copy-paste commands.

---

## Diagnostic Commands Quick Reference

| What | Command |
|------|---------|
| Container status | `docker compose -f docker-compose.prod.yml ps` |
| App logs (last 50 lines) | `docker compose -f docker-compose.prod.yml logs --tail=50 app` |
| DB logs (last 50 lines) | `docker compose -f docker-compose.prod.yml logs --tail=50 db` |
| nginx logs (last 50 lines) | `docker compose -f docker-compose.prod.yml logs --tail=50 nginx` |
| Health check | `curl -sf http://localhost/actuator/health \| jq` |
| Database size | `docker compose exec db psql -U wv_app wealthview -c "SELECT pg_size_pretty(pg_database_size('wealthview'));"` |
| Active DB connections | `docker compose exec db psql -U wv_app wealthview -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'wealthview';"` |
| Disk space | `df -h` |
| Docker disk usage | `docker system df` |
| Container resource usage | `docker stats --no-stream` |

---

## App Won't Start

### Database Not Ready

**Symptom:** App container restarts repeatedly. Logs show "Connection refused" or "Connection to localhost:5432 refused."

**Cause:** The PostgreSQL container has not finished initializing. This is common on first startup when PostgreSQL needs to run `initdb` and process the initial SQL scripts.

**Fix:** The app container depends on the db health check and will retry automatically. Wait 1-2 minutes for PostgreSQL to become ready. If it persists beyond that:

```bash
# Check db container status and health
docker compose -f docker-compose.prod.yml ps db

# Check db container logs for errors
docker compose -f docker-compose.prod.yml logs db
```

Common db startup failures: disk full, corrupted data directory, wrong `POSTGRES_PASSWORD` on first init.

### Migration Failure

**Symptom:** App logs show "Flyway migration failed", "Migration checksum mismatch", or a SQL syntax error during startup.

**Cause:** Either a versioned migration file was modified after it was already applied, or a new migration contains a SQL syntax error.

**Fix:**

- **Checksum mismatch:** A previously applied migration was modified. This is never allowed. Restore the original migration file content and redeploy. Check git history: `git log --oneline -- backend/wealthview-persistence/src/main/resources/db/migration/`
- **SQL syntax error in a new migration:** Fix the SQL in the migration file and redeploy.
- **Database in a bad state (partially applied migration):** Restore from backup. See [backups documentation](backups.md) for the restore procedure using `infra/backup/restore.sh`.

To check migration status:

```bash
docker compose exec db psql -U wv_app wealthview -c "
  SELECT version, description, success
  FROM flyway_schema_history
  ORDER BY installed_rank DESC
  LIMIT 10;"
```

### Port Conflict

**Symptom:** `docker compose up` fails with "Bind for 0.0.0.0:80: address already in use" or similar.

**Fix:**

```bash
# Find what's using port 80
ss -tlnp | grep :80

# Or for port 443
ss -tlnp | grep :443
```

Stop the conflicting service, or change the port mapping in `docker-compose.prod.yml`.

### Out of Memory

**Symptom:** App container is killed by the OOM killer. Docker logs show "Killed" or the container exits with code 137.

**Fix:**

1. Check system memory: `free -h`
2. Check container memory usage: `docker stats --no-stream`
3. Reduce JVM heap if needed — add to `docker-compose.prod.yml`:
   ```yaml
   environment:
     JAVA_OPTS: "-Xmx384m -Xms256m"
   ```
4. Check for other processes consuming memory on the host.

### Environment Variable Missing

**Symptom:** App starts but crashes immediately with errors about missing configuration.

**Fix:** Verify all required environment variables are set in `.env`:

```bash
# Required variables
grep DB_PASSWORD .env
grep JWT_SECRET .env
grep SUPER_ADMIN_PASSWORD .env
```

| Variable | Requirement |
|----------|------------|
| `DB_PASSWORD` | Must match the password PostgreSQL was initialized with |
| `JWT_SECRET` | Must be at least 32 characters |
| `SUPER_ADMIN_PASSWORD` | Password for the auto-created super-admin account |
| `FINNHUB_API_KEY` | Optional — price sync disabled without it |
| `ZILLOW_ENABLED` | Optional — defaults to `false` |
| `BACKUP_RETENTION_DAYS` | Optional — defaults to 14 |

---

## Can't Log In

### Wrong Credentials

**Symptom:** Login returns 401 Unauthorized.

**Fix:** Verify the correct email and password:

- **Super-admin:** `admin@wealthview.local` with the value of `SUPER_ADMIN_PASSWORD` from `.env`
- **Demo user (docker profile only):** `demo@wealthview.local` / `demo123`

Passwords are case-sensitive. Check for trailing whitespace in `.env`.

### Tenant Disabled

**Symptom:** All users in a tenant receive 401 on login, even with correct credentials.

**Cause:** A super-admin disabled the tenant.

**Fix:**

```bash
# Check tenant status
docker compose exec db psql -U wv_app wealthview -c "SELECT id, name, is_active FROM tenants;"
```

To re-enable: log in as super-admin, navigate to `/admin`, and enable the tenant.

### JWT Secret Changed

**Symptom:** Previously logged-in users suddenly get 401 on all API calls. New logins work fine.

**Cause:** `JWT_SECRET` was changed in `.env`, invalidating all previously issued tokens.

**Fix:** This is expected behavior after a secret rotation. Users need to log in again. Old tokens cannot be refreshed or repaired.

### CORS Errors

**Symptom:** Browser console shows "CORS policy" errors. API calls fail from the frontend but work with `curl`.

**Cause:** The CORS configuration does not allow the requesting origin.

**Fix:**

- Docker profile allows `localhost`. Ensure you are accessing via `http://localhost`, not an IP address or custom domain.
- If using a custom domain, the CORS configuration in the app may need to be updated.

---

## Import Failures

### Wrong Format Selected

**Symptom:** Import completes but all rows fail validation or produce incorrect data (wrong amounts, missing fields).

**Fix:** Ensure the format dropdown matches the brokerage that produced the file:

- Fidelity CSV: select "Fidelity"
- Vanguard CSV: select "Vanguard"
- Schwab CSV: select "Schwab"
- OFX/QFX file: select "OFX"

Each parser expects specific column headers and data formats.

### File Encoding Issues

**Symptom:** Import fails with parse errors, or imported data contains garbled characters.

**Fix:** Ensure the CSV file is UTF-8 encoded. Most brokerages export UTF-8 by default. If you see garbled text, re-export the file from your brokerage, or convert encoding:

```bash
iconv -f ISO-8859-1 -t UTF-8 original.csv > converted.csv
```

### Missing Required Columns

**Symptom:** Import fails immediately with a parse error mentioning expected headers.

**Fix:** Open the CSV file and verify it has the expected column headers for the selected format. The file may be truncated, or the brokerage may have changed their export format.

### All Rows Rejected as Duplicates

**Symptom:** Import completes with 0 successful rows. All rows are reported as duplicates.

**Cause:** The same file was already imported. WealthView uses content-hash deduplication — it recognizes rows that have already been processed.

**Fix:** This is working as intended. Only genuinely new transactions are imported. If you believe this is incorrect, check the transactions table for the data:

```bash
docker compose exec db psql -U wv_app wealthview -c "
  SELECT symbol, transaction_type, quantity, price, transaction_date
  FROM transactions
  ORDER BY created_at DESC
  LIMIT 20;"
```

---

## Prices Not Updating

### No Finnhub API Key

**Symptom:** No new prices appear in the `prices` table. No price-related log entries.

**Cause:** The price sync job is disabled when `FINNHUB_API_KEY` is empty or missing.

**Fix:**

```bash
grep FINNHUB_API_KEY .env
```

Set the key and restart the app:

```bash
docker compose -f docker-compose.prod.yml up -d app
```

### Finnhub Rate Limit

**Symptom:** Some symbols get prices, others do not. Logs show HTTP 429 responses.

**Cause:** Finnhub free tier allows 60 API requests per minute. With many tracked symbols, the sync may exceed this limit.

**Fix:** The app has internal rate limiting but may still hit Finnhub's limits with many symbols. Options:

1. Reduce the number of tracked symbols
2. Upgrade to a paid Finnhub plan
3. Wait — symbols that failed will be retried on the next scheduled run

### Weekends and Holidays

**Symptom:** No prices for Saturday or Sunday.

**Cause:** The price sync job only runs on weekdays (Monday through Friday). Stock markets are closed on weekends.

**Fix:** This is expected behavior. No action needed.

---

## Zillow Not Working

### Not Enabled

**Symptom:** No property valuations appear. No Zillow-related log entries.

**Fix:** `ZILLOW_ENABLED` must be explicitly set to `true` in `.env` (default is `false`):

```bash
grep ZILLOW_ENABLED .env
```

After setting it, restart the app container.

### No ZPID Configured

**Symptom:** Zillow sync runs (visible in logs) but no valuations are created.

**Cause:** Properties need a `zillow_zpid` value to be fetched. This must be configured per property.

**Fix:** Check which properties have ZPIDs:

```bash
docker compose exec db psql -U wv_app wealthview -c "SELECT id, name, zillow_zpid FROM properties;"
```

Set the ZPID via the property edit form in the UI or via the API.

### Blocked by Zillow

**Symptom:** Logs show timeouts, HTTP errors, or "blocked" messages related to Zillow.

**Cause:** Zillow may block or rate-limit scraping requests, especially from cloud/datacenter IP addresses.

**Fix:** No reliable workaround — Zillow does not offer an official free API. The property's existing value remains unchanged on failure (no data is lost). You can manually update property values through the UI.

---

## Database Issues

### Connection Refused

**Symptom:** App logs show "Connection refused" to PostgreSQL.

**Fix:**

```bash
# Is the db container running?
docker compose -f docker-compose.prod.yml ps db

# Check db logs
docker compose -f docker-compose.prod.yml logs --tail=50 db
```

Common causes: db container crashed, disk full, corrupted data files.

### Authentication Failed

**Symptom:** App logs show "password authentication failed for user wv_app."

**Cause:** `DB_PASSWORD` in `.env` does not match the password PostgreSQL was initialized with. PostgreSQL only reads `POSTGRES_PASSWORD` on first initialization (when the data directory is created).

**Fix:**

- If you know the original password, update `.env` to match it.
- To change the password in PostgreSQL:
  ```bash
  docker compose exec db psql -U postgres -c "ALTER USER wv_app PASSWORD 'new_password';"
  ```
  Then update `DB_PASSWORD` in `.env` and restart the app.
- As a last resort: delete the pgdata volume and reinitialize. **This destroys all data** — restore from backup afterward.

### Disk Full

**Symptom:** Insert operations fail. Backups fail. App logs show "could not write to file" or "No space left on device."

**Fix:**

```bash
# Check host disk space
df -h

# Check Docker disk usage
docker system df

# Check backup directory
du -sh ./backups/
```

Immediate remediation:

1. Clean Docker images: `docker system prune -f`
2. Reduce backup retention: lower `BACKUP_RETENTION_DAYS` in `.env`
3. Manually delete old backups: `ls -lt ./backups/ | tail -5` then remove the oldest
4. Expand the disk if the above is not sufficient

### Too Many Connections

**Symptom:** App logs show "too many clients already" or new requests fail intermittently.

**Fix:**

```bash
# Check active connections
docker compose exec db psql -U wv_app wealthview -c "
  SELECT state, count(*)
  FROM pg_stat_activity
  WHERE datname = 'wealthview'
  GROUP BY state;"
```

If many connections are in `idle` state, the connection pool may be misconfigured. Default PostgreSQL max is 100 connections. If you need more, adjust `max_connections` in PostgreSQL config.

---

## Docker Issues

### Container Keeps Restarting

**Symptom:** `docker compose ps` shows a container restarting or with a short uptime that keeps resetting.

**Fix:**

```bash
# Check the specific container's logs
docker compose -f docker-compose.prod.yml logs --tail=100 <service>
```

Common causes by service:

| Service | Common causes |
|---------|--------------|
| `app` | Migration failure, database not ready, missing env vars, OOM |
| `db` | Disk full, corrupted data directory, wrong permissions |
| `nginx` | Invalid config, missing SSL certificates |
| `certbot` | Network issues reaching Let's Encrypt |
| `backup` | Disk full, wrong database credentials |

### Docker Disk Full

**Symptom:** `docker compose up` fails or containers crash. `docker system df` shows high usage.

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

### Network Issues Between Containers

**Symptom:** App cannot reach the database, or nginx cannot reach the app. Logs show connection timeouts between services.

**Cause:** Docker networking is misconfigured or in a bad state.

**Fix:**

```bash
# Recreate the network stack
docker compose -f docker-compose.prod.yml down
docker compose -f docker-compose.prod.yml up -d
```

Containers communicate via service names (`db`, `app`, `nginx`). Verify the app is configured to connect to `db` (not `localhost`) for the database host.

### Docker Compose File Not Found

**Symptom:** `docker compose` commands fail with "no configuration file provided."

**Fix:** Specify the production compose file explicitly:

```bash
docker compose -f docker-compose.prod.yml up -d
```

Or run from the project root directory where the compose file exists.

---

## Backup and Restore Issues

### Backup Not Running

**Symptom:** No new files in `./backups/`. The backup container may not be running.

**Fix:**

```bash
# Check backup container status
docker compose -f docker-compose.prod.yml ps backup

# Check backup container logs
docker compose -f docker-compose.prod.yml logs --tail=20 backup
```

The backup job runs daily at 3:00 AM UTC. If the container is running but no backups appear, check that the `./backups/` directory is writable and has sufficient disk space.

### Restore Procedure

Use the restore script at `infra/backup/restore.sh`:

```bash
# List available backups
ls -lt ./backups/

# Restore a specific backup (CAUTION: this overwrites the current database)
./infra/backup/restore.sh ./backups/wealthview_2026-03-14.dump
```

Always verify the restore was successful by checking the app health endpoint and logging in.

---

## Cross-References

- For backup-specific troubleshooting (pg_dump failures, restore issues), see [Backups](backups.md#troubleshooting).
- For TLS/certificate issues, see [TLS & Nginx](../deployment/tls-and-nginx.md#troubleshooting).
- For upgrading problems, see [Upgrading](../deployment/upgrading.md#troubleshooting-failed-upgrades).
- For log configuration and parsing, see [Monitoring and Logging](monitoring-and-logging.md).
- For scheduled jobs and database maintenance, see [Maintenance](maintenance.md).
