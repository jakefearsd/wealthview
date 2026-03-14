[← Back to README](../../README.md)

# Maintenance

This guide covers scheduled jobs, database maintenance, disk space management, certificate renewal, Docker image updates, performance tuning, and capacity planning.

---

## Scheduled Jobs

### Price Sync

| | |
|---|---|
| **Schedule** | Weekdays (Monday--Friday) at 4:30 PM server time |
| **What it does** | Calls the Finnhub API for each tracked symbol and inserts the daily closing price into the `prices` table |
| **Requirement** | `FINNHUB_API_KEY` must be set in `.env` |

**Verification:**

```bash
docker compose exec db psql -U wv_app wealthview -c "
  SELECT symbol, close_price, price_date
  FROM prices
  WHERE price_date = CURRENT_DATE
  ORDER BY symbol;"
```

**If prices are missing:**

1. Check that `FINNHUB_API_KEY` is set: `grep FINNHUB_API_KEY .env`
2. Check app logs for API errors: `docker compose logs --since 2h app | grep -i finnhub`
3. Individual symbol failures are logged at WARN level. One symbol failing does not stop the others.
4. The job retries automatically on the next scheduled run. No manual intervention needed.

**Notes:**
- The job does not run on weekends or market holidays. Missing weekend prices is expected.
- Finnhub free tier allows 60 requests/minute. With many tracked symbols, the sync may take several minutes.

### Zillow Valuation Sync

| | |
|---|---|
| **Schedule** | Sundays at 6:00 AM server time |
| **What it does** | Scrapes Zillow for each property that has a `zillow_zpid` configured, updates the property's `current_value`, and creates a record in the `property_valuations` table |
| **Requirement** | `ZILLOW_ENABLED=true` in `.env` (default is `false`) |

**Verification:**

```bash
docker compose exec db psql -U wv_app wealthview -c "
  SELECT p.name, pv.value, pv.valuation_date
  FROM property_valuations pv
  JOIN properties p ON p.id = pv.property_id
  ORDER BY pv.valuation_date DESC
  LIMIT 10;"
```

**If valuations are missing:**

1. Verify `ZILLOW_ENABLED=true` in `.env`
2. Verify properties have `zillow_zpid` set: `docker compose exec db psql -U wv_app wealthview -c "SELECT id, name, zillow_zpid FROM properties WHERE zillow_zpid IS NOT NULL;"`
3. Check logs for Zillow errors: `docker compose logs --since 24h app | grep -i zillow`
4. Zillow may block or rate-limit scraping. Failures are logged but the property's value remains unchanged — no data is lost.

---

## Database Maintenance

PostgreSQL autovacuum runs automatically and handles routine maintenance in most cases. Manual intervention is rarely needed.

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

### Manual VACUUM ANALYZE

Run this if you notice degraded query performance, especially after large imports or bulk deletes:

```bash
docker compose exec db psql -U wv_app wealthview -c "VACUUM ANALYZE;"
```

This reclaims dead row space and updates query planner statistics. It is safe to run while the application is live.

### Flyway Migrations

WealthView uses 34 versioned migrations (V001--V034) and 3 repeatable migrations (seed data for prices, tax brackets, and standard deductions). Migrations run automatically on application startup.

To check the current migration state:

```bash
docker compose exec db psql -U wv_app wealthview -c "SELECT version, description, installed_on, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Never modify a migration that has already been applied. If a migration needs correction, create a new versioned migration.

---

## Disk Space Management

### Backup Retention

Daily backups are stored in `./backups/`. The backup container automatically deletes files older than `BACKUP_RETENTION_DAYS` (default: 14 days).

```bash
# Check backup directory size
du -sh ./backups/

# List most recent backups
ls -lt ./backups/ | head -5

# Count backup files
ls ./backups/*.dump 2>/dev/null | wc -l
```

To reduce disk usage, lower `BACKUP_RETENTION_DAYS` in `.env` and restart the backup container.

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

See [monitoring-and-logging.md](monitoring-and-logging.md#log-rotation) for Docker log driver configuration.

---

## Certificate Renewal

The certbot container automatically attempts certificate renewal every 12 hours. Let's Encrypt certificates are valid for 90 days and renew at the 60-day mark.

**Verify certificate expiry:**

```bash
echo | openssl s_client -connect yourdomain.com:443 2>/dev/null | openssl x509 -noout -dates
```

**If renewal fails:**

1. Confirm port 80 is open and reachable from the internet (Let's Encrypt uses HTTP-01 challenge).
2. Verify DNS still points to this server: `dig +short yourdomain.com`
3. Check certbot logs: `docker compose -f docker-compose.prod.yml logs certbot`
4. Verify the certbot-webroot volume is shared correctly between the certbot and nginx containers.

---

## Docker Image Updates

Rebuild periodically to pull the latest base images with security patches:

```bash
docker compose -f docker-compose.prod.yml build --pull
docker compose -f docker-compose.prod.yml up -d
```

This pulls the latest versions of base images (OpenJDK, PostgreSQL 16, nginx, etc.) and rebuilds. The `up -d` command restarts only containers whose images changed.

---

## Performance Tuning

### PostgreSQL Memory

For larger datasets, customize PostgreSQL settings via a Docker volume mount to a custom `postgresql.conf`. Key parameters:

| Setting | Recommendation | Default |
|---------|---------------|---------|
| `shared_buffers` | 25% of available RAM | 128 MB |
| `work_mem` | 4--16 MB | 4 MB |
| `effective_cache_size` | 50--75% of available RAM | 4 GB |

### JVM Heap

If the app container is killed by OOM or runs slowly under load, tune the JVM heap in `docker-compose.prod.yml`:

```yaml
services:
  app:
    environment:
      JAVA_OPTS: "-Xmx512m -Xms256m"
```

Start with `-Xmx512m` and increase if needed. Monitor with `docker stats`.

---

## Capacity Planning

### Growth Estimates

| Data | Growth Rate |
|------|-------------|
| Prices table | ~3,000 rows/year per tracked symbol (weekday Finnhub sync) |
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
