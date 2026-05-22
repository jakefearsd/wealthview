# Load Test Harness — Design Spec

**Date:** 2026-05-22
**Status:** Approved (design)
**Scope:** A capacity / breaking-point + hot-path profiling load-test harness for the WealthView backend, driven by [k6](https://k6.io) against a fully isolated local Docker stack seeded with synthetic multi-tenant data.

---

## 1. Goal & non-goals

### Goal
- **Capacity / breaking-point:** ramp load until the system degrades, to find the maximum sustainable throughput and *which resource* saturates first (HikariCP pool, CPU, GC, Postgres connections).
- **Hot-path profiling:** characterize the known expensive endpoints under sustained load — the Monte Carlo guardrail optimizer, the deterministic projection run, the Roth conversion optimizer, and the dashboard/portfolio-history aggregation.
- **Basic verification:** confirm the core read/auth flows behave correctly under concurrency (no 5xx storms, tenant isolation holds).

### Non-goals (YAGNI — explicitly out of scope)
- **No CI regression gate.** This is an exploratory/characterization harness, not a per-PR pass/fail gate. Thresholds are recorded as *observations*, not build-failing assertions.
- **No SLO contract validation** against a prod-like environment.
- **No staging / remote target.** Local `./wv`-style Docker only.
- **No distributed/multi-node k6.** Single k6 runner is sufficient for a single-host target.
- **No changes to production code paths** beyond an additive, profile-gated seeder. The harness must not alter runtime behaviour of the app under the default/dev/docker/prod profiles.

---

## 2. Architecture & isolation

A dedicated, disposable load-test stack, completely separate from the dev stack and its data.

- **`loadtest/docker-compose.loadtest.yml`** defines:
  - `loadtest-db` — its own PostgreSQL 16 container, **separate named volume**, exposed on host port **5434** (dev DB stays on 5433, native PG on 5432 untouched).
  - `loadtest-app` — a dedicated app instance built from the same image, run under a new Spring **`loadtest`** profile whose datasource points **only** at `loadtest-db`. Internal-network port only; not bound to the dev app's port.
  - `k6` — the Grafana k6 image, on the same compose network, hitting `loadtest-app` over the internal network (avoids host round-trip skew). Configured to **remote-write its own metrics to Prometheus** so client-side load metrics live alongside server-side metrics in Grafana.
  - **`prometheus`** — first-class, always on. Scrapes `loadtest-app` `/actuator/prometheus`, the Postgres exporter, and receives k6's remote-write stream.
  - **`postgres-exporter`** — `postgres_exporter` against `loadtest-db` for connection/lock/throughput series.
  - **`grafana`** — first-class, always on. Pre-provisioned datasources (Prometheus + Pyroscope) and dashboards; the harness prints its URL at run start.
  - **`pyroscope`** — Grafana Pyroscope server for continuous JVM profiling. `loadtest-app` runs with the **Pyroscope Java agent** attached (async-profiler: CPU, alloc, lock), pushing profiles to Pyroscope; viewable as flame graphs in Grafana and **exportable in pprof format** for offline analysis (`go tool pprof` / the pprof web UI). The agent is attached **only** in the loadtest stack, so it never touches dev/docker/prod app behaviour.
- **Isolation guarantees:**
  - The `loadtest` Spring profile is mutually exclusive with dev/docker/prod; the seeder (§3) is `@Profile("loadtest")` so it can never run against real data.
  - The load DB volume is ephemeral — `run.sh` wipes and re-creates it each run by default, so runs are reproducible and leave no residue.
  - Tenant isolation in the app is unchanged; the harness exercises it but does not weaken it.

### Rejected alternative
k6 against the existing `./wv` dev stack with API-driven seeding — simpler infra, but pollutes the dev DB and cannot reach realistic data volumes. Ruled out by the isolation requirement.

---

## 3. Synthetic data seeding

A **`LoadTestDataSeeder`** in `wealthview-app` (alongside the existing `SampleDataInitializer` / `DevDataInitializer`), activated **only** under `@Profile("loadtest")`, run once at stack bootstrap.

- **Volume (defaults, configurable via env/properties):**
  - `loadtest.tenants` = **25** synthetic tenants.
  - Each tenant: a spread of accounts (brokerage / IRA / Roth / taxable / bank), **hundreds–low-thousands** of transactions and holdings, multi-year price history for the held symbols, and **at least one** projection scenario plus a guardrail and a Roth-conversion config — so the compute-heavy endpoints have real inputs.
  - Each tenant gets one login user with known, clearly-fake credentials (e.g. `loadtest-tenant-{n}@loadtest.local` / a fixed fake password — obviously non-secret, satisfies the secrets policy).
- **Performance:** high-volume tables (transactions, holdings, prices) are inserted via **JDBC batch** so seeding 25 tenants completes in seconds, not minutes. Lower-volume rows use normal repository saves for clarity.
- **Determinism:** a fixed RNG seed so data volumes/shapes are identical run-to-run and results are comparable.
- **Manifest:** the seeder writes a `loadtest/results/manifest.json` (or a well-known volume path) listing tenant IDs + login credentials. k6 `setup()` reads it to spread VUs across tenants.
- **Why Java, not raw SQL:** reuses the real entities, constraints, and conventions (no duplicated schema knowledge that drifts from migrations); batch JDBC is fast enough at this scale.

---

## 4. k6 scenarios & load profiles (TypeScript)

Scripts in `loadtest/scenarios/`, written in TypeScript, sharing a `lib/` for auth + helpers.

- **`setup()`** — reads the manifest, logs in as each synthetic tenant, captures the **httpOnly access-token cookie + XSRF token** (the app's web transport), and returns a pool of authenticated sessions for the VUs to draw from. Sessions are refreshed if they near expiry during long runs.
- **Scenario groups (weighted):**
  - **Read baseline** — `GET /accounts`, holdings, `dashboard/summary`, `dashboard/portfolio-history`. High weight; represents normal browsing.
  - **Hot paths** (the focus) — projection run, Monte Carlo guardrail **optimize**, Roth conversion optimize. Lower request rate but CPU-heavy; the most likely saturation source.
  - **Light write** (optional, low weight) — create a transaction, for write-path realism. Each VU writes only into its own tenant.
- **Load profiles (selectable via `run.sh --profile`):**
  - **`ramp`** — a `ramping-vus` / `ramping-arrival-rate` executor that steps load up (e.g. 10 → 50 → 100 → 200 → 400 …) holding each step, until the latency knee or error-rate rise reveals the breaking point.
  - **`soak`** — steady moderate load focused on the hot-path scenarios, to characterize sustained behaviour (GC pressure, pool churn, memory growth).
- **Checks & thresholds** — per-scenario `check()`s for 2xx/expected bodies (basic verification) and recorded `thresholds` for p95/p99 latency and error rate. Recorded for the report; **not** build-failing.

---

## 5. Metrics, observability & profiling

A solid, first-class observability stack — **Prometheus + Grafana + Pyroscope** — is a primary way results are consumed, alongside exported pprof profiles. It comes up with every run.

### Metrics (Prometheus → Grafana)
A single Prometheus instance ingests three sources, unified in Grafana:
- **App** — `loadtest-app` `/actuator/prometheus` (Micrometer). Key series: **HikariCP** (`hikaricp_connections_active` / `pending` / `idle` / `max` — the 20-connection pool is the prime saturation suspect), **JVM** (heap, GC pause time/count, threads), **HTTP server** (`http_server_requests` latency histogram + counts per URI/status), and the app's own cache/business meters.
- **Postgres** — `postgres_exporter` against `loadtest-db`: active/idle/waiting connections, commits/rollbacks, locks, tuple throughput.
- **k6** — k6's experimental Prometheus **remote-write** output streams client-side load metrics (RPS, p50/p95/p99 per scenario+endpoint, error rate, dropped iterations, VU count) into the same Prometheus.

**Grafana** ships with pre-provisioned datasources and dashboards (committed as JSON):
1. **Load overview** — k6 RPS / latency percentiles / error rate / active VUs over the run.
2. **App internals** — HTTP latency by endpoint, HikariCP pool, JVM heap + GC.
3. **Database** — Postgres connections, locks, throughput.
4. **Profiling** — embedded Pyroscope flame graphs.

Because k6 client metrics and server metrics share a timeline in Grafana, the latency knee can be read directly against pool exhaustion / GC pauses / CPU.

### Continuous profiling (Pyroscope → pprof)
- `loadtest-app` runs with the **Pyroscope Java agent** (async-profiler) capturing **CPU, allocation, and lock** profiles continuously, tagged by time so a run window can be isolated.
- Profiles are viewable as **flame graphs in Grafana** (Pyroscope datasource) and **exported in pprof format** per run, so the hot-path endpoints (Monte Carlo optimize, projection, Roth optimize) can be analyzed offline with `go tool pprof` or the pprof web UI.
- The agent is attached **only** via the loadtest stack's JVM opts; no other profile runs it.

### Per-run report & artifacts
Each run writes a timestamped folder `loadtest/results/<ts>/` containing:
- the k6 JSON summary + human-readable summary,
- the exported **pprof** profiles (CPU + alloc) for the run window,
- a **Prometheus snapshot** (or exported series) so the run's metrics are reproducible offline,
- the run parameters/manifest,
- a generated `REPORT.md` correlating the latency knee with the saturating resource and linking the relevant flame graph.

Grafana + Pyroscope stay up after the run by default (for interactive exploration); `run.sh --teardown` (or `--no-keep`) tears the whole stack down.

---

## 6. Repo layout & orchestration

```
loadtest/
  docker-compose.loadtest.yml   # isolated pg(5434) + app(loadtest profile, pyroscope agent)
                                #   + k6 + prometheus + postgres-exporter + grafana + pyroscope
  scenarios/
    lib/            # auth (cookie+XSRF), session pool, manifest loader, helpers
    baseline.ts     # read-baseline scenario
    hotpaths.ts     # projection / MC optimize / Roth optimize
    ramp.ts         # ramping executor wiring → breaking point
    soak.ts         # steady-state hot-path soak
  observability/
    prometheus/     # prometheus.yml (scrape app + pg-exporter; k6 remote-write receiver)
    grafana/        # provisioning: datasources (prometheus, pyroscope) + dashboards/*.json
    pyroscope/      # pyroscope server config; agent jar + JVM opts wiring for loadtest-app
    pg-exporter/    # postgres-exporter config
  seed/             # notes/SQL helpers if needed; primary seeder is Java @Profile("loadtest")
  run.sh            # bootstrap(wipe+up+healthcheck) → seed → run k6 → export pprof + prom snapshot
                    #   → generate REPORT.md → (keep stack up for exploration | teardown)
  README.md         # how to run, knobs, how to read dashboards / flame graphs / the report
  results/          # gitignored run outputs (k6 json, pprof, prom snapshot, manifest, REPORT.md)
```

- **Backend addition:** `LoadTestDataSeeder` (+ a `loadtest` profile config: datasource → `loadtest-db`, seeding knobs). Additive and profile-gated; no behaviour change to existing profiles. The Pyroscope Java agent is wired only via the loadtest container's `JAVA_TOOL_OPTIONS`/entrypoint, not the app image default.
- **Single entry point:** `loadtest/run.sh --profile ramp --vus-max 400 --tenants 25` with sensible defaults. Flags: `--profile soak`, `--smoke` (1 VU validation run), `--keep`/`--teardown`, `--vus-max`, `--tenants`. The full observability stack (Prometheus/Grafana/Pyroscope/pg-exporter) is **always on** — it is core, not optional.
- **Kept separate from `./wv`** — `./wv` manages real dev/prod stacks; this harness is throwaway and isolated, so it gets its own runner.

---

## 7. Success criteria

- `loadtest/run.sh` with defaults: brings up an isolated stack on a separate DB **plus the Prometheus/Grafana/Pyroscope/pg-exporter observability stack**, seeds 25 synthetic tenants, runs the k6 ramp profile, and produces a timestamped report — in one command, leaving the dev DB untouched.
- **Grafana** shows, on a shared timeline, k6 load metrics + app HTTP/HikariCP/JVM + Postgres, with **Pyroscope flame graphs** for the run; the harness prints the Grafana URL.
- Each run exports **pprof** CPU + alloc profiles and a Prometheus snapshot into `results/<ts>/` for offline analysis.
- The generated report identifies, for the ramp, the approximate breaking point (VUs/RPS at the latency knee) and the first saturating resource (with HikariCP/JVM/PG + flame-graph evidence).
- The hot-path soak yields stable p95/p99 figures for the projection and optimizer endpoints, with a flame graph showing where their CPU goes.
- Re-running with the same parameters produces comparable results (deterministic seed + ephemeral DB).
- Tests: the seeder has a focused test verifying it produces the expected tenant/data shape; k6 scripts are validated by a smoke run (1 VU, short duration) wired into `run.sh --smoke`; `run.sh` verifies every observability target is healthy/scraping before starting load.

---

## 8. Open knobs (defaults chosen, tunable later)

| Knob | Default | Notes |
|---|---|---|
| Tenant count | 25 | `loadtest.tenants` |
| Per-tenant txn volume | ~1–2k | tune for realistic projection/aggregation cost |
| Ramp stages | 10→50→100→200→400 VUs | adjust to the host's headroom |
| Prometheus scrape interval | 5s | app + pg-exporter |
| Observability stack | **always on** | Prometheus + Grafana + Pyroscope + pg-exporter are core |
| Pyroscope profiles | CPU + alloc + lock | exported as pprof per run |
