# Pyroscope Java agent

`run.sh` downloads `pyroscope.jar` here before bringing up the stack:

    curl -sSL -o loadtest/observability/pyroscope/pyroscope.jar \
      https://github.com/grafana/pyroscope-java/releases/latest/download/pyroscope.jar

The jar is mounted read-only into the app container and attached via
`JAVA_TOOL_OPTIONS=-javaagent:/pyroscope/pyroscope.jar`. It is gitignored.
