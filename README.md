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

## Documentation

### For Users

| Guide | Description |
|-------|-------------|
| [Getting Started](docs/user-guide/getting-started.md) | First login, navigation, key concepts |
| [Investment Accounts](docs/user-guide/investment-accounts.md) | Accounts, transactions, holdings, cost basis |
| [Data Import](docs/user-guide/data-import.md) | CSV and OFX/QFX import with deduplication |
| [Prices & Valuation](docs/user-guide/prices-and-valuation.md) | Price feeds, manual entry, portfolio valuation |
| [Portfolio Analysis](docs/user-guide/portfolio-analysis.md) | Dashboard, charts, net worth breakdown |
| [Rental Properties](docs/user-guide/rental-properties.md) | Properties, mortgages, income/expenses, analytics |
| [Retirement Projections](docs/user-guide/retirement-projections.md) | Projection engine, strategies, scenario comparison |
| [Spending & Income](docs/user-guide/spending-and-income.md) | Spending profiles, income sources, tax treatments |
| [Settings & Export](docs/user-guide/settings-and-export.md) | Notifications, invite codes, data export |

### Deployment & Operations

| Guide | Description |
|-------|-------------|
| [Quick Start Deployment](docs/deployment/quickstart.md) | Get running in 5 minutes with Docker |
| [Production Setup](docs/deployment/production-setup.md) | Full VPS deployment with nginx and TLS |
| [TLS & Nginx](docs/deployment/tls-and-nginx.md) | Let's Encrypt certificates, nginx configuration |
| [Security Hardening](docs/deployment/security-hardening.md) | Firewall, SSH, secrets management |
| [Upgrading](docs/deployment/upgrading.md) | Upgrades, rollback, Flyway migrations |

### System Administration

| Guide | Description |
|-------|-------------|
| [Tenant & User Management](docs/administration/tenant-and-user-management.md) | Tenants, roles, invite codes, audit log |
| [Backups](docs/administration/backups.md) | Automated backups, restore procedures |
| [Monitoring & Logging](docs/administration/monitoring-and-logging.md) | Health checks, structured logs, alerting |
| [Maintenance](docs/administration/maintenance.md) | Database, disk, scheduled jobs, capacity |
| [Troubleshooting](docs/administration/troubleshooting.md) | Diagnostics, common problems, fixes |

### Reference

| Document | Description |
|----------|-------------|
| [Architecture](docs/reference/architecture.md) | Module structure, dependency rules, project tree |
| [API Reference](docs/reference/api-reference.md) | Full endpoint documentation with examples |
| [Data Model](docs/reference/data-model.md) | Entity definitions, ER diagram, migrations |
| [Configuration](docs/reference/configuration.md) | Environment variables, Spring profiles |
| [Frontend Routes](docs/reference/frontend-routes.md) | Route table with page descriptions |

### For Developers

| Document | Description |
|----------|-------------|
| [Development Guide](docs/development.md) | Local setup, build commands, testing |
| [Feature Walkthrough](docs/feature_walkthrough.md) | Step-by-step manual test script |
| [PROJECT.md](PROJECT.md) | Full architectural spec and feature roadmap |

## Tech Stack

| Layer     | Technology                                                |
|-----------|-----------------------------------------------------------|
| Frontend  | React 18, TypeScript, Vite, React Router, Recharts, Axios |
| Backend   | Java 21, Spring Boot 3.3, Spring Security, JPA/Hibernate  |
| Database  | PostgreSQL 16 with Flyway migrations                      |
| Build     | Maven multi-module (backend), npm (frontend)              |
| Testing   | JUnit 5, Mockito, AssertJ, Testcontainers, Vitest         |
| Deploy    | Docker Compose (multi-stage build)                        |

## License

Private -- all rights reserved.
