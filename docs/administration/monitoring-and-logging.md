[← Back to README](../../README.md)

# Monitoring and Logging

This guide covers health checks, log configuration, structured log parsing, slow query detection, and external monitoring setup for a WealthView production deployment.

---

## Health Endpoint

### /actuator/health (Public)

The health endpoint is publicly accessible — no authentication required. It returns JSON with the overall application status and individual component health (database connectivity, disk space).

**Basic check:**

```bash
curl -sf http://localhost/actuator/health | jq .status
```

A healthy response looks like:

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 53687091200,
        "free": 38420234240,
        "threshold": 10485760
      }
    }
  }
}
```

If any component is unhealthy, the top-level status changes to `DOWN`.

### Docker Health Check

The app container has a built-in health check that polls `/actuator/health` every 30 seconds:

```
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  interval: 30s
```

Docker marks the container as `healthy`, `unhealthy`, or `starting` based on this check. View container health with:

```bash
docker compose -f docker-compose.prod.yml ps
```

### /actuator/info (Authenticated)

The `/actuator/info` endpoint requires super-admin JWT authentication. It is not suitable for automated health monitoring — use `/actuator/health` instead.

---

## Log Configuration

### Dev Profile

- **Format:** Plain text (human-readable)
- **Level:** DEBUG
- **SQL output:** Formatted and logged for debugging queries
- **CORS:** Allows `localhost:5173` (frontend dev server)

### Docker Profile (Production)

- **Format:** Structured JSON (machine-parseable)
- **Level:** INFO
- **SQL output:** Not logged unless slow (see [Slow Query Detection](#slow-query-detection))
- **CORS:** Allows `localhost`

### Log Output

All log output goes to stdout. Docker captures it via its configured log driver. There are no application-level log files to manage.

---

## Reading Structured JSON Logs

When running with the docker profile, every log line is a JSON object. Key fields:

| Field | Description |
|-------|-------------|
| `@timestamp` | ISO 8601 timestamp (e.g., `2026-03-14T15:30:00.123Z`) |
| `level` | Log level: `ERROR`, `WARN`, `INFO`, `DEBUG` |
| `logger_name` | Fully qualified Java class that produced the log |
| `message` | The log message text |
| `mdc` | Mapped diagnostic context — includes `requestId`, `tenantId`, `userId`, `operation` when available |

### Parsing with jq

**Show only errors with timestamps:**

```bash
docker compose logs app --no-log-prefix | jq -r 'select(.level == "ERROR") | "\(.["@timestamp"]) \(.message)"'
```

**Filter logs by tenant:**

```bash
docker compose logs app --no-log-prefix | jq -r 'select(.mdc.tenantId == "some-uuid") | "\(.["@timestamp"]) [\(.level)] \(.message)"'
```

**Find all log entries for a specific request:**

```bash
docker compose logs app --no-log-prefix | jq -r 'select(.mdc.requestId == "abc123") | "\(.["@timestamp"]) [\(.level)] \(.message)"'
```

**Show warnings and errors from the last hour:**

```bash
docker compose logs --since 1h app --no-log-prefix | jq -r 'select(.level == "WARN" or .level == "ERROR") | "\(.["@timestamp"]) [\(.level)] \(.logger_name): \(.message)"'
```

**Count log entries by level:**

```bash
docker compose logs app --no-log-prefix | jq -r '.level' | sort | uniq -c | sort -rn
```

---

## Slow Query Detection

The application logs slow database queries at the `WARN` level. Thresholds:

| Profile | Threshold |
|---------|-----------|
| Docker (production) | 500ms |
| Dev | 100ms |

Slow query log entries include the SQL text and execution time.

### Finding Slow Queries

```bash
docker compose logs app --no-log-prefix | jq -r 'select(.level == "WARN" and (.message | test("slow|query"; "i"))) | "\(.["@timestamp"]) \(.message)"'
```

### Addressing Slow Queries

When you see slow queries, investigate the following:

1. **Missing indexes:** Check if the query filters on columns without indexes. Add indexes via a new Flyway migration.
2. **N+1 queries:** Multiple slow queries for related entities in the same request suggest an N+1 problem. Use `JOIN FETCH` in the repository query.
3. **Large result sets:** Queries returning thousands of rows are inherently slow. Add pagination or more restrictive filters.
4. **Table bloat:** Run `VACUUM ANALYZE` if the table has had heavy update/delete activity. See [maintenance.md](maintenance.md#database-maintenance).

---

## Docker Log Commands

### Common Operations

```bash
# All app logs
docker compose logs app

# Follow/tail logs in real time
docker compose logs -f app

# Last hour of logs
docker compose logs --since 1h app

# Last 100 lines
docker compose logs --tail=100 app

# Logs from a specific container
docker compose logs db
docker compose logs nginx

# All services at once
docker compose logs

# Filter errors (plain text grep — works but less precise than jq)
docker compose logs app 2>&1 | grep ERROR
```

### Log Rotation

Docker's default `json-file` log driver has no rotation configured by default. Without rotation, log files grow indefinitely and will eventually fill the disk.

Configure log rotation in `/etc/docker/daemon.json`:

```json
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "50m",
    "max-file": "3"
  }
}
```

After editing, restart the Docker daemon:

```bash
sudo systemctl restart docker
```

This limits each container to 3 log files of 50 MB each (150 MB max per container).

---

## External Monitoring Guidance

### Uptime Monitoring

Poll the health endpoint from an external monitoring service (UptimeRobot, Healthchecks.io, Pingdom, or a simple cron job):

```bash
# Simple cron-based check (add to crontab)
* * * * * curl -sf http://localhost/actuator/health | jq -e '.status == "UP"' > /dev/null || echo "WealthView is DOWN" | mail -s "ALERT" admin@example.com
```

Recommended polling interval: 60 seconds. Alert if status is not `UP` for 2+ consecutive checks to avoid false positives during restarts.

### Log Aggregation

Forward Docker logs to a centralized log platform for long-term retention and search:

- **Loki + Grafana:** Use the Docker Loki log driver or Promtail sidecar
- **ELK Stack:** Use Filebeat to ship Docker JSON logs to Elasticsearch
- **CloudWatch / Datadog / etc.:** Use the vendor's Docker log driver

The structured JSON format (docker profile) works directly with these tools without additional parsing configuration.

### Disk Space Alerts

Monitor these paths for disk pressure:

| Path | What grows there |
|------|------------------|
| `/var/lib/docker/` | Docker images, containers, volumes, logs |
| `./backups/` | Daily PostgreSQL backup files |
| Docker volume for `pgdata` | PostgreSQL data directory |

Alert when any filesystem exceeds 80% usage.

### Database Size

Track database size growth over time:

```bash
docker compose exec db psql -U wv_app wealthview -c "SELECT pg_size_pretty(pg_database_size('wealthview'));"
```

---

## Key Metrics to Watch

| Metric | How to Check | Warning Threshold |
|--------|-------------|-------------------|
| Container status | `docker compose -f docker-compose.prod.yml ps` | Any service not "Up" and "healthy" |
| Container resources | `docker stats --no-stream` | Sustained CPU > 80% or memory near limit |
| Database size | See query above | Rapid unexpected growth |
| Backup recency | `ls -lt ./backups/ \| head -5` | No backup in last 24 hours |
| Slow query frequency | Check WARN logs | Increasing trend over days |
| Disk space | `df -h` | Any mount > 80% full |
| Active DB connections | `docker compose exec db psql -U wv_app wealthview -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'wealthview';"` | > 50 connections |
