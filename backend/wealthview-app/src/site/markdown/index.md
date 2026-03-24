# wealthview-app

The assembly module. Pulls all other modules together into a deployable Spring Boot fat JAR.
This is where `@SpringBootApplication` lives, application profiles are defined, and the
Docker / production deployment is rooted.

---

## Main Class

`com.wealthview.app.WealthViewApplication` — the standard Spring Boot entry point.
No application logic; just `SpringApplication.run(...)`.

---

## Application Profiles

| Profile | Activation | Purpose |
|---|---|---|
| `dev` | `-Dspring.profiles.active=dev` | Local development; minimal seed data; verbose SQL logging |
| `docker` | Set in `docker-compose.yml` | Docker Compose deployment; full demo data seed |
| `prod` | Explicit activation | Production; no seed data; JSON logging; stricter settings |
| `it` | Maven Failsafe | Integration test; Testcontainers PostgreSQL; no seed data |

Configuration files: `application.yml`, `application-dev.yml`, `application-docker.yml`,
`application-prod.yml`, `application-it.yml`.

---

## Application Initializers

### SuperAdminInitializer

Runs on every profile at startup. Checks whether the super-admin user (email configured via
`APP_SUPER_ADMIN_EMAIL` environment variable) exists. If not, creates the super-admin tenant
and user. This ensures a usable entry point on first deployment without any manual setup.

### SampleDataInitializer (`docker` profile)

Creates a complete demo tenant with:
* One admin user (`demo@wealthview.local` / `demo123`)
* Three financial accounts (brokerage, Roth IRA, 401k) with sample transactions and holdings
* Two properties with income, expense, and valuation history
* One retirement projection scenario with a spending profile and income sources

### DevDataInitializer (`dev` profile)

Creates a minimal single-user dev tenant for rapid iteration.

---

## Scheduling

`SchedulingConfig` enables Spring's `@Scheduled` task execution with a configurable thread pool.

`PriceSyncScheduler` drives the Finnhub price sync:
* **Daily cron** — runs after market close; updates all held symbols
* **On-demand** — can be triggered via an internal application event (`NewHoldingCreatedEvent`)
  for 2-year historical backfill when a new symbol is first held

---

## Integration Tests (Maven Failsafe)

The `it` profile activates Maven Failsafe which runs all `*IT.java` classes as
post-package integration tests.

```bash
cd backend
mvn verify -pl wealthview-app
```

IT classes use `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `HttpClient5` to send real
HTTP requests to the fully started application backed by a Testcontainers PostgreSQL container.
These tests cover full request-to-database round trips including auth, tenant isolation, and
Flyway migration correctness.

---

## JaCoCo Aggregate Coverage

`wealthview-app` generates the cross-module aggregate coverage report. After running
`mvn verify`, the report is at:

```
target/site/jacoco-aggregate/index.html
```

This report spans all source modules and gives the truest picture of end-to-end test coverage,
including code paths exercised only through integration tests.

---

## Docker Deployment

The `Dockerfile` at the repository root performs a multi-stage build:

1. **Stage 1 (frontend):** `node:20-alpine` — `npm ci && npm run build` → `dist/`
2. **Stage 2 (backend):** `maven:3.9-eclipse-temurin-21` — `mvn clean package -DskipTests` → fat JAR
3. **Stage 3 (runtime):** `eclipse-temurin:21-jre-alpine` — copies JAR + `dist/` → single image

The fat JAR serves the React `dist/` as static resources from `/` and the API under `/api/v1/`.

```bash
docker compose up --build -d     # http://localhost:80
docker compose down
docker compose logs -f app
```

---

## Static Resource Serving

Spring Boot serves the compiled React SPA from `classpath:static/`. This means the final
container image has no Nginx — the Spring Boot app itself serves both the API and the frontend
from port 8080, with Nginx or a load balancer optionally placed in front for TLS termination.
