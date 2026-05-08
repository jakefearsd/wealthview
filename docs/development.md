[← Back to README](../README.md)

# Development Guide

Instructions for setting up a local development environment, building, and running tests.

## Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 20+
- Docker & Docker Compose (for PostgreSQL and integration tests via Testcontainers)

## Database Setup

```bash
docker compose up -d db                              # Start PostgreSQL (exposed on localhost:5432)
docker compose exec db psql -U wv_app wealthview     # Direct psql access
```

The Docker Compose DB is exposed on `localhost:5433` (avoiding conflicts with any native PostgreSQL on 5432), so the backend connects to it automatically whether run via Docker Compose or locally (IDE / `mvn spring-boot:run`).

## Backend

```bash
cd backend
mvn clean install                         # Build + run all tests
mvn -pl wealthview-app spring-boot:run    # Start with dev profile (port 8080)
```

The dev profile auto-creates the super-admin account and enables debug logging.

## Frontend

```bash
cd frontend
npm install
npm run dev                               # Dev server at http://localhost:5173
```

The Vite dev server proxies `/api` requests to the backend on port 8080.

## Dev Database Backup & Restore

```bash
./dev-backup.sh                                      # Dump to backups/wealthview_<timestamp>.dump
./dev-restore.sh backups/<file>.dump                  # Restore from a dump file
./dev-restore.sh                                      # List available backups
```

Backups are stored in `backups/` (gitignored). Use these to save test data before destructive operations or to share a known database state.

---

## Running Tests

### Backend

```bash
cd backend

# All tests (unit + integration; integration tests require Docker for Testcontainers)
mvn test

# Unit tests only (no Docker required)
mvn test -pl wealthview-core,wealthview-api,wealthview-import,wealthview-projection

# Integration tests only (wealthview-app module, uses Failsafe + Testcontainers)
mvn verify -pl wealthview-app

# Persistence integration tests (repository + Flyway tests against real PostgreSQL)
mvn test -pl wealthview-persistence

# Skip integration tests during verify
mvn verify -DskipITs

# Single test class
mvn test -Dtest=AccountServiceTest

# Single test method
mvn test -Dtest="AccountServiceTest#methodName"
```

**Test infrastructure:**
- Unit tests use JUnit 5 + Mockito with AssertJ assertions
- Integration tests use Testcontainers with PostgreSQL 16 (never H2)
- The `wealthview-app` module has 66 API-level integration tests across 11 test classes
- The `wealthview-persistence` module has 13 integration tests covering Flyway migrations and repository CRUD

### Frontend

```bash
cd frontend

npm run test                              # All tests (Vitest)
npx vitest run src/utils/projectionCalcs.test.ts  # Specific test file
npm run lint                              # ESLint check
npx tsc --noEmit                          # TypeScript type check
```

---

## Code Quality

The backend build includes static analysis and coverage tools, configured in the parent POM (`backend/pom.xml`):

| Tool | Purpose | Report Location |
|------|---------|----------------|
| **Checkstyle** | Enforces coding style and formatting rules | `target/checkstyle-result.xml` |
| **SpotBugs** | Detects common bug patterns (null deref, resource leaks, etc.) | `target/spotbugsXml.xml` |
| **JaCoCo** | Measures test code coverage | `target/site/jacoco/index.html` |

JaCoCo runs automatically as part of `mvn verify`. Checkstyle and SpotBugs are available via `pluginManagement` but not enforced by default -- invoke them explicitly with `mvn checkstyle:check` or `mvn spotbugs:check`. To generate a human-readable JaCoCo coverage report:

```bash
cd backend
mvn verify
open wealthview-app/target/site/jacoco/index.html    # macOS
xdg-open wealthview-app/target/site/jacoco/index.html # Linux
```

### Coverage Targets

| Module | Target |
|--------|--------|
| `wealthview-core` | 90%+ line coverage |
| `wealthview-projection` | 90%+ line coverage |
| `wealthview-api` | 80%+ line coverage |
| `wealthview-import` | 80%+ line coverage |

---

## Related Docs

- [Architecture](reference/architecture.md) — Module structure and dependency rules
- [Configuration](reference/configuration.md) — Environment variables and Spring profiles
