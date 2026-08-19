# Stage 1: Build frontend
# Pinned by digest (node:24-alpine, NODE_VERSION=24.19.0 at time of pin).
# Moved off the node:20 line in 2026-08: Node 20 reached end-of-life in April 2026
# and stopped receiving security patches. 24.x is the current Active LTS line.
# To upgrade: `docker pull node:<tag>` then `docker inspect --format='{{index .RepoDigests 0}}' node:<tag>`.
FROM node:24-alpine@sha256:2a49bdf71e9fd965a58c1703fd9ddd205b34e5782b692a72dd1d248abb0beb43 AS frontend-build
WORKDIR /app
# Workspace manifests — must all be present for `npm ci` to resolve the
# workspace topology even though we only build frontend in this stage.
# (mobile/ is intentionally excluded from the install — it pulls in React
#  Native + native modules that are huge and irrelevant to the web build.)
COPY package.json package-lock.json ./
COPY frontend/package.json ./frontend/
COPY shared/package.json ./shared/
RUN npm ci --workspace=frontend --workspace=shared --include-workspace-root
# Source
COPY shared ./shared
COPY frontend ./frontend
# Build
RUN npm run build --workspace=frontend
# Result: /app/frontend/dist

# Stage 2: Build backend
# Pinned by digest (maven:3.9-eclipse-temurin-25, JDK 25.0.3+9 at time of pin).
# To upgrade: `docker pull maven:3.9-eclipse-temurin-25` then
# `docker inspect --format='{{index .RepoDigests 0}}' maven:3.9-eclipse-temurin-25`.
FROM maven:3.9-eclipse-temurin-25@sha256:f621e42ff394ccf7e03d0394dba557a5b885d505301886b41bb27adb20b66a65 AS build
WORKDIR /app
COPY backend/pom.xml backend/pom.xml
COPY backend/wealthview-persistence/pom.xml backend/wealthview-persistence/pom.xml
COPY backend/wealthview-core/pom.xml backend/wealthview-core/pom.xml
COPY backend/wealthview-api/pom.xml backend/wealthview-api/pom.xml
COPY backend/wealthview-import/pom.xml backend/wealthview-import/pom.xml
COPY backend/wealthview-projection/pom.xml backend/wealthview-projection/pom.xml
COPY backend/wealthview-app/pom.xml backend/wealthview-app/pom.xml
RUN cd backend && mvn dependency:go-offline -q
COPY backend backend
RUN cd backend && mvn clean package -DskipTests -q

# Stage 3: Runtime
# Pinned by digest (eclipse-temurin:25-jre-alpine, JDK 25.0.3+9 at time of pin).
# To upgrade: `docker pull eclipse-temurin:25-jre-alpine` then
# `docker inspect --format='{{index .RepoDigests 0}}' eclipse-temurin:25-jre-alpine`.
FROM eclipse-temurin:25-jre-alpine@sha256:cdd967aa55f1d0175ebe57245e4450292e6e6dd185dce73f93580598934128aa
WORKDIR /app
# `wv prune` scopes itself to this project by filtering on this label, so it can
# reclaim orphaned dev-rebuild images without touching other projects sharing the
# daemon. CI applies the same key via docker/build-push-action, so locally-built
# and published images are both matched — keep the two in sync.
LABEL org.opencontainers.image.title="WealthView"
RUN addgroup -S wv && adduser -S wv -G wv
COPY --from=build --chown=wv:wv /app/backend/wealthview-app/target/*.jar app.jar
COPY --from=frontend-build --chown=wv:wv /app/frontend/dist /app/static
USER wv
EXPOSE 8080
# Health probe uses wget: this is an Alpine JRE image and curl is NOT installed.
# This is the single source of truth for the container healthcheck — compose files
# deliberately do not override it, so the two definitions cannot drift apart.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
    CMD wget -q -O- http://localhost:8080/actuator/health || exit 1
# JVM tuning: set JAVA_TOOL_OPTIONS (e.g. "-Xmx1g -XX:MaxRAMPercentage=75"), NOT JAVA_OPTS.
# The entrypoint is exec-form on purpose — it keeps java as PID 1 so SIGTERM from
# `docker stop` reaches the JVM directly and Spring shuts down gracefully. Exec form
# means there is no shell, so no ${JAVA_OPTS} expansion happens. JAVA_TOOL_OPTIONS is
# read by the JVM itself at startup, so it works without a shell wrapper.
# Do not "fix" this by re-adding JAVA_OPTS via `sh -c` — that regresses signal handling.
ENTRYPOINT ["java", "-jar", "app.jar"]
