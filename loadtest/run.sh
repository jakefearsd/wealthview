#!/usr/bin/env bash
# WealthView load-test harness orchestrator.
#
# One command brings up an isolated stack (separate Postgres on 5434, a
# dedicated app on the `loadtest` profile, Prometheus, postgres-exporter,
# Pyroscope, Grafana), seeds synthetic tenants, builds + runs the k6 scenario
# (remote-writing its metrics into Prometheus), exports a CPU pprof + a
# Prometheus TSDB snapshot, and generates REPORT.md.
#
# Flags:
#   --profile ramp|soak   k6 scenario to run (default: ramp)
#   --vus-max N           peak VU count for the ramp/soak (default: 200)
#   --tenants N           synthetic tenants to seed (default: 25)
#   --smoke               1 VU / 30s against ramp.js with a tiny seed (fast)
#   --keep                leave the stack up afterwards (default)
#   --teardown            `down -v` the stack when done
set -euo pipefail
cd "$(dirname "$0")"
COMPOSE="docker compose -f docker-compose.loadtest.yml"

PROFILE="ramp"; KEEP=1; SMOKE=0
export LOADTEST_TENANTS="${LOADTEST_TENANTS:-25}"
export LOADTEST_TXNS_PER_TENANT="${LOADTEST_TXNS_PER_TENANT:-1500}"
export VUS_MAX="${VUS_MAX:-200}"
while [[ $# -gt 0 ]]; do case "$1" in
  --profile) PROFILE="$2"; shift 2;;
  --vus-max) export VUS_MAX="$2"; shift 2;;
  --tenants) export LOADTEST_TENANTS="$2"; shift 2;;
  --smoke) SMOKE=1; PROFILE="ramp"; shift;;
  --teardown) KEEP=0; shift;;
  --keep) KEEP=1; shift;;
  *) echo "unknown arg $1"; exit 1;;
esac; done

# A smoke run wants a small, fast seed.
if [[ "$SMOKE" == "1" ]]; then
  export LOADTEST_TENANTS="${LOADTEST_TENANTS_SMOKE:-5}"
  export LOADTEST_TXNS_PER_TENANT="${LOADTEST_TXNS_PER_TENANT_SMOKE:-100}"
fi

TS="$(date +%Y%m%d-%H%M%S)"; OUT="results/$TS"; mkdir -p "$OUT" results
# k6 runs as a non-root uid inside its container and writes the summary into this
# subdir via the bind mount; make it world-writable so that write succeeds.
chmod 777 "$OUT" 2>/dev/null || true

echo "==> Fetching Pyroscope agent (if missing)"
[[ -f observability/pyroscope/pyroscope.jar ]] || \
  curl -sSL -o observability/pyroscope/pyroscope.jar \
    https://github.com/grafana/pyroscope-java/releases/latest/download/pyroscope.jar

echo "==> Bringing up isolated stack (wiping prior volume)"
# The seeder is NOT idempotent on users — always start from a clean volume.
$COMPOSE down -v >/dev/null 2>&1 || true
$COMPOSE up --build -d loadtest-db loadtest-app prometheus postgres-exporter pyroscope grafana

echo "==> Waiting for app to be healthy"
for _ in $(seq 1 100); do
  if $COMPOSE ps loadtest-app 2>/dev/null | grep -q healthy; then break; fi
  sleep 3
done
$COMPOSE ps loadtest-app | grep -q healthy || { echo "app never went healthy"; $COMPOSE logs --tail=80 loadtest-app; exit 1; }

echo "==> Waiting for Prometheus to be ready"
for _ in $(seq 1 30); do
  if curl -sf http://localhost:9090/-/ready >/dev/null 2>&1; then break; fi
  sleep 2
done
echo "    Grafana: http://localhost:3001   Prometheus: http://localhost:9090   Pyroscope: http://localhost:4040"

echo "==> Building k6 scenarios"
( cd scenarios && npm install --silent && npm run build --silent )

START_EPOCH=$(date +%s)
# k6 exits 99 when a threshold is crossed. For a ramp/soak that IS the point —
# we ramp until latency/errors breach the thresholds. So capture k6's exit code
# instead of letting `set -e`/`pipefail` abort the script before we collect the
# pprof, the Prometheus snapshot, and REPORT.md. (`|| true` keeps the pipeline
# from tripping `set -e`; PIPESTATUS[0] is k6's real exit code.)
# --no-deps: the app+stack are already up and seeded. Without this, `compose run`
# recreates the app container, which re-runs the (non-idempotent) seeder against
# the already-seeded DB and crashes on a duplicate-key. The stack is healthy here.
if [[ "$SMOKE" == "1" ]]; then
  echo "==> SMOKE run (1 VU, 30s on the hot paths)"
  # ramp.js exposes only named execs + an `options` block with multi-minute
  # ramping stages, and this k6 version has no `--exec` flag to pick one. For a
  # fast 1-VU/30s smoke we run hotpaths.js directly (it has a `default` export and
  # exercises the same projection/optimize hot-path checks).
  $COMPOSE run --rm --no-deps -e VUS_MAX=1 k6 run --vus 1 --duration 30s \
    -o experimental-prometheus-rw \
    --summary-export="/loadtest/results/$TS/k6-summary.json" \
    /scripts/hotpaths.js | tee "$OUT/k6-stdout.txt" || true
else
  echo "==> k6 $PROFILE run (VUS_MAX=$VUS_MAX)"
  # `compose run` does NOT inherit the host shell's env into the container, and
  # the k6 service's env block doesn't declare VUS_MAX/SOAK_DURATION — so the
  # scenarios would otherwise fall back to their compiled-in defaults (VUS_MAX
  # 200) and ignore --vus-max/--profile soak duration. Pass them through with -e.
  $COMPOSE run --rm --no-deps \
    -e "VUS_MAX=$VUS_MAX" \
    -e "SOAK_DURATION=${SOAK_DURATION:-}" \
    k6 run \
    -o experimental-prometheus-rw \
    --summary-export="/loadtest/results/$TS/k6-summary.json" \
    "/scripts/$PROFILE.js" | tee "$OUT/k6-stdout.txt" || true
fi
K6_EXIT="${PIPESTATUS[0]}"
END_EPOCH=$(date +%s)
case "$K6_EXIT" in
  0)  echo "    k6 exited 0 (all thresholds held)";;
  99) echo "    k6 exited 99 — a threshold was crossed (expected at the breaking point); continuing to collect artifacts";;
  *)  echo "    k6 exited $K6_EXIT (run may be incomplete); continuing to collect artifacts";;
esac

echo "==> Exporting pprof CPU profile for the run window (best-effort)"
PPROF_OK=0
PROF_TYPE='process_cpu:cpu:nanoseconds:cpu:nanoseconds'
SEL='{service_name="wealthview-loadtest"}'
# Primary: profilecli ships inside the grafana/pyroscope image and writes a real
# binary (gzipped) pprof that `go tool pprof` reads. The render API on this
# version ignores format=pprof (returns JSON flamebearer) and the HTTP Connect
# SelectMergeProfile returns JSON-encoded proto — neither yields a usable binary.
# Pad the window a little so short runs still capture samples.
PF=$((START_EPOCH - 60)); PT=$((END_EPOCH + 30))
if $COMPOSE exec -T pyroscope profilecli query profile \
      --url=http://localhost:4040 \
      --query="$SEL" \
      --profile-type="$PROF_TYPE" \
      --from="${PF}" --to="${PT}" \
      --output=pprof=/tmp/cpu.pprof -f >/dev/null 2>&1 \
   && $COMPOSE cp pyroscope:/tmp/cpu.pprof "$OUT/cpu.pprof" >/dev/null 2>&1 \
   && [[ -s "$OUT/cpu.pprof" ]]; then
  PPROF_OK=1
  echo "    pprof exported via profilecli (in the pyroscope container)"
fi
# Fallback: the Pyroscope JSON render (flamebearer) — not a binary pprof, but it
# carries the same data and can be inspected/converted offline.
if [[ "$PPROF_OK" == "0" ]]; then
  if curl -sfG 'http://localhost:4040/pyroscope/render' \
      --data-urlencode "query=${PROF_TYPE}${SEL}" \
      --data-urlencode "from=${PF}" --data-urlencode "until=${PT}" \
      -o "$OUT/cpu-flamebearer.json" 2>/dev/null && [[ -s "$OUT/cpu-flamebearer.json" ]]; then
    echo "    profilecli unavailable — saved JSON flamebearer as cpu-flamebearer.json"
  fi
  cat > "$OUT/cpu.pprof.SKIPPED" <<EOF
A binary cpu.pprof could not be produced automatically on this Pyroscope version.

The /pyroscope/render endpoint ignores format=pprof here (returns a JSON
flamebearer) and the HTTP Connect SelectMergeProfile call returns JSON-encoded
protobuf, not a binary pprof. profilecli (which the grafana/pyroscope image
bundles) was also unavailable.

To export manually, run profilecli inside the pyroscope container:

  docker compose -f docker-compose.loadtest.yml exec pyroscope \\
    profilecli query profile --url=http://localhost:4040 \\
      --query='{service_name="wealthview-loadtest"}' \\
      --profile-type='${PROF_TYPE}' \\
      --from=${PF} --to=${PT} --output=pprof=/tmp/cpu.pprof -f
  docker compose -f docker-compose.loadtest.yml cp pyroscope:/tmp/cpu.pprof ./cpu.pprof

Then: go tool pprof -http=:8080 cpu.pprof
Or explore the flame graph live in Grafana (http://localhost:3001, Pyroscope datasource).
EOF
  echo "    binary pprof skipped — wrote $OUT/cpu.pprof.SKIPPED"
fi

echo "==> Snapshotting Prometheus TSDB"
SNAP=$(curl -s -XPOST http://localhost:9090/api/v1/admin/tsdb/snapshot \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['name'])" 2>/dev/null || true)
if [[ -n "${SNAP:-}" ]]; then
  $COMPOSE cp "prometheus:/prometheus/snapshots/$SNAP" "$OUT/prometheus-snapshot" \
    && echo "    snapshot copied to $OUT/prometheus-snapshot" \
    || echo "    (prom snapshot copy failed)"
else
  echo "    (prom snapshot skipped)"
fi

cp results/manifest.json "$OUT/" 2>/dev/null || true

echo "==> Generating REPORT.md"
python3 gen_report.py "$OUT" "$PROFILE" "$VUS_MAX" "$START_EPOCH" "$END_EPOCH" > "$OUT/REPORT.md" || \
  echo "# Load test $TS ($PROFILE, VUS_MAX=$VUS_MAX)" > "$OUT/REPORT.md"

echo "==> Done. Artifacts in $OUT"
echo "    Explore live: http://localhost:3001 (Grafana)"
if [[ "$KEEP" == "0" ]]; then echo "==> Tearing down"; $COMPOSE --profile k6 down -v; fi
