[← Back to README](../../README.md)

# Architecture

WealthView is a self-hosted, multi-tenant personal finance application built as a monorepo with a Java/Spring Boot backend and a React frontend, backed by PostgreSQL.

## High-Level Component Diagram

```
React SPA  <-->  Spring Boot REST API  <-->  PostgreSQL
                      |
                  Finnhub API        Zillow
                  (stock prices)     (property valuations)
```

## Tech Stack

| Layer     | Technology                                                |
|-----------|-----------------------------------------------------------|
| Frontend  | React 18, TypeScript, Vite, React Router, Recharts, Axios |
| Backend   | Java 21, Spring Boot 3.3, Spring Security, JPA/Hibernate  |
| Database  | PostgreSQL 16 with Flyway migrations                      |
| Build     | Maven multi-module (backend), npm (frontend)              |
| Testing   | JUnit 5, Mockito, AssertJ, Testcontainers, Vitest, React Testing Library |
| Analysis  | Checkstyle, SpotBugs, JaCoCo                              |
| Deploy    | Docker Compose (multi-stage build)                        |

## Maven Multi-Module Structure

The backend is organized as a Maven multi-module project:

| Module                   | Responsibility                                           |
|--------------------------|----------------------------------------------------------|
| `wealthview-api`         | REST controllers, security config, exception handlers    |
| `wealthview-core`        | Services, business logic, domain DTOs                    |
| `wealthview-persistence` | JPA entities, repositories, Flyway migrations            |
| `wealthview-import`      | CSV/OFX parsers, Finnhub price feed client, Zillow scraper |
| `wealthview-projection`  | Deterministic retirement modeling engine                  |
| `wealthview-app`         | Spring Boot main class, profile configs, JAR packaging   |

## Module Dependency Rules

```
wealthview-app  -->  all modules (assembles the application)
wealthview-api  -->  wealthview-core
wealthview-core -->  wealthview-persistence
wealthview-import      -->  wealthview-core
wealthview-projection  -->  wealthview-core
wealthview-persistence -->  nothing (leaf module)
```

`wealthview-api` never depends directly on `wealthview-persistence`, `wealthview-import`, or `wealthview-projection`. Controllers call services; services call repositories.

## Layer Responsibilities

- **Controller (wealthview-api):** HTTP mapping, request validation (`@Valid`), response DTO assembly. No business logic. Controllers call services, never repositories.
- **Service (wealthview-core):** Business logic, orchestration, transaction boundaries (`@Transactional`). Services call repositories and other services.
- **Repository (wealthview-persistence):** Data access only. Custom queries via `@Query` or Spring Data method naming. No business logic.

## Project Directory Tree

```
wealthview/
├── backend/
│   ├── pom.xml                            (parent POM)
│   ├── wealthview-api/                    (controllers, security)
│   ├── wealthview-core/                   (services, business logic, DTOs)
│   │   └── projection/
│   │       ├── strategy/                  (WithdrawalStrategy sealed interface + 3 implementations)
│   │       ├── tax/                       (FederalTaxCalculator, FilingStatus)
│   │       └── dto/                       (ProjectionYearDto, CompareRequest/Response)
│   ├── wealthview-persistence/            (entities, repos, migrations)
│   ├── wealthview-import/                 (CSV/OFX parsers, Finnhub client)
│   ├── wealthview-projection/             (DeterministicProjectionEngine)
│   └── wealthview-app/                    (Spring Boot main, configs)
├── frontend/
│   ├── src/
│   │   ├── api/                           (Axios client, API call functions)
│   │   ├── components/                    (SummaryCard, ProjectionChart, MilestoneStrip, HelpText, InfoSection, etc.)
│   │   ├── context/                       (auth, tenant providers)
│   │   ├── hooks/                         (custom React hooks)
│   │   ├── pages/                         (route-level views)
│   │   ├── types/                         (TypeScript interfaces)
│   │   └── utils/                         (formatting, projection calculations, styles)
│   └── package.json
├── docker-compose.yml
├── Dockerfile
├── CLAUDE.md                              (AI coding conventions)
└── PROJECT.md                             (full architecture spec)
```

## Further Reading

- [PROJECT.md](../PROJECT.md) — Full architectural specification, data model goals, and feature roadmap
- [Development Guide](../development.md) — Local setup, build commands, and testing
- [Data Model Reference](data-model.md) — Entity definitions and migration inventory
