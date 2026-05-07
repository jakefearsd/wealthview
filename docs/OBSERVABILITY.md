# Observability

WealthView ships three layers of observability: **metrics** (Micrometer →
Prometheus, always on), **tracing** (OpenTelemetry, off by default), and an
**opt-in compose stack** that wires up Prometheus + Grafana for self-hosted
operators.

This document covers configuration, the metric/span catalog, and how to plug
external tooling (Tempo, Jaeger) into the tracing layer.

---

## 1. Metrics

### Endpoints

| Path                     | Auth required        | Purpose                                         |
| ------------------------ | -------------------- | ----------------------------------------------- |
| `/actuator/health`       | none                 | Liveness/readiness — used by Docker healthcheck |
| `/actuator/metrics`      | `SUPER_ADMIN`        | Browseable list of registered meters            |
| `/actuator/prometheus`   | `SUPER_ADMIN`        | Prometheus scrape target                        |

The security rule lives in `wealthview-api/.../SecurityConfig.java` and is
covered by `PrometheusEndpointIT`. Don't loosen it — Prometheus output
contains tag values that, while not PII, reveal internal structure.

### Common tags

Every meter is stamped with two tags by `MetricsConfig.CommonTagsCustomizer`:

| Tag           | Source                                | Example       |
| ------------- | ------------------------------------- | ------------- |
| `application` | hard-coded                            | `wealthview`  |
| `env`         | `${spring.profiles.active}` or `unknown` | `prod`        |

Tags **never** include `tenantId` or `userId` — high-cardinality identifiers
would explode Prometheus' index and leak PII through scrape output.

### Custom WealthView meters

| Meter (Micrometer name)       | Type      | Tags                       | Emitted by                           |
| ----------------------------- | --------- | -------------------------- | ------------------------------------ |
| `wealthview.auth.login`       | Counter   | `result`, `reason`         | `AuthService.login`                  |
| `wealthview.auth.refresh`     | Counter   | `result`, `reason`         | `AuthService.refresh`                |
| `wealthview.auth.logout`      | Counter   | (none)                     | `AuthService.logout`                 |
| `wealthview.auth.registration`| Counter   | `result`, `reason`         | `AuthService.register`               |
| `wealthview.scenarios`        | Counter   | `action` (create/update/delete) | `ScenarioCrudService`           |
| `wealthview.scheduled.runs`   | Counter   | `job`, `status` (success/failure) | scheduled jobs (price, valuation) |
| `wealthview.scheduled.last_success_seconds` | Gauge | `job` (priceSync, propertyValuationSync) | scheduled jobs |
| `wealthview.import.process`   | Timer     | (auto from `@Timed`)       | `ImportService.processImport`        |
| `wealthview.import.rows`      | Counter   | `outcome`                  | `ImportService.processImport`        |
| `wealthview.imports`          | Counter   | `format`, `status`         | `ImportService.processImport`        |
| `wealthview.dashboard.summary`| Timer     | (auto from `@Timed`)       | `DashboardService`                   |
| `wealthview.dashboard.portfolio.history` | Timer | (auto)                | `CombinedPortfolioHistoryService`    |
| `wealthview.pricefeed.sync`   | Timer     | (auto)                     | `PriceSyncService`                   |
| `wealthview.property.valuation.sync` | Timer | (auto)                  | `PropertyValuationSyncService`       |
| `wealthview.projection.run`   | Timer     | (auto)                     | `DeterministicProjectionEngine.run`  |
| `wealthview.projection.runs`  | Counter   | `type` (deterministic / monte_carlo) | both projection paths     |
| `wealthview.mc.optimize`      | Timer + histogram | (auto)             | `MonteCarloSpendingOptimizer.optimize` |
| `wealthview.finnhub.quote`    | Timer + histogram | (auto)             | `FinnhubClient.getQuote`             |
| `wealthview.finnhub.candles`  | Timer + histogram | (auto)             | `FinnhubClient.getCandles`           |
| `wealthview.ratelimit.exceeded` | Counter | `type` (ip / user)         | `RateLimitFilter`                    |
| `wealthview.errors`           | Counter   | `exception`, `status`      | `GlobalExceptionHandler` (whitelist; unknowns bucket as `other`) |
| `cache.gets` / `cache.puts` / `cache.evictions` / `cache.size` | Caffeine | `cache`, `result` (hit/miss for gets) | `CacheConfig` (5 caches) |

In Prometheus output the dots become underscores and timers/histograms expand
into `_seconds_count` / `_seconds_sum` / `_seconds_bucket` sibling series.
Counters get a `_total` suffix.

Spring Boot also ships rich built-ins: `http_server_requests_seconds_*`,
`jvm_memory_used_bytes`, `hikaricp_connections_*`, `cache_*`, etc.

### Adding new meters

- Use `@Timed` on Spring-managed beans for latency. Pass `histogram = true`
  for percentile distributions when you intend to chart p95/p99 across
  multiple replicas — without it, Micrometer only stores client-side
  summaries and `histogram_quantile()` won't aggregate correctly.
- Use `meterRegistry.counter(name, tags...)` for events. Keep tag-key sets
  consistent across all increment sites of the same meter name; mismatched
  key sets emit a "duplicate meter registration" warning.
- Never tag with `tenantId`, `userId`, request bodies, account IDs,
  symbols, or anything else with cardinality above ~20.
- For tags derived from class names or other potentially-open inputs, use a
  closed whitelist and bucket unknowns as `other` (see
  `GlobalExceptionHandler.KNOWN_EXCEPTIONS`).
- For scheduled jobs, emit both a per-run counter (`wealthview.scheduled.runs{job, status}`)
  and a "last success" gauge (`wealthview.scheduled.last_success_seconds{job}`).
  Alert with `time() - wealthview_scheduled_last_success_seconds{job=...} > <threshold>`.

---

## 2. Tracing (OpenTelemetry)

Disabled by default. Activate it when you need to investigate a slow request.

### Turning it on

Set these env vars (e.g. in your `.env` next to `docker-compose.prod.yml`):

```bash
MANAGEMENT_TRACING_ENABLED=true
OTEL_EXPORTER_OTLP_ENDPOINT=http://my-collector:4318/v1/traces
# Optional, default 0.1. Set to 1.0 during an incident.
MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
```

Restart the app. Spring Boot picks up the env vars and wires:

- `micrometer-tracing-bridge-otel` — converts Micrometer Observations to
  OpenTelemetry spans.
- `opentelemetry-exporter-otlp` — sends spans over OTLP/HTTP to the
  configured endpoint.

### Pointing at Jaeger

Run Jaeger with OTLP enabled (the default in v1.35+):

```bash
docker run --rm -p 16686:16686 -p 4318:4318 \
    jaegertracing/all-in-one:1.65
```

Set `OTEL_EXPORTER_OTLP_ENDPOINT=http://<jaeger-host>:4318/v1/traces`. Open
Jaeger at `http://<jaeger-host>:16686`, select the `wealthview` service.

### Pointing at Tempo

Run Grafana Tempo standalone or use Grafana Cloud. Tempo's default OTLP
receiver listens on `:4318`. Add Tempo as a Grafana datasource alongside
Prometheus, then trace IDs in dashboards become clickable links.

### What gets traced

HTTP requests are traced automatically by Spring Boot's
`ServerHttpObservationFilter`. The methods explicitly annotated with
`@Observed` become first-class spans you can drill into:

- `DeterministicProjectionEngine.run` (`wealthview.projection.run`)
- `MonteCarloSpendingOptimizer.optimize` (`wealthview.mc.optimize`)
- `ImportService.processImport` (`wealthview.import.process`)

These are the same hot spots already carrying `@Timed`. The two annotations
coexist by design: `@Timed` always feeds the meter registry; `@Observed`
becomes a span only when tracing is on, and contributes a no-op observation
when it's off.

### Safety notes

- Tracing exporters are always potentially blocking. The OTLP exporter is
  asynchronous and bounded, but a misconfigured endpoint can still slow
  startup — keep tracing off unless you actively want it.
- Spans inherit common tags, so trace search by `application=wealthview`
  works the same way as in Prometheus.

---

## 3. Compose stack

The repo ships a self-contained Prometheus + Grafana stack as
`docker-compose.observability.yml`. It's layered on top of the prod compose:

```bash
docker compose -f docker-compose.prod.yml \
               -f docker-compose.observability.yml \
               up -d
```

### Required env vars

| Var                       | Used by    | Notes                                  |
| ------------------------- | ---------- | -------------------------------------- |
| `SUPER_ADMIN_PASSWORD`    | Prometheus | Already set by the prod compose        |
| `GRAFANA_ADMIN_PASSWORD`  | Grafana    | No fallback — Grafana fails to start without it |
| `PROMETHEUS_PORT` (opt)   | Prometheus | Defaults to 9090                       |
| `GRAFANA_PORT` (opt)      | Grafana    | Defaults to 3000                       |

Add them to your `.env` next to the existing `DB_PASSWORD`/`JWT_SECRET`/etc.

### What you get

- Prometheus scraping `app:8080/actuator/prometheus` every 15s.
- Grafana auto-provisioned with one dashboard ("WealthView Overview")
  containing: HTTP request rate, HTTP latency p50/p95/p99, JVM heap + GC,
  Hikari pool usage, projection/MC p95 latency, Finnhub p95 latency, login
  outcomes, imports by format/status, rate-limit rejections.

### Tearing it down

```bash
docker compose -f docker-compose.prod.yml \
               -f docker-compose.observability.yml \
               down
```

Use `docker compose ... down -v` to also drop the `prometheus_data` and
`grafana_data` volumes if you want a clean slate.

### Production deployment notes

- The stack as shipped exposes Prometheus and Grafana on the host network.
  Behind a reverse proxy (Caddy, Nginx, Cloudflare Tunnel), close those
  ports and proxy `/grafana` if you want a single ingress.
- The Prometheus scrape config uses HTTP basic auth with the SUPER_ADMIN
  user — fine for a self-hosted single-node deployment. For multi-node
  deployments, generate a dedicated read-only metrics token instead of
  reusing the SUPER_ADMIN credential.
- Alertmanager is not included. Add it as a follow-up service when you
  decide on alert routing destinations.

---

## 4. Out of scope (for now)

- Logs aggregation (Loki / ELK).
- APM-vendor SDKs (Datadog, New Relic, etc.).
- Frontend RUM / Web Vitals.
- Alert routing.
