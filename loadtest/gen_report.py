#!/usr/bin/env python3
"""Generate a Markdown load-test report from a k6 summary + Prometheus.

Usage:
    gen_report.py <out-dir> <profile> <vus-max> <start-epoch> <end-epoch>

Reads <out-dir>/k6-summary.json, queries Prometheus on localhost:9090 for the
HikariCP pool peaks over the run window, and prints a Markdown report to stdout.
Stdlib only; robust to missing data (never crashes on empty queries).
"""
import datetime
import json
import os
import sys
import urllib.parse
import urllib.request

PROM = "http://localhost:9090"
GRAFANA = "http://localhost:3001"


def prom_query_range(expr, start, end, step=15):
    """Return the max value of an instant-vector expression over a window, or None."""
    try:
        qs = urllib.parse.urlencode(
            {"query": expr, "start": str(start), "end": str(end), "step": str(step)}
        )
        with urllib.request.urlopen(f"{PROM}/api/v1/query_range?{qs}", timeout=10) as r:
            data = json.load(r)
    except Exception:
        return None
    if data.get("status") != "success":
        return None
    peak = None
    for series in data.get("data", {}).get("result", []):
        for _, val in series.get("values", []):
            try:
                v = float(val)
            except (TypeError, ValueError):
                continue
            if peak is None or v > peak:
                peak = v
    return peak


def fmt(v, digits=2):
    return "n/a" if v is None else f"{v:.{digits}f}"


def load_summary(out_dir):
    path = os.path.join(out_dir, "k6-summary.json")
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return None


def metric_val(metric, key):
    """Pull a value (e.g. 'p(95)', 'rate', 'count') from a k6 summary metric block."""
    if not metric:
        return None
    values = metric.get("values", metric)
    return values.get(key)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    profile = sys.argv[2] if len(sys.argv) > 2 else "ramp"
    vus_max = sys.argv[3] if len(sys.argv) > 3 else "?"
    start = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    end = int(sys.argv[5]) if len(sys.argv) > 5 else 0

    summary = load_summary(out_dir)
    metrics = (summary or {}).get("metrics", {})

    out = []
    w = out.append
    w(f"# Load test report — {os.path.basename(out_dir.rstrip('/'))}")
    w("")
    w(f"- **Profile:** `{profile}`")
    w(f"- **VUS_MAX:** {vus_max}")
    if start and end:
        utc = datetime.timezone.utc
        w(f"- **Window:** {datetime.datetime.fromtimestamp(start, utc).isoformat()} "
          f"→ {datetime.datetime.fromtimestamp(end, utc).isoformat()} "
          f"({end - start}s)")
    w("")

    # --- k6 client-side metrics ---
    w("## k6 client-side results")
    w("")
    if not summary:
        w("_No k6 summary found (k6-summary.json missing or unreadable)._")
    else:
        dur = metrics.get("http_req_duration")
        failed = metrics.get("http_req_failed")
        reqs = metrics.get("http_reqs")
        # k6 Rate metrics expose the failure ratio under 'value' (with passes/fails
        # counts); Trend metrics expose percentiles. The default summary emits p90/p95
        # (p99 only when configured via summaryTrendStats), so fall back gracefully.
        p99 = metric_val(dur, "p(99)")
        if p99 is None:
            p99 = metric_val(dur, "p(90)")
            p99_label = "http_req_duration p90 (ms)"
        else:
            p99_label = "http_req_duration p99 (ms)"
        fail_rate = metric_val(failed, "value")
        if fail_rate is None:
            fail_rate = metric_val(failed, "rate")
        w("| metric | value |")
        w("| --- | --- |")
        w(f"| total requests | {fmt(metric_val(reqs, 'count'), 0)} |")
        w(f"| http_req_duration p95 (ms) | {fmt(metric_val(dur, 'p(95)'))} |")
        w(f"| {p99_label} | {fmt(p99)} |")
        w(f"| http_req_duration avg (ms) | {fmt(metric_val(dur, 'avg'))} |")
        w(f"| http_req_failed rate | {fmt(fail_rate, 4)} |")
        w("")

        # Checks pass/fail. The aggregate ratio is under 'value' for a Rate metric.
        checks = metrics.get("checks")
        if checks:
            passes = metric_val(checks, "passes") or 0
            fails = metric_val(checks, "fails") or 0
            total = passes + fails
            rate = metric_val(checks, "value")
            if rate is None:
                rate = metric_val(checks, "rate")
            w(f"**Checks:** {int(passes)}/{int(total)} passed "
              f"(rate {fmt(rate, 4)}, {int(fails)} failed)")
            w("")

    # --- Prometheus: connection pool saturation ---
    w("## Connection pool saturation")
    w("")
    peak_active = prom_query_range("max(hikaricp_connections_active)", start, end) if start else None
    peak_pending = prom_query_range("max(hikaricp_connections_pending)", start, end) if start else None
    pool_max = prom_query_range("max(hikaricp_connections_max)", start, end) if start else None
    w("| pool metric | peak |")
    w("| --- | --- |")
    w(f"| hikaricp_connections_active | {fmt(peak_active, 1)} |")
    w(f"| hikaricp_connections_pending | {fmt(peak_pending, 1)} |")
    w(f"| hikaricp_connections_max | {fmt(pool_max, 1)} |")
    w("")

    note = "No Prometheus pool data available for the window."
    if peak_active is not None and pool_max:
        util = (peak_active / pool_max) * 100 if pool_max else 0
        note = f"Peak active connections reached {fmt(peak_active, 1)} of {fmt(pool_max, 1)} ({util:.0f}% of pool)."
        if peak_pending and peak_pending > 0:
            note += (f" **Pool saturation observed:** up to {fmt(peak_pending, 1)} requests were "
                     "queued waiting for a connection — the DB connection pool is a bottleneck.")
        elif util >= 90:
            note += " Pool was near capacity; consider it the likely next bottleneck under more load."
        else:
            note += " The pool was not the saturating resource in this run."
    w(f"**Saturating resource:** {note}")
    w("")

    # --- Artifacts / links ---
    w("## Artifacts & links")
    w("")
    w(f"- Grafana overview: [{GRAFANA}/d/loadtest-overview]({GRAFANA}/d/loadtest-overview)")
    w(f"- Prometheus: [{PROM}]({PROM})")
    cpu = os.path.join(out_dir, "cpu.pprof")
    if os.path.exists(cpu):
        w(f"- CPU profile: `cpu.pprof` — `go tool pprof -http=:8080 {cpu}`")
    elif os.path.exists(os.path.join(out_dir, "cpu.pprof.SKIPPED")):
        w("- CPU profile: _skipped_ — see `cpu.pprof.SKIPPED` for manual export steps")
    if os.path.isdir(os.path.join(out_dir, "prometheus-snapshot")):
        w("- Prometheus TSDB snapshot: `prometheus-snapshot/`")
    if os.path.exists(os.path.join(out_dir, "manifest.json")):
        w("- Seed manifest: `manifest.json`")
    w("")

    print("\n".join(out))


if __name__ == "__main__":
    main()
