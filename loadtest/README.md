# WealthView Load-Test Harness

A self-contained capacity / breaking-point and hot-path profiling rig for the
WealthView backend. It brings up an **isolated** copy of the application against
its **own** Postgres (on port **5434**), seeds it with synthetic tenants, runs a
[k6](https://k6.io/) load scenario that ramps virtual users until latency knees,
and captures everything you need to read the result: live Grafana dashboards, a
Prometheus snapshot, a CPU flame profile, and a generated `REPORT.md`.

## Purpose

- **Capacity / breaking point.** Ramp concurrent virtual users (VUs) against the
  CPU-heavy projection and Monte Carlo optimization endpoints until p95 latency
  climbs and a resource saturates, so you can find the knee and the bottleneck
  (DB pool? CPU? heap/GC?).
- **Hot-path profiling.** A Pyroscope Java agent continuously profiles the app;
  under sustained load you get real CPU flame graphs pointing into the
  projection / Monte Carlo code.

This is an **exploratory tool, not a CI gate.** The k6 thresholds in the
scenarios are recorded as observations, not enforced pass/fail gates. Run it by
hand when you want to understand capacity or chase a hot path.

> **It never touches your dev data.** The harness runs as its own Docker Compose
> project (`wealthview-loadtest`) with its own volume and its own Postgres on
> **5434**. Your dev DB on **5433** is left completely alone. See
> [Isolation guarantees](#isolation-guarantees).

## Prerequisites

- **Docker** + **docker compose** (the whole stack runs in containers).
- **Node ≥ 20** — `run.sh` runs `npm install` + `npm run build` to bundle the
  TypeScript k6 scenarios into `scenarios/dist/` with esbuild.
- **`go`** — *only* if you want to open the exported `cpu.pprof` locally with
  `go tool pprof`. Not needed to run the harness; you can also explore the
  profile live in Grafana.

No JDK or Maven needed on the host — the app is built inside the Docker image.

## Usage

Everything goes through `loadtest/run.sh`:

```bash
loadtest/run.sh [--profile ramp|soak] [--vus-max N] [--tenants N]
                [--smoke] [--keep | --teardown]
```

| Flag | Default | Meaning |
| --- | --- | --- |
| `--profile ramp\|soak` | `ramp` | Which k6 scenario to run. `ramp` ramps VUs up and back down through baseline reads then the hot paths; `soak` holds a constant VU level on the hot paths. |
| `--vus-max N` | `200` | Peak VU count the ramp/soak scales its stages against. |
| `--tenants N` | `25` | Number of synthetic tenants to seed into the loadtest DB. |
| `--smoke` | off | Quick 1-VU / 30s validation that runs `hotpaths.js` directly with a tiny seed (5 tenants, 100 txns each). Forces the `ramp` profile name but bypasses the multi-minute ramp stages. |
| `--keep` | **on** | Leave the stack up after the run so you can explore Grafana. |
| `--teardown` | off | `docker compose ... down -v` the stack when the run finishes. |

> **Heads up:** every invocation begins with `down -v` (wiping the prior volume)
> because the seeder is **not idempotent** — it would hit a duplicate-key on a
> second seed. So a fresh run always starts from a clean DB, and `--keep` only
> keeps the stack up *until your next run*.

### Examples

```bash
# Fast end-to-end validation (1 VU, 30s, tiny seed). 35 hot-path checks.
loadtest/run.sh --smoke

# A real ramp to a peak of 80 VUs over 25 synthetic tenants.
VUS_MAX=80 loadtest/run.sh --profile ramp --tenants 25

# A 30-minute soak holding a constant VU level on the hot paths.
SOAK_DURATION=30m loadtest/run.sh --profile soak --vus-max 60

# Run, then tear the whole stack + volume down afterwards.
loadtest/run.sh --profile ramp --teardown
```

Flags and env vars overlap: `--vus-max 80` and `VUS_MAX=80` are equivalent
(`run.sh` exports the flag values as env vars for k6 and the seeder).

### What the ramp actually does

`ramp.js` defines two sequential k6 scenarios driven off `VUS_MAX`:

1. **`read_baseline`** — ramps `0 → 10% → 25% → 50% → 100%` of `VUS_MAX` over
   ~8 minutes hitting the cheap read endpoints (accounts, dashboard summary,
   portfolio history).
2. **`hot_paths`** — ramps up to ~25% of `VUS_MAX` over ~7 minutes hitting the
   CPU-heavy endpoints: scenario list, deterministic projection run, Monte Carlo
   guardrail optimize, and Roth-conversion optimize.

`soak.js` instead holds a constant `max(5, 15% of VUS_MAX)` VUs on the hot paths
for `SOAK_DURATION` (default `15m`).

## What comes up

`run.sh` builds and starts the isolated stack, waits for the app health check,
seeds tenants on app boot (via the `loadtest` Spring profile), then runs k6.
Services and URLs:

| Service | URL | Notes |
| --- | --- | --- |
| **Grafana** | http://localhost:3001 | Anonymous Admin (no login). Pre-provisioned datasources + the "Load Test — Overview" dashboard. |
| **Prometheus** | http://localhost:9090 | Scrapes the app + postgres-exporter; receives k6 metrics via remote-write. |
| **Pyroscope** | http://localhost:4040 | Continuous JVM profiles from the app's Pyroscope agent. |
| **App** | http://localhost:8081 | The WealthView backend under test (loadtest profile). |
| Postgres | localhost:**5434** | The isolated loadtest DB (not normally accessed directly). |

## Reading results

### Live in Grafana — "Load Test — Overview"

Open http://localhost:3001 and the **Load Test — Overview** dashboard
(`/d/loadtest-overview`). The panels are arranged to let you correlate the
**latency knee** with the **saturating resource**:

- **k6 request rate (req/s)** — offered load over time.
- **k6 p95 latency by endpoint (s)** — per-scenario client-side latency; watch
  for the knee on `projection_run` / `mc_optimize` / `roth_optimize`.
- **k6 error rate** — should stay near zero until something breaks.
- **HTTP server p95 by URI (s)** — server-side latency, to confirm the slowdown
  is in the app and see which URI.
- **HikariCP connections** — active / pending / max. Pending > 0 means requests
  are queued waiting for a DB connection (the pool is the bottleneck).
- **JVM heap used (bytes)** and **JVM GC pause rate (s/s)** — heap pressure / GC
  as the CPU bottleneck signal.
- **Postgres backends** — DB-side connection count.

Line up the timestamp where **k6 p95** starts climbing with whichever resource
panel saturates at the same moment — that's your bottleneck.

### Flame graphs

In Grafana, go to **Explore → Pyroscope datasource**, select service
`wealthview-loadtest`, and pick the `process_cpu` profile type for the run
window. Under sustained hot-path load the flame graph concentrates in the
projection / Monte Carlo code.

The run also exports a binary pprof. Open it locally:

```bash
go tool pprof -http=:8080 loadtest/results/<ts>/cpu.pprof
# or, top frames in the terminal:
go tool pprof -top -nodecount=20 loadtest/results/<ts>/cpu.pprof
```

> The pprof is exported by `profilecli` from *inside* the Pyroscope container
> (the render API on this Pyroscope version returns a JSON flamebearer, not a
> binary pprof). On tiny runs (e.g. `--smoke`) the profile is near-empty; under a
> sustained multi-VU ramp it fills with real CPU samples. If the binary export
> can't be produced on your Pyroscope version, `run.sh` writes a
> `cpu.pprof.SKIPPED` note with manual export steps and saves a
> `cpu-flamebearer.json` instead.

### `REPORT.md`

Each run writes `loadtest/results/<ts>/REPORT.md` (generated by `gen_report.py`):

- **k6 client-side results** — total requests, `http_req_duration` p95 / p99 (or
  p90 fallback) / avg, error rate, and the checks pass count.
- **Connection pool saturation** — peak HikariCP active vs. pending vs. max
  pulled from Prometheus over the run window, plus a one-line verdict on whether
  the pool was the saturating resource (`peak active X of Y (Z% of pool)` and a
  pending-queue warning if any requests waited).
- **Artifacts & links** — Grafana / Prometheus links, the `cpu.pprof` command,
  the Prometheus TSDB snapshot, and the seed manifest.

### Other artifacts under `loadtest/results/<ts>/`

- `k6-summary.json` — raw k6 end-of-test summary.
- `k6-stdout.txt` — the full k6 console output (checks, thresholds).
- `cpu.pprof` (or `cpu.pprof.SKIPPED` + `cpu-flamebearer.json`).
- `prometheus-snapshot/` — a Prometheus TSDB snapshot of the run window.
- `manifest.json` — the synthetic tenants the seeder produced (emails/passwords
  the k6 VUs log in as).

`loadtest/results/` is gitignored — it is local scratch, not committed.

## Isolation guarantees

- **Separate Compose project, volume, and DB.** The stack runs as
  `wealthview-loadtest` with its own `loadtest-db-data` volume and Postgres on
  **5434**. Your dev stack / DB on **5433** is never touched.
- **Seeder is loadtest-only.** The synthetic-tenant seeder is `@Profile("loadtest")`
  and only runs under `SPRING_PROFILES_ACTIVE=loadtest`, which only this stack
  sets. It cannot run against the dev or prod DB.
- **Clean slate per run.** `run.sh` does `down -v` at the **start** of each run
  because the seeder is not idempotent — so each run begins from an empty volume.
- **Stays up by default.** With `--keep` (the default), the stack stays running
  after the run so you can explore Grafana, until you do another run or pass
  `--teardown`.

To tear down manually:

```bash
docker compose -f loadtest/docker-compose.loadtest.yml --profile k6 down -v
```

## Knobs

| Env var | Default | Effect |
| --- | --- | --- |
| `LOADTEST_TENANTS` | `25` (smoke: `5`) | Number of synthetic tenants seeded. Also settable via `--tenants`. |
| `LOADTEST_TXNS_PER_TENANT` | `1500` (smoke: `100`) | Transactions seeded per tenant — controls dataset size / query cost. |
| `VUS_MAX` | `200` | Peak VU count the ramp/soak stages scale against. Also settable via `--vus-max`. |
| `SOAK_DURATION` | `15m` | Hold duration for the `soak` profile. |
