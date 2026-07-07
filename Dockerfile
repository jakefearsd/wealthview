# Stage 1: Build frontend
# Pinned by digest (node:20-alpine, NODE_VERSION=20.20.2 at time of pin).
# To upgrade: `docker pull node:<tag>` then `docker inspect --format='{{index .RepoDigests 0}}' node:<tag>`.
FROM node:20-alpine@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293 AS frontend-build
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
FROM maven:3.9-eclipse-temurin-25@sha256:7e461cec477077c1d9e50b13df8aef9018764410f4c4cd7c34803f10c4c99e4c AS build
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
FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app
RUN addgroup -S wv && adduser -S wv -G wv
COPY --from=build --chown=wv:wv /app/backend/wealthview-app/target/*.jar app.jar
COPY --from=frontend-build --chown=wv:wv /app/frontend/dist /app/static
USER wv
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
    CMD wget -q -O- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
