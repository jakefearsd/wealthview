[← Back to README](../README.md)

# Development Guide

Instructions for setting up a local development environment, building, and running tests.

## Prerequisites

- Java 25 (the backend targets `<java.version>25</java.version>`; `backend/.sdkmanrc` pins `25.0.3-tem`)
- Maven 3.9+
- Node.js 20.19+ (CI runs Node 22; the production Docker image builds on Node 24)
- Docker & Docker Compose (for PostgreSQL and integration tests via Testcontainers)
- `gitleaks` — required by the pre-commit hook (see [Pre-Commit Secret Scanning](#pre-commit-secret-scanning))

The `mobile/` workspace still builds against JDK 17 (`mobile/.sdkmanrc`). If you use SDKMAN with
`sdkman_auto_env=true`, `cd backend` and `cd mobile` switch JDKs for you.

## Environment File

Both Docker Compose and the local `dev` profile read credentials from a gitignored `.env` at the
repo root. Copy the template and fill in your own values before anything else:

```bash
cp .env.example .env
```

`docker compose up -d db` fails fast with `DB_PASSWORD must be set in .env` if it is missing.

## Database Setup

```bash
docker compose up -d db                              # Start PostgreSQL (exposed on localhost:5433)
docker compose exec db psql -U wv_app wealthview     # Direct psql access
```

The Docker Compose DB is published on **`localhost:5433`** (avoiding conflicts with any native
PostgreSQL on 5432), which is exactly what `application.yml` points at
(`jdbc:postgresql://localhost:5433/wealthview`). The backend therefore connects automatically
whether it runs via Docker Compose or locally (IDE / `mvn spring-boot:run`).

## Backend

```bash
cd backend
mvn clean install                                                       # Build + run all tests
mvn -pl wealthview-app spring-boot:run -Dspring-boot.run.profiles=dev   # Start on port 8080
```

**The `dev` profile is not optional for a local run.** The default profile declares `${DB_PASSWORD}`
and `${JWT_SECRET}` with no fallback (fail-loud, by design); only `application-dev.yml` supplies the
`LOCAL_DEV_*` sentinels that let the app boot without exported environment variables.

The dev profile also enables SQL logging and `DEBUG` logging for `com.wealthview`, and activates the
seed-data initializers described below.

### Profiles and Seed Data

| Profile | Used by | Data initializers that run |
|---|---|---|
| `dev` | Local `spring-boot:run` / IDE | `SuperAdminInitializer`, `SampleDataInitializer`, `DevDataInitializer` |
| `docker` | `docker compose up` (dev stack) | `SuperAdminInitializer`, `SampleDataInitializer` |
| `prod` | `docker-compose.prod.yml` | `SuperAdminInitializer` only |
| `it` | Failsafe integration tests | none (tests seed their own data) |

`DevDataInitializer` is `@Profile("dev")` only; `SampleDataInitializer` is `@Profile({"dev","docker"})`.

Dev/demo logins:

- `admin@wealthview.local` / `admin123` — super admin, every profile
- `demo@wealthview.local` / `demo123` — demo tenant, `dev` + `docker`
- `demo-admin@wealthview.local` / `demo123` — `dev` only

## Frontend

The repo is an **npm workspaces monorepo** (`shared/`, `frontend/`, `mobile/`). Run `npm install`
**once at the repo root** — that is what creates the `node_modules/@wealthview/shared` symlink the
frontend and mobile apps import from. Running `npm install` inside `frontend/` alone will not wire
the workspace dependency.

```bash
npm install                               # at the REPO ROOT — hoists deps, symlinks @wealthview/shared

cd frontend
npm run dev                               # Dev server at http://localhost:5173
```

The Vite dev server proxies `/api` requests to the backend on port 8080.

Cross-workspace scripts, all run from the repo root:

```bash
npm run test:shared                       # Vitest in shared/
npm run test:frontend                     # Vitest in frontend/
npm run test:mobile                       # Jest in mobile/
npm run test:all                          # every workspace that defines a test script
npm run typecheck:all                     # tsc --noEmit across all workspaces
npm run build:frontend                    # Vite production build
```

Mobile native builds (Android Gradle, iOS Xcode) run locally only — see `mobile/README.md`.

## Dev Database Backup & Restore

```bash
./wv backup                                          # Dump to backups/wealthview_<timestamp>.dump
./wv backups                                         # List available backups with size + age
./wv restore backups/<file>.dump                     # Confirm + restore + restart + health-check
```

`./dev-backup.sh` and `./dev-restore.sh` still work but are deprecated shims that `exec` into
`./wv backup` / `./wv restore` (with no arguments `./dev-restore.sh` calls `./wv backups`). New
automation should call `./wv` directly. Backups are stored in `backups/` (gitignored). Use these to
save test data before destructive operations or to share a known database state.

---

## Running Tests

### Backend

```bash
cd backend

# Everything Surefire runs: unit tests plus the @DataJpaTest Testcontainers slices
# (so Docker is required). Does NOT include the wealthview-app *IT.java suite.
mvn test

# Unit tests only (no Docker required)
mvn test -pl wealthview-core,wealthview-api,wealthview-import,wealthview-projection

# API-level integration tests (wealthview-app, Failsafe + Testcontainers, `it` profile)
mvn verify -pl wealthview-app

# Persistence integration tests (repository + Flyway tests against real PostgreSQL)
mvn test -pl wealthview-persistence

# Full verify including the quality gates, but skipping the Failsafe ITs
mvn verify -DskipITs

# Single test class
mvn test -Dtest=AccountServiceTest

# Single test method
mvn test -Dtest="AccountServiceTest#methodName"
```

**Test infrastructure:**

- Unit tests use JUnit 5 + Mockito with AssertJ assertions
- Integration tests use Testcontainers with PostgreSQL 16 (never H2)
- Failsafe in `wealthview-app` matches `**/*IT.java` and forces `spring.profiles.active=it`
- The `wealthview-app` module holds **50 `*IT.java` classes** covering the HTTP API, auth/transport
  boundaries, tenant isolation, fuzzing, stock splits and metrics
- The `wealthview-persistence` module covers Flyway migrations, repository queries and entity mapping
  with `@DataJpaTest` + Testcontainers; its shared container lives in `AbstractIntegrationTest`

### Frontend

```bash
cd frontend

npm run test                              # All tests (Vitest)
npm run test:watch                        # Watch mode
npm run test:coverage                     # Vitest + v8 coverage, enforces the ratchet thresholds
npx vitest run src/utils/projectionCalcs.test.ts  # Specific test file
npm run lint                              # ESLint check
npm run typecheck                         # tsc --noEmit
```

Frontend coverage is measured and ratcheted in `frontend/vite.config.ts`: statements 83, branches 75,
functions 74, lines 86. `coverage.include` is set to `src/**/*.{ts,tsx}` so an entirely untested file
counts as zero rather than disappearing from the denominator. Raise a floor when you raise coverage;
never lower one.

---

## Code Quality

Five tools are configured in the parent POM (`backend/pom.xml`) and **all five fail the build** on
`mvn verify`. They are gates, not advisories.

| Tool | Goal / phase | Config file | Report location |
|------|-------------|-------------|-----------------|
| **PMD** | `pmd:check` @ `verify` | `backend/pmd-ruleset.xml` | `target/pmd.xml`, `target/site/pmd.html` |
| **CPD** | `pmd:cpd-check` @ `verify` | `backend/pmd-ruleset.xml` (plugin defaults for CPD) | `target/cpd.xml`, `target/site/cpd.html` |
| **SpotBugs** | `spotbugs:check` @ `verify` | `backend/spotbugs-exclude.xml` | `target/spotbugsXml.xml`, `target/site/spotbugs.html` |
| **Checkstyle** | `checkstyle:check` @ `verify` | `backend/wealthview_checks.xml` | `target/checkstyle-result.xml` |
| **JaCoCo** | `jacoco:check` @ `verify` | thresholds in each module POM | `target/site/jacoco/index.html` |

```bash
cd backend
mvn verify -DskipITs          # runs all five gates without needing the Failsafe/Docker suite
mvn verify                    # gates + Testcontainers integration tests

xdg-open wealthview-core/target/site/jacoco/index.html   # Linux
open wealthview-core/target/site/jacoco/index.html       # macOS
```

### Coverage Thresholds (enforced by `jacoco:check`)

| Module | Line minimum | Branch minimum |
|--------|--------------|----------------|
| `wealthview-core` | 0.90 | 0.83 |
| `wealthview-projection` | 0.90 | 0.84 |
| `wealthview-api` | 0.80 | 0.85 |
| `wealthview-import` | 0.80 | 0.71 |

`wealthview-persistence` and `wealthview-app` set `jacoco.check.skip=true` (inherited from the parent
default) — they are covered by integration tests rather than a line-coverage gate. Branch floors lock
in levels already reached: raise one when you raise coverage, never lower one.

### Mutation Testing (PIT — advisory)

PIT is **not** a build gate. Run it by hand to find tests that pass through coverage without pinning
behaviour:

```bash
cd backend
mvn -q test-compile org.pitest:pitest-maven:mutationCoverage -pl wealthview-core,wealthview-projection
```

Reports land in `<module>/target/pit-reports/`.

---

## Pre-Commit Secret Scanning

`.githooks/pre-commit` runs `gitleaks protect --staged` against `.gitleaks.toml` before every commit
and blocks the commit if a staged value matches a secret pattern. Enable the hook once per clone:

```bash
./scripts/install-hooks.sh          # or: git config core.hooksPath .githooks
```

Run the same scan manually with:

```bash
gitleaks protect --staged --redact --config .gitleaks.toml
```

Never commit a real or real-looking secret. Reference `${VAR}` in YAML, add a `CHANGE_ME` placeholder
to `.env.example`, and keep dev/IT fallbacks as obvious `LOCAL_DEV_*` / `INTEGRATION_TEST_*` sentinels.
If a sentinel trips the scanner, allowlist it in `.gitleaks.toml` — do not weaken the sentinel.

---

## Continuous Integration

Six workflows live in `.github/workflows/`: `backend-verify.yml`, `web.yml`, `shared.yml`,
`mobile.yml`, `scripts.yml`, `secret-scan.yml`. All run on GitHub-hosted runners and are triggered
**only** by pushing a version tag (`push: tags: ['v*']`), plus a manual `workflow_dispatch` escape
hatch — they do **not** run on every push or pull request.

`backend-verify.yml` is a three-job pipeline, each job gated on the previous one:

1. **verify** — `mvn clean verify -DskipITs` (unit tests, `@DataJpaTest` slices, and the five
   quality gates)
2. **integration-tests** — the full `wealthview-app` Failsafe/HTTP Testcontainers suite
3. **docker-image** — builds the release image only after both test jobs pass

There is no auto-deploy. Deployment happens on the server via `./wv update`.

---

## Related Docs

- [Architecture](reference/architecture.md) — Module structure and dependency rules
- [Configuration](reference/configuration.md) — Environment variables and Spring profiles
</content>
</invoke>
