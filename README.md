# WealthView

A self-hosted, multi-tenant personal finance application for tracking investment portfolios, rental properties, and retirement projections.

## Key Features

- **Investment Portfolio Tracking** -- Accounts, holdings, transactions with automatic cost basis computation, portfolio history charts, and money market fund support
- **Rental Property Management** -- Income/expense tracking, cash flow reports, loan amortization, Zillow valuation scraping, investment analytics, and depreciation modeling
- **Retirement Projections** -- Year-by-year projection engine with three withdrawal strategies, Roth conversion modeling, tax-aware income sources, and spending viability analysis
- **Multi-Format Import** -- Fidelity, Vanguard, and Schwab CSV parsers plus OFX/QFX import with content-hash deduplication
- **Live Price Feeds** -- Finnhub API integration with historical backfill and scheduled daily sync
- **Dashboard** -- Net worth summary combining investments, cash, and property equity with asset allocation breakdown
- **Multi-Tenant** -- JWT-based auth with tenant isolation, invite code registration, and role-based access (admin/member/viewer)
- **Administrative Tooling** -- Super-admin tenant management, audit log, data export (JSON + CSV), and notification preferences
- **Self-Hosted** -- Single Docker Compose command to deploy; no third-party SaaS dependencies

## Quick Start

**Prerequisites:** Docker with the Compose plugin (`docker compose`), port 80 available.

```bash
docker compose up --build -d              # Build and start
```

- **URL:** http://localhost
- **Super admin:** `admin@wealthview.local` / `admin123`
- **Demo user:** `demo@wealthview.local` / `demo123` (pre-loaded with sample data)

```bash
docker compose down                       # Stop (preserve data)
docker compose down -v                    # Stop and delete database
```

## Tech Stack

| Layer     | Technology                                                |
|-----------|-----------------------------------------------------------|
| Frontend  | React 18, TypeScript, Vite, React Router, Recharts, Axios |
| Backend   | Java 21, Spring Boot 3.3, Spring Security, JPA/Hibernate  |
| Database  | PostgreSQL 16 with Flyway migrations                      |
| Build     | Maven multi-module (backend), npm (frontend)              |
| Testing   | JUnit 5, Mockito, AssertJ, Testcontainers, Vitest         |
| Deploy    | Docker Compose (multi-stage build)                        |

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture](docs/architecture.md) | Component diagram, module structure, dependency rules, project tree |
| [Development Guide](docs/development.md) | Local setup, build commands, running tests, code quality tools |
| [Configuration Reference](docs/configuration.md) | Environment variables, JWT settings, Finnhub/Zillow config, Spring profiles |
| [Deployment Guide](docs/deployment.md) | Production security checklist, health checks, upgrading, resource requirements |
| [Administration Guide](docs/administration.md) | Tenant management, user roles, audit log, data export, scheduled jobs |
| [Backup Operations](docs/doing_backups.md) | Automated backup scheduling, restore procedures, troubleshooting |
| [Data Model Reference](docs/data_model.md) | All 23 entity definitions, ER diagram, Flyway migration inventory |
| [API Reference](docs/api_reference.md) | Full endpoint documentation with request/response examples |
| [Frontend Pages](docs/frontend.md) | Route table with page descriptions |
| [Feature Walkthrough](docs/feature_walkthrough.md) | Step-by-step guided tour of all features (doubles as manual test script) |
| [PROJECT.md](PROJECT.md) | Full architectural spec, data model goals, and feature roadmap |

## License

Private -- all rights reserved.
