[← Back to README](../../README.md)

# Monitoring and Logging

This guide covers health checks, actuator endpoints, log configuration, structured log
parsing, slow query detection, and external monitoring setup for a WealthView deployment.

For the metrics/tracing deep dive — every custom meter, the Grafana dashboard, tracing
setup — see [OBSERVABILITY.md](../OBSERVABILITY.md). This page stays at the operator
level.

---

## Health Endpoint

### /actuator/health (Public)

The health endpoint is publicly accessible — no authentication required. Anonymous callers
get the overall status only:

```bash
curl -sf http://localhost/actuator/health
```

```json
{"status":"UP"}
```

Component detail (database connectivity, disk space, ...) is gated:
`management.endpoint.health.show-details` is `when-authorized`, so you must present a
valid session to see it. Logged into the UI, `/actuator/health` in the same browser
returns the expanded form:

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "PostgreSQL", "validationQuery": "isValid()" } },
    "diskSpace": { "status": "UP", "details": { "total": 53687091200, "free": 38420234240, "threshold": 10485760 } }
  }
}
```

If any component is unhealthy the top-level status changes to `DOWN`, and the endpoint
returns HTTP 503 — which is what makes `curl -f` a usable liveness probe.

### `./wv status`

The admin command wraps the same probe with container state:

```bash
./wv status
```

It prints the detected mode (dev/prod), the compose file in use, `docker compose ps`, and
then a one-shot health probe against `http://localhost:${APP_PORT}/actuator/health`.

`./wv up`, `./wv restart`, `./wv update`, `./wv rollback`, and `./wv restore` all poll the
same URL before declaring success (120s, 180s, and 90s budgets respectively). Set
`WV_HEALTH_URL` in `wv.conf` when the app sits behind a reverse proxy and `localhost:80`
is not the right address.

### Docker Health Check

The image carries its own health check, and it is the only definition the prod stack uses:

```dockerfile
# Dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
    CMD wget -q -O- http://localhost:8080/actuator/health || exit 1
```

`wget` rather than `curl` is deliberate — the Alpine JRE runtime image does not ship
`curl`. `docker-compose.prod.yml` used to override the probe with a `curl` test, which
made prod containers report `(unhealthy)` permanently; that override was removed. The dev
`docker-compose.yml` still declares its own app health check, but it uses the same `wget`
probe:

```yaml
# docker-compose.yml
healthcheck:
  test: ["CMD-SHELL", "wget -q -O- http://localhost:8080/actuator/health || exit 1"]
  interval: 30s
  timeout: 5s
  retries: 3
```

The `db` container's check is `pg_isready -U wv_app -d wealthview` every 5s, and the `app`
(and `backup`) containers wait for it via `depends_on: condition: service_healthy`.

View container health with `./wv status` or `docker compose ps`.

### Which actuator endpoints exist

`management.endpoints.web.exposure.include` is `health,prometheus,metrics` — those three,
on every profile. There is no `/actuator/info`, no `/actuator/env`, no
`/actuator/loggers`; requests to them return 404.

| Endpoint | Access |
|---|---|
| `/actuator/health` | Public. Details only for authenticated callers |
| `/actuator/prometheus` | Requires the `SUPER_ADMIN` role |
| `/actuator/metrics` | Requires the `SUPER_ADMIN` role |

Authentication is the app's own JWT — a `Bearer` token or the auth cookie, per
`JwtAuthenticationFilter`. Anonymous requests get 401 and non-super-admins get 403, both in
the standard `{"error":...,"message":...,"status":...}` envelope.

---

## Log Configuration

Logging is configured in `backend/wealthview-app/src/main/resources/logback-spring.xml`,
keyed on the active Spring profile.

### `dev` / `default` / `loadtest` profiles

- **Format:** plain text, one line per event, with the MDC fields inline:
  `HH:mm:ss.SSS LEVEL [requestId] [t:tenantId] [u:userId] [op:operation] logger - message`
- **Root level:** INFO; `com.wealthview` is DEBUG on the `dev` profile
- **SQL output:** `show-sql: true` and formatted SQL on `dev`
- **CORS:** allows `http://localhost:5173` (the Vite dev server)

### `docker` and `prod` profiles

- **Format:** single-line JSON via Logstash's `LogstashEncoder`, wrapped in an
  `AsyncAppender` (queue size 2048) so application threads never block on the encoder
- **Root level:** INFO, with `org.springframework` and `org.hibernate` pinned to WARN
- **SQL output:** not logged unless slow (see [Slow Query Detection](#slow-query-detection))
- **CORS:** `docker` allows `http://localhost`; `prod` requires `CORS_ORIGIN` to be set to
  your https origin (startup aborts otherwise)

The `docker` profile is what `docker-compose.yml` (the dev/local stack) runs; the `prod`
profile is what `docker-compose.prod.yml` runs. **Both emit JSON** — the plain-text format
is what you get running the backend directly with `mvn spring-boot:run`.

### Log Output

All log output goes to stdout. Docker captures it via its configured log driver. There are
no application-level log files to manage.

---

## Reading Structured JSON Logs

Every line is one JSON object. MDC fields are promoted to **top-level** keys (they are not
nested under an `mdc` object):

| Field | Description |
|-------|-------------|
| `@timestamp` | ISO 8601 timestamp (e.g. `2026-08-16T15:30:00.123Z`) |
| `level` | `ERROR`, `WARN`, `INFO`, `DEBUG` |
| `logger_name` | Fully qualified Java class that produced the log |
| `thread_name` | Emitting thread |
| `message` | The log message text |
| `stack_trace` | Present on logged exceptions |
| `application` | Constant `wealthview` (a custom field on every line) |
| `requestId` | Per-request id set by `JwtAuthenticationFilter`; echoes an inbound `X-Request-ID` header (truncated to 32 chars) or generates a 12-char id |
| `tenantId`, `userId` | Set once a request is authenticated |
| `operation` | Set by scheduled jobs (`priceSync`, `propertyValuationSync`, ...) |
| `traceId`, `spanId` | Present when tracing is enabled |

### Parsing with jq

**Show only errors with timestamps:**

```bash
docker compose logs app --no-log-prefix | jq -r 'select(.level == "ERROR") | "\(.["@timestamp"]) \(.message)"'
```

**Filter logs by tenant:**

```bash
docker compose logs app --no-log-prefix | jq -r 'select(.tenantId == "some-uuid") | "\(.["@timestamp"]) [\(.level)] \(.message)"'
```

**Find all log entries for a specific request:**

```bash
docker compose logs app --no-log-prefix | jq -r 'select(.requestId == "abc123") | "\(.["@timestamp"]) [\(.level)] \(.message)"'
```

**Show warnings and errors from the last hour:**

```bash
docker compose logs --since 1h app --no-log-prefix | jq -r 'select(.level == "WARN" or .level == "ERROR") | "\(.["@timestamp"]) [\(.level)] \(.logger_name): \(.message)"'
```

**Watch one scheduled job:**

```bash
docker compose logs app --no-log-prefix | jq -r 'select(.operation == "priceSync") | "\(.["@timestamp"]) \(.message)"'
```

**Count log entries by level:**

```bash
docker compose logs app --no-log-prefix | jq -r '.level' | sort | uniq -c | sort -rn
```

`./wv logs` streams by default; add `--no-follow` for a one-shot dump and `--tail N` to
limit history. Use raw `docker compose logs ... --no-log-prefix` when piping to `jq`, since
the compose prefix (`app  | {...}`) is not valid JSON.

---

## Slow Query Detection

Hibernate's session-events logger emits slow queries at WARN under the logger
`org.hibernate.SQL_SLOW`. Thresholds come from
`spring.jpa.properties.hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS`:

| Profile | Threshold |
|---------|-----------|
| `prod` / `docker` (the base default) | 500ms |
| `dev` | 100ms |

Slow query log entries include the SQL text and execution time.

### Finding Slow Queries

```bash
docker compose logs app --no-log-prefix | jq -r 'select(.logger_name == "org.hibernate.SQL_SLOW") | "\(.["@timestamp"]) \(.message)"'
```

### Addressing Slow Queries

1. **Missing indexes:** check whether the query filters on unindexed columns. Add indexes
   via a new Flyway migration.
2. **N+1 queries:** many slow queries for related entities inside one request suggests an
   N+1. Use `JOIN FETCH` in the repository query.
3. **Large result sets:** queries returning thousands of rows are inherently slow. Add
   pagination or tighter filters.
4. **Table bloat:** run `VACUUM ANALYZE` after heavy update/delete activity. See
   [maintenance.md](maintenance.md#database-maintenance).

---

## Log Commands

### Common Operations

```bash
# Follow all services
./wv logs

# Follow one service (db, app, and — in prod — backup)
./wv logs app

# Last 100 lines, then keep following
./wv logs app --tail 100

# One-shot dump, no streaming
./wv logs app --tail 200 --no-follow

# Raw compose equivalents (useful for --since and jq piping)
docker compose logs --since 1h app
docker compose logs app --no-log-prefix
docker compose logs db

# Filter errors (plain-text grep — works, but less precise than jq)
./wv logs app --no-follow | grep ERROR
```

There is no `nginx` or `certbot` container: the compose stack is `db` + `app` in dev and
`db` + `app` + `backup` in prod. If you terminate TLS with host nginx, its logs are in
`journalctl -u nginx` and `/var/log/nginx/`.

### Log Rotation

Docker's default `json-file` log driver has no rotation configured. Without rotation, log
files grow indefinitely and will eventually fill the disk.

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

## Metrics and Tracing

### Prometheus endpoint

Micrometer's Prometheus registry is enabled by default and exposed at
`/actuator/prometheus` (super-admin only). Alongside the standard JVM, HTTP, and HikariCP
meters, WealthView registers its own — for example:

| Meter | Type | Meaning |
|---|---|---|
| `wealthview.scheduled.last_success_seconds{job=...}` | gauge | Unix epoch seconds of the last successful run of `priceSync` / `propertyValuationSync`; `0` if never |
| `wealthview.scheduled.runs{job,status}` | counter | Scheduled-job runs by outcome |
| `wealthview.splits.last_success_seconds` | gauge | Last successful stock-split sync |
| `wealthview.pricefeed.symbols{status}` | counter | Symbols priced successfully vs failed |
| `wealthview.ratelimit.tracked_keys` | gauge | Live rate-limit windows in memory |

Every meter carries the common `application=wealthview` tag. The full inventory lives in
[OBSERVABILITY.md](../OBSERVABILITY.md#custom-wealthview-meters).

### Optional Prometheus + Grafana stack

`docker-compose.observability.yml` adds Prometheus and Grafana next to the production
stack:

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml up -d
```

- Prometheus on `${PROMETHEUS_PORT:-9090}`, scrape config
  `infra/observability/prometheus.yml` (15s interval), alert rules in
  `infra/observability/prometheus-rules.yml`
- Grafana on `${GRAFANA_PORT:-3000}` with a provisioned datasource and the dashboard at
  `infra/observability/grafana/dashboards/wealthview.json`
- Requires `GRAFANA_ADMIN_PASSWORD` (Grafana refuses to start without it) and
  `SUPER_ADMIN_PASSWORD` (Prometheus uses it for the scrape credential)

Because `/actuator/prometheus` is super-admin-only, confirm the scrape is actually
succeeding after you bring the stack up — check Prometheus' **Status → Targets** page or
`docker compose ... logs prometheus`. See [OBSERVABILITY.md](../OBSERVABILITY.md) for the
authoritative setup.

### Tracing

OpenTelemetry tracing is **off** by default (`management.tracing.enabled: false`). Turn it
on with `MANAGEMENT_TRACING_ENABLED=true` plus an OTLP endpoint
(`OTEL_EXPORTER_OTLP_ENDPOINT`); sampling defaults to 10% and is raised with
`MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0` during an incident. When enabled, `traceId`
and `spanId` appear in the JSON logs, which lets you pivot from a log line to a trace.

---

## External Monitoring Guidance

### Uptime Monitoring

Poll the health endpoint from an external monitoring service (UptimeRobot,
Healthchecks.io, Pingdom, or a cron job):

```bash
# Simple cron-based check (add to crontab)
* * * * * curl -sf http://localhost/actuator/health > /dev/null || echo "WealthView is DOWN" | mail -s "ALERT" admin@example.com
```

`curl -f` already fails on the 503 that a `DOWN` status returns, so no JSON parsing is
needed — which matters because anonymous callers only receive `{"status":"UP"}`.

Recommended polling interval: 60 seconds. Alert if the check fails 2+ consecutive times to
avoid false positives during restarts.

### Log Aggregation

Forward Docker logs to a centralized platform for long-term retention and search:

- **Loki + Grafana:** the Docker Loki log driver, or a Promtail sidecar
- **ELK Stack:** Filebeat shipping Docker JSON logs to Elasticsearch
- **CloudWatch / Datadog / etc.:** the vendor's Docker log driver

The `docker`/`prod` JSON format works with these tools without additional parsing
configuration, and the promoted `tenantId` / `requestId` / `operation` keys are directly
queryable.

### Disk Space Alerts

Monitor these paths for disk pressure:

| Path | What grows there |
|------|------------------|
| `/var/lib/docker/` | Docker images, containers, volumes, logs |
| The backups directory (`WV_BACKUPS_DIR`, and `./backups` next to the compose file) | Nightly and on-demand PostgreSQL dumps |
| Docker volume for `pgdata` | PostgreSQL data directory |

Alert when any filesystem exceeds 80% usage. Remember that retention only sweeps the cron
container's own `wealthview_auto_*.dump[.age]` files — every `./wv backup` dump, encrypted
or not, is yours to prune.

### Database Size

Track database size growth over time:

```bash
docker compose exec db psql -U wv_app wealthview -c "SELECT pg_size_pretty(pg_database_size('wealthview'));"
```

---

## Key Metrics to Watch

| Metric | How to Check | Warning Threshold |
|--------|-------------|-------------------|
| Container status | `./wv status` | Any service not "Up" / "healthy" |
| Container resources | `docker stats --no-stream` | Sustained CPU > 80% or memory near limit |
| Database size | See query above | Rapid unexpected growth |
| Backup recency | `./wv backups \| head -5` | No backup in last 24 hours |
| Backup integrity | `./wv verify <newest dump>` | Any non-zero exit |
| Slow query frequency | WARN lines from `org.hibernate.SQL_SLOW` | Increasing trend over days |
| Scheduled job freshness | `wealthview.scheduled.last_success_seconds` | Older than the job's interval, or `0` |
| Disk space | `df -h` | Any mount > 80% full |
| Active DB connections | `docker compose exec db psql -U wv_app wealthview -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'wealthview';"` | > 50 (Hikari caps the app at 20) |

---

## Related Docs

- [OBSERVABILITY.md](../OBSERVABILITY.md) — metrics inventory, tracing, Grafana dashboard
- [Operations Handbook](../deployment/operations.md) — the full `wv` command surface
- [Maintenance](maintenance.md) — scheduled jobs, updates, disk space
- [Troubleshooting](troubleshooting.md) — symptom-driven diagnostics
