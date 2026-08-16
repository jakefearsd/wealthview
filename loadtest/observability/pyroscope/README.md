# Pyroscope Java agent

`run.sh` downloads `pyroscope.jar` here before bringing up the stack, and only
if the file is missing:

    curl -sSL -o loadtest/observability/pyroscope/pyroscope.jar \
      https://github.com/grafana/pyroscope-java/releases/latest/download/pyroscope.jar

The jar is gitignored (`.gitignore` line `loadtest/observability/pyroscope/pyroscope.jar`).
Delete it and re-run `run.sh` to pull a newer release.

This whole directory is bind-mounted read-only into the app container as
`/pyroscope`, and the agent is attached through `JAVA_TOOL_OPTIONS`
(`-javaagent:/pyroscope/pyroscope.jar`) — the JVM honours that additively on
top of the image's `java -jar app.jar` entrypoint, so the Dockerfile needs no
loadtest-specific change.

Agent settings live in the `loadtest-app` service in
`docker-compose.loadtest.yml`:

| Env var | Value |
| --- | --- |
| `PYROSCOPE_APPLICATION_NAME` | `wealthview-loadtest` (the service name to select in Grafana) |
| `PYROSCOPE_SERVER_ADDRESS` | `http://pyroscope:4040` |
| `PYROSCOPE_PROFILING_INTERVAL` | `10ms` |
| `PYROSCOPE_PROFILER_EVENT` | `itimer` |
| `PYROSCOPE_PROFILER_ALLOC` | `512k` |
| `PYROSCOPE_PROFILER_LOCK` | `10ms` |
| `PYROSCOPE_FORMAT` | `jfr` |

`itimer` is signal-based rather than `perf_events`-based, so it works in an
unprivileged container without extra capabilities.
