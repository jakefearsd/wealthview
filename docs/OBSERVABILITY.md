# Observability

WealthView ships four layers of observability: **metrics** (Micrometer →
Prometheus, always on), **tracing** (OpenTelemetry, off by default),
**structured logging** (JSON under `prod`/`docker`), and an **opt-in compose
stack** that wires up Prometheus + Grafana for self-hosted operators.

This document covers configuration, the metric/span catalog, and how to plug
external tooling (Tempo, Jaeger) into the tracing layer.

The compose stack ships **Prometheus and Grafana only** — no Loki, no Tempo,
no Pyroscope. (The load-test harness under `loadtest/` runs its *own*,
completely separate stack that does add Pyroscope and a postgres-exporter;
see `loadtest/README.md`. Nothing there is part of the production stack.)

---

## 1. Metrics

### Endpoints

Only three actuator endpoints are exposed at all — `application.yml` sets
`management.endpoints.web.exposure.include: health,prometheus,metrics`.
Everything else (`/actuator/env`, `/actuator/beans`, …) returns 404.

| Path                     | Auth required        | Purpose                                         |
| ------------------------ | -------------------- | ----------------------------------------------- |
| `/actuator/health`       | none                 | Liveness/readiness — used by Docker healthcheck |
| `/actuator/metrics`      | `SUPER_ADMIN` (see below) | Browseable list of registered meters       |
| `/actuator/prometheus`   | `SUPER_ADMIN` (see below) | Prometheus scrape target                   |

`management.endpoint.health.show-details: when-authorized`, so anonymous
callers get a bare `{"status":"UP"}` and only an authenticated principal sees
the component breakdown.

The security rules live in `wealthview-api/.../SecurityConfig.java`
(`/actuator/health` → `permitAll`, `/actuator/**` → `hasRole("SUPER_ADMIN")`)
and are covered by `PrometheusEndpointIT`. Don't loosen them — Prometheus
output contains tag values that, while not PII, reveal internal structure.

#### Opting the metrics endpoints out of auth — `app.observability.anonymous-metrics`

> **When this flag is on, `/actuator/prometheus` and `/actuator/metrics` are
> served with no authentication at all — any caller that can reach the app's
> port can read them.** The default is **`false`**, and every shipped profile
> (`prod`, `docker`, `dev`, `it`, default) leaves it off, so `/actuator/**`
> stays `SUPER_ADMIN`-only unless you deliberately switch it on.

The app has no HTTP Basic auth: `SecurityConfig` defines the only
`SecurityFilterChain` and never calls `.httpBasic(...)`; `JwtAuthenticationFilter`
accepts a `Bearer` header or the auth cookie and nothing else. Prometheus has no
way to present a SUPER_ADMIN credential, so scraping a `SUPER_ADMIN`-only
endpoint used to 401 every time. Rather than add a second authentication
mechanism just for metrics, the two metrics endpoints can be opened explicitly:

| Property | Env var | Default |
| --- | --- | --- |
| `app.observability.anonymous-metrics` | `APP_OBSERVABILITY_ANONYMOUS_METRICS` | `false` |

`docker-compose.observability.yml` sets `APP_OBSERVABILITY_ANONYMOUS_METRICS: "true"`
on the app service, which is what makes the bundled stack collect anything.
`application-loadtest.yml` sets the same property (it replaced a hardcoded
`loadtest`-profile check inside `SecurityConfig`, so there is one mechanism to
reason about rather than two).

Because the endpoints are unauthenticated wherever the flag is on:

- Only enable it where the app's port is **not** reachable from the internet.
  In the bundled stack the app is scraped over the compose network and its port
  is not published.
- Keep `/actuator` blocked at the reverse proxy regardless — see
  `docs/deployment/security-hardening.md`.
- Metrics carry no `tenantId`/`userId` (see "Common tags"), but they do expose
  request URIs, volumes and internal structure.

### Common tags

Every meter is stamped with two tags by `MetricsConfig.CommonTagsCustomizer`
(a `MeterRegistryCustomizer`, so it applies to the `SimpleMeterRegistry` used
in tests as well as the Prometheus registry):

| Tag           | Source                                | Example       |
| ------------- | ------------------------------------- | ------------- |
| `application` | hard-coded                            | `wealthview`  |
| `env`         | `${spring.profiles.active:unknown}`   | `prod`        |

Under the `loadtest` profile `application-loadtest.yml` overrides
`management.metrics.tags.application` to `wealthview-loadtest`.

Tags **never** include `tenantId` or `userId` — high-cardinality identifiers
would explode Prometheus' index and leak PII through scrape output.

### Custom WealthView meters

| Meter (Micrometer name)       | Type      | Tags                       | Emitted by                           |
| ----------------------------- | --------- | -------------------------- | ------------------------------------ |
| `wealthview.auth.login`       | Counter   | `result` (success/failure/mfa_required), `reason`, `transport` | `AuthService`, `MfaChallengeService` |
| `wealthview.auth.registration`| Counter   | `result`, `reason` (failures only), `transport` | `AuthService.register`  |
| `wealthview.auth.refresh`     | Counter   | `result`, `reason`, `transport` | `TokenService.refresh`          |
| `wealthview.auth.refresh_reuse_detected` | Counter | `transport`      | `TokenService.refresh` (token replay) |
| `wealthview.auth.logout`      | Counter   | `transport`                | `TokenService.revokeAllTokens`       |
| `wealthview.auth.session_created` | Counter | `transport`               | `TokenService.issueTokens`           |
| `wealthview.auth.session_revoked` | Counter | `scope` (single/all_other) | `SessionService`                    |
| `wealthview.mfa.setup`        | Counter   | (none)                     | `MfaService.setup`                   |
| `wealthview.mfa.verified`     | Counter   | (none)                     | `MfaService.verifySetup`             |
| `wealthview.mfa.failed`       | Counter   | `scope` (verify_setup/disable/challenge/recovery_code/challenge_token) | `MfaService`, `MfaChallengeService` |
| `wealthview.app.version_check_total` | Counter | `platform`, `outcome` (up_to_date/update_recommended/update_required/invalid_request) | `MobileAppVersionService` |
| `wealthview.scenarios`        | Counter   | `action` (create/update/delete) | `ScenarioCrudService`           |
| `wealthview.scheduled.runs`   | Counter   | `job`, `status` (success/failure) | `PriceSyncService`, `PropertyValuationSyncService` |
| `wealthview.scheduled.last_success_seconds` | Gauge | `job` (priceSync, propertyValuationSync) | same two services |
| `wealthview.import.process`   | Timer     | (auto from `@Timed`)       | `ImportService.processImport`        |
| `wealthview.import.rows`      | Counter   | `outcome` (imported/duplicate/error) | `ImportService.processImport` |
| `wealthview.imports`          | Counter   | `format`, `status`         | `ImportService.processImport`        |
| `wealthview.dashboard.summary`| Timer     | (auto from `@Timed`)       | `DashboardService`                   |
| `wealthview.dashboard.portfolio.history` | Timer | (auto)                | `CombinedPortfolioHistoryService`    |
| `wealthview.pricefeed.sync`   | Timer     | (auto)                     | `PriceSyncService`                   |
| `wealthview.pricefeed.symbols`| Counter   | `status` (success/failure) | `PriceSyncService` (per-symbol outcome) |
| `wealthview.property.valuation.sync` | Timer | (auto)                  | `PropertyValuationSyncService`       |
| `wealthview.property.valuations` | Counter | `status` (success/skipped) | `PropertyValuationSyncService` (per-property outcome) |
| `wealthview.projection.run`   | Timer     | `component=projection` (from `@Observed`) | `DeterministicProjectionEngine.run` |
| `wealthview.projection.runs`  | Counter   | `type` (deterministic / monte_carlo) | both projection paths     |
| `wealthview.mc.optimize`      | Timer + histogram | `component=projection` (from `@Observed`) | `MonteCarloSpendingOptimizer.optimize` |
| `wealthview.finnhub.quote`    | Timer + histogram | (auto)             | `FinnhubClient.getQuote`             |
| `wealthview.finnhub.candles`  | Timer + histogram | (auto)             | `FinnhubClient.getCandles`           |
| `wealthview.finnhub.splits`   | Timer + histogram | (auto)             | `FinnhubSplitClient.fetch`           |
| `wealthview.splits.sync`      | Timer + histogram | (auto)             | `StockSplitSyncService.syncAll`      |
| `wealthview.splits.synced_total` | Counter | `result` (success/partial) | `StockSplitSyncService.syncAll`     |
| `wealthview.splits.sync_failed` | Counter | `symbol`                  | `StockSplitSyncService` (per-symbol failure) |
| `wealthview.splits.last_success_seconds` | Gauge | (none)          | `StockSplitSyncService`              |
| `wealthview.splits.applied`   | Counter   | `symbol`, `ratio`          | `StockSplitService.applySplit`       |
| `wealthview.splits.unapplied` | Counter   | `symbol`                   | `StockSplitService.unapplySplit`     |
| `wealthview.splits.backfill_completed_total` | Counter | (none)      | `StockSplitBackfillRunner`           |
| `wealthview.ratelimit.exceeded` | Counter | `type` (ip / user)         | `RateLimitFilter`                    |
| `wealthview.ratelimit.tracked_keys` | Gauge | (none)                  | `RateLimitFilter` (live window-map size) |
| `wealthview.errors`           | Counter   | `exception`, `status`      | `GlobalExceptionHandler` (whitelist; unknowns bucket as `other`) |
| `cache.gets` / `cache.puts` / `cache.evictions` / `cache.size` | Caffeine | `cache`, `result` (hit/miss for gets) | `CacheConfig` |

The five Caffeine caches registered by `CacheConfig` are `accountBalances`,
`latestPrices`, `exchangeRates`, `exchangeRateConversions` and
`mobileAppVersions`.

In Prometheus output the dots become underscores and timers/histograms expand
into `_seconds_count` / `_seconds_sum` / `_seconds_bucket` sibling series;
counters gain a `_total` suffix.

> Three counters already carry `_total` inside their **Micrometer** name
> (`wealthview.splits.synced_total`, `wealthview.splits.backfill_completed_total`,
> `wealthview.app.version_check_total`). Whether the exporter appends a second
> `_total` depends on the Micrometer/Prometheus-client version in the BOM, so
> check the live scrape body before writing a query or alert against those
> three rather than assuming the suffix. New counters should NOT bake `_total`
> into the Micrometer name.

Spring Boot also ships rich built-ins: `http_server_requests_seconds_*`,
`jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*`, `hikaricp_connections_*`,
`cache_*`, etc.

> **`http.server.requests` publishes histogram buckets on every profile.**
> `application.yml` sets `percentiles-histogram` for that meter, so
> `http_server_requests_seconds_bucket` exists on prod, docker, dev and the
> loadtest stack alike:
>
> ```yaml
> management:
>   metrics:
>     distribution:
>       percentiles-histogram:
>         http.server.requests: true
> ```
>
> Anything that calls `histogram_quantile()` over that series — the "HTTP
> latency p50/p95/p99" dashboard panel, the `WealthViewHttpP99Latency` alert —
> therefore works out of the box. It used to be set only in
> `application-loadtest.yml`, which is why both read empty in prod: it looked
> like health rather than blindness. There is a real cardinality cost, since
> `http.server.requests` is tagged by URI and each distinct URI actually
> exercised adds a full bucket set — accepted deliberately, because WealthView
> is self-hosted for a household and a latency alert that works is worth more
> than the series it saves.

### Adding new meters

- Use `@Timed` on Spring-managed beans for latency. Pass `histogram = true`
  for percentile distributions when you intend to chart p95/p99 across
  multiple replicas — without it, Micrometer only stores client-side
  summaries and `histogram_quantile()` won't aggregate correctly.
- **Never put `@Timed` and `@Observed` on the same method under the same
  name.** See the boxed warning in §2 — this is not a style preference, it
  took out the whole scrape once.
- If a meter's timer comes from `@Observed`, its buckets cannot be declared on
  the annotation. Configure them with a `MeterFilter` in `MetricsConfig`
  instead — `monteCarloOptimizeHistogram()` is the worked example.
- Use `meterRegistry.counter(name, tags...)` for events. Keep tag-key sets
  consistent across all increment sites of the same meter name; mismatched
  key sets emit a "duplicate meter registration" warning.
- Never tag with `tenantId`, `userId`, request bodies, account IDs, or
  anything else with cardinality above ~20. (`wealthview.splits.applied`,
  `.unapplied` and `.sync_failed` tag by `symbol` and are the deliberate
  exception: split events are rare and bounded by the ticker set the
  deployment actually holds. Do not copy the pattern onto a hot path.)
- For tags derived from class names or other potentially-open inputs, use a
  closed whitelist and bucket unknowns as `other` (see
  `GlobalExceptionHandler.KNOWN_EXCEPTIONS`, which derives itself from the
  class's own `@ExceptionHandler` declarations so it cannot drift).
- For scheduled jobs, emit both a per-run counter (`wealthview.scheduled.runs{job, status}`)
  and a "last success" gauge (`wealthview.scheduled.last_success_seconds{job}`).
  Alert with `time() - wealthview_scheduled_last_success_seconds{job=...} > <threshold>`.

---

## 2. Tracing (OpenTelemetry)

Disabled by default (`management.tracing.enabled: false`). Activate it when
you need to investigate a slow request.

### Turning it on

Set these env vars (e.g. in your `.env` next to `docker-compose.prod.yml`):

```bash
MANAGEMENT_TRACING_ENABLED=true
OTEL_EXPORTER_OTLP_ENDPOINT=http://my-collector:4318/v1/traces
# Optional, default 0.1. Set to 1.0 during an incident.
MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
```

`OTEL_EXPORTER_OTLP_ENDPOINT` is consumed through
`management.otlp.tracing.endpoint`, which defaults to
`http://localhost:4318/v1/traces` when the env var is absent.

Restart the app. Spring Boot picks up the env vars and wires:

- `micrometer-tracing-bridge-otel` — converts Micrometer Observations to
  OpenTelemetry spans.
- `opentelemetry-exporter-otlp` — sends spans over OTLP/HTTP to the
  configured endpoint.

Both are declared in `wealthview-app/pom.xml` and ship in the image, so
enabling tracing needs no rebuild.

### Pointing at Jaeger

Run Jaeger with OTLP enabled (the default in v1.35+):

```bash
docker run --rm -p 16686:16686 -p 4318:4318 \
    jaegertracing/all-in-one:1.65
```

Set `OTEL_EXPORTER_OTLP_ENDPOINT=http://<jaeger-host>:4318/v1/traces`. Open
Jaeger at `http://<jaeger-host>:16686`, select the `wealthview` service.

### Pointing at Tempo

Run Grafana Tempo standalone or use Grafana Cloud. Tempo's default OTLP/HTTP
receiver listens on `:4318`. Add Tempo as a Grafana datasource alongside
Prometheus, then trace IDs in dashboards become clickable links. Tempo is
**not** part of `docker-compose.observability.yml` — you bring your own.

### What gets traced

HTTP requests are traced automatically by Spring Boot's
`ServerHttpObservationFilter`. Exactly **two** methods carry `@Observed` and
become first-class spans you can drill into:

- `DeterministicProjectionEngine.run` — name `wealthview.projection.run`,
  contextual name `deterministic-projection`
- `MonteCarloSpendingOptimizer.optimize` — name `wealthview.mc.optimize`,
  contextual name `monte-carlo-optimize`

Both carry `component=projection` as a low-cardinality key value. `@Observed`
supplies BOTH the span and the timer for these two — they deliberately carry
no `@Timed`.

(`ImportService.processImport` is `@Timed` only. It produces the
`wealthview.import.process` timer but is *not* a span.)

> **Do not add `@Timed` next to an `@Observed` with the same name.** The
> earlier claim that `@Observed` "contributes a no-op observation when tracing
> is off" is wrong: Spring Boot registers a `DefaultMeterObservationHandler`
> whenever a `MeterRegistry` is present, so an observation ALWAYS registers a
> timer under its `name`. With both annotations the name is registered twice.
> That double-counts the metric — and if the two disagree about buckets (one
> asked for `histogram = true`, the observation timer cannot), the family ends
> up holding Histogram and Summary data points at once and the Prometheus text
> writer throws `ClassCastException`, failing the **entire** `/actuator/prometheus`
> scrape rather than just that metric. That is exactly what happened on
> `wealthview.mc.optimize`: any single optimization run poisoned every metric
> in the scrape. `wealthview.projection.run` had the same double registration
> but silently double-counted instead of crashing, because neither of its two
> timers asked for buckets.
>
> The fix, and the current state of the code: the `@Timed` is gone from both
> methods, and buckets are configured with a `MeterFilter` in `MetricsConfig`
> — see `monteCarloOptimizeHistogram()`. `PrometheusEndpointIT`
> (`prometheusEndpoint_afterAMonteCarloOptimization_stillRendersAndExposesItsBuckets`)
> runs a real optimization and then scrapes, pinning both the 200 and the
> presence of `wealthview_mc_optimize_seconds_bucket`. Don't reintroduce the
> second annotation; add buckets through the filter.

Note that only `wealthview.mc.optimize` gets buckets from that filter.
`wealthview.projection.run` is a plain timer — a `histogram_quantile()` over
`wealthview_projection_run_seconds_bucket` has nothing to read until you add
it to the filter too.

### Safety notes

- Tracing exporters are always potentially blocking. The OTLP exporter is
  asynchronous and bounded, but a misconfigured endpoint can still slow
  startup — keep tracing off unless you actively want it.
- Spans inherit common tags, so trace search by `application=wealthview`
  works the same way as in Prometheus.
- With tracing on, `traceId` and `spanId` land in the MDC and are promoted to
  top-level JSON log keys (see §3), so logs and traces correlate.

---

## 3. Logging

Configured in `backend/wealthview-app/src/main/resources/logback-spring.xml`,
profile by profile:

| Profile(s)          | Appender                                     | Root level |
| ------------------- | -------------------------------------------- | ---------- |
| `dev`, `default`, `loadtest` | Console, human-readable pattern with MDC fields | `INFO` |
| `docker`, `prod`    | `LogstashEncoder` JSON, wrapped in `AsyncAppender` | `INFO` |
| `it`                | Console, minimal pattern                     | `WARN`     |

The dev pattern renders the request-scoped MDC keys populated by
`JwtAuthenticationFilter` (`requestId`, `tenantId`, `userId`) plus the
per-job `operation` key set by scheduled jobs and the projection engine:

```
14:22:07.331 INFO  [a1b2c3] [t:…] [u:…] [op:projection] c.w.p.DeterministicProjectionEngine - …
```

Under `docker`/`prod` each line is a complete JSON object from
`net.logstash.logback:logstash-logback-encoder`, ready for a log shipper
(Promtail, Vector, Filebeat, a vendor agent — WealthView ships none of them).
`requestId`, `tenantId`, `userId`, `operation`, `traceId` and `spanId` are
promoted from the MDC to top-level keys rather than nested under `mdc`, and
`{"application":"wealthview"}` is added as a custom field. The JSON appender
sits behind an `AsyncAppender` (`queueSize` 2048, `discardingThreshold` 0) so
application threads never block on encoding; with `discardingThreshold` at 0
nothing is dropped preferentially — the queue simply applies backpressure.

`prod`/`docker` also pin `org.springframework` and `org.hibernate` to `WARN`;
`application-dev.yml` sets `com.wealthview` to `DEBUG`. Slow queries surface
through `org.hibernate.SQL_SLOW` at `WARN`, fed by
`session.events.log.LOG_QUERIES_SLOWER_THAN_MS` (500ms by default, 100ms in
dev).

Never log secrets. See the logging rules in `CLAUDE.md`.

---

## 4. Compose stack

The repo ships a self-contained Prometheus + Grafana stack as
`docker-compose.observability.yml`. It's layered on top of the prod compose:

```bash
docker compose -f docker-compose.prod.yml \
               -f docker-compose.observability.yml \
               up -d
```

Images are pinned: `prom/prometheus:v3.13.2` and `grafana/grafana:13.1.3`.
Both services are `restart: unless-stopped`.

### Required env vars

| Var                       | Used by    | Notes                                  |
| ------------------------- | ---------- | -------------------------------------- |
| `GRAFANA_ADMIN_PASSWORD`  | Grafana    | No fallback — Grafana fails to start without it |
| `PROMETHEUS_PORT` (opt)   | Prometheus | Defaults to 9090                       |
| `GRAFANA_PORT` (opt)      | Grafana    | Defaults to 3000                       |

Prometheus needs no credential: the overlay hard-codes
`APP_OBSERVABILITY_ANONYMOUS_METRICS: "true"` on the app service, so the scrape
is anonymous. Read the security note in the section below before enabling it.

Add them to your `.env` next to the existing `DB_PASSWORD`/`JWT_SECRET`/etc.
Note that `GRAFANA_ADMIN_PASSWORD` is not yet listed in `.env.example` — add
it there with a `CHANGE_ME` placeholder if you wire this stack up permanently.

Grafana runs with `GF_USERS_ALLOW_SIGN_UP=false` and
`GF_AUTH_ANONYMOUS_ENABLED=false`, i.e. login required.

### What you get

- Prometheus scraping `app:8080/actuator/prometheus` every 15s
  (`scrape_interval` and `evaluation_interval` both 15s, TSDB retention 30d,
  `--web.enable-lifecycle` on), plus a self-scrape of `localhost:9090`. The
  external label `application: wealthview` is set globally.
- Grafana auto-provisioned with the Prometheus datasource
  (`infra/observability/grafana/provisioning/`) and one dashboard,
  **"WealthView Overview"** (`uid: wealthview-overview`, from
  `infra/observability/grafana/dashboards/wealthview.json`), containing 14
  panels: HTTP request rate; HTTP latency p50/p95/p99; JVM heap used vs max;
  GC pause rate; Hikari connection pool (active/idle/pending/max);
  projection + MC p95 latency; Finnhub quote/candle p95 latency; login
  outcomes by result+reason; imports by format/status; rate-limit rejections
  by type; cache hit ratio per cache; time since last scheduled-job success;
  and errors by exception class.

### How the stack authenticates — and what that costs

The overlay scrapes anonymously, on purpose. Know exactly what that means
before you enable it:

1. **`infra/observability/prometheus.yml` carries no `basic_auth`.** It used to,
   and every scrape 401'd: `SecurityConfig` never enables HTTP Basic —
   `JwtAuthenticationFilter` reads a `Bearer` header or the auth cookie and
   nothing else — so the credentials could never be accepted and the stack
   silently collected nothing.
2. **Instead, `docker-compose.observability.yml` sets
   `APP_OBSERVABILITY_ANONYMOUS_METRICS: "true"` on the app service.** That
   makes `/actuator/prometheus` and `/actuator/metrics` **unauthenticated** on
   any deployment running this overlay. They stay reachable only on the compose
   network — the app's port is not published there, and you should not publish
   it to the internet or expose `/actuator` through your reverse proxy. See
   "Opting the metrics endpoints out of auth" in §1 for the full tradeoff.
   `/actuator/**` beyond those two paths remains `SUPER_ADMIN`-only.
3. **If you would rather keep the endpoints authenticated,** leave the flag at
   its `false` default and put something in front of Prometheus that injects a
   SUPER_ADMIN bearer token (`authorization: {type: Bearer, credentials: …}`,
   or a sidecar proxy). The app itself will not accept a username/password.

### Tearing it down

```bash
docker compose -f docker-compose.prod.yml \
               -f docker-compose.observability.yml \
               down
```

Use `docker compose ... down -v` to also drop the `prometheus_data` and
`grafana_data` volumes if you want a clean slate.

### Alerting rules

`infra/observability/prometheus-rules.yml` ships a recommended starter set of
ten alerts in six groups:

| Group | Alerts |
| --- | --- |
| `wealthview-liveness`   | `WealthViewAppDown` (critical) |
| `wealthview-http`       | `WealthViewHighHttpErrorRate`, `WealthViewHttpP99Latency` |
| `wealthview-jvm`        | `WealthViewHeapPressureSustained` |
| `wealthview-datasource` | `WealthViewHikariSaturated`, `WealthViewHikariConnectionsExhausted` (critical) |
| `wealthview-scheduled`  | `WealthViewPriceSyncStale`, `WealthViewPropertyValuationSyncStale` |
| `wealthview-auth`       | `WealthViewLoginFailureSpike`, `WealthViewRateLimitSustained` |

They are not loaded by Prometheus by default — mount the file into the
prometheus container and add it under `rule_files:` in `prometheus.yml` once
you wire up Alertmanager. The file's preamble has the exact
mount-and-include incantation.

`WealthViewHttpP99Latency` depends on `http_server_requests_seconds_bucket`,
which `application.yml` now publishes on every profile — see the note in §1.

### Production deployment notes

- The stack as shipped exposes Prometheus and Grafana on the host network.
  Behind a reverse proxy (Caddy, Nginx, Cloudflare Tunnel), close those
  ports and proxy `/grafana` if you want a single ingress.
- The overlay scrapes anonymously via `app.observability.anonymous-metrics`,
  which leaves `/actuator/prometheus` and `/actuator/metrics` unauthenticated
  on that deployment — acceptable for a single-node self-hosted box where the
  app's port is closed to the internet and `/actuator` is blocked at the
  reverse proxy, and nothing more. For a multi-node deployment, leave the flag
  off and put a dedicated read-only metrics token in front of the scrape
  instead. See "How the stack authenticates" above.
- Alertmanager is not included. Add it as a follow-up service when you
  decide on alert routing destinations.

---

## 5. Out of scope (for now)

- Logs *aggregation* (Loki / ELK). The app emits structured JSON (§3); no
  shipper or log store is bundled.
- APM-vendor SDKs (Datadog, New Relic, etc.).
- Frontend RUM / Web Vitals.
- Alert routing (no Alertmanager).
- Continuous profiling in production. Pyroscope exists only inside the
  `loadtest/` harness.
