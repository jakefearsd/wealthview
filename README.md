# WealthView

A self-hosted, multi-tenant personal finance application for tracking investment portfolios, rental properties, and retirement projections.

## Overview

WealthView consolidates financial visibility across brokerage accounts, rental properties, and retirement plans into a single dashboard. It supports multi-format import from brokerages (Fidelity, Vanguard, Schwab CSV and OFX/QFX), automated stock price feeds via Finnhub, retirement projection modeling, and multi-tenant data isolation so one deployment can serve multiple independent users.

### Key Features

- **Investment Portfolio Tracking** -- Accounts, holdings, transactions with automatic cost basis and quantity computation. Theoretical portfolio history charts with historical price data.
- **Rental Property Management** -- Property records with income/expense tracking and monthly cash flow reports.
- **Dashboard** -- Net worth summary combining investments, cash, and property equity with asset allocation pie chart.
- **Multi-Format Import** -- Fidelity, Vanguard, and Schwab CSV parsers plus OFX/QFX import. Content-hash deduplication prevents duplicate transactions across imports.
- **Live Price Feeds** -- Finnhub API integration with historical backfill and scheduled daily sync. Seed data covers major tickers (AAPL, AMZN, GOOG, MSFT, NVDA, VOO, VTI, and more).
- **Retirement Projections** -- Deterministic year-by-year projection engine with configurable contributions, growth rates, inflation-adjusted withdrawals, and multiple visualization modes (balance chart, annual flows, data table).
- **Multi-Tenant** -- JWT-based auth with tenant isolation; invite code registration system with role-based access (admin/user).
- **Self-Hosted** -- Single Docker Compose command to deploy; no third-party SaaS dependencies.

## Tech Stack

| Layer     | Technology                                                |
|-----------|-----------------------------------------------------------|
| Frontend  | React 18, TypeScript, Vite, React Router, Recharts, Axios |
| Backend   | Java 21, Spring Boot 3.3, Spring Security, JPA            |
| Database  | PostgreSQL 16 with Flyway migrations                      |
| Build     | Maven multi-module (backend), npm (frontend)              |
| Testing   | JUnit 5, Mockito, Testcontainers, Vitest, React Testing Library |
| Deploy    | Docker Compose (multi-stage build)                        |

## Architecture

```
React SPA  <-->  Spring Boot REST API  <-->  PostgreSQL
                      |
                  Finnhub API
                  (stock prices)
```

The backend is organized as a Maven multi-module project:

| Module                   | Responsibility                                           |
|--------------------------|----------------------------------------------------------|
| `wealthview-api`         | REST controllers, security config, exception handlers    |
| `wealthview-core`        | Services, business logic, domain DTOs                    |
| `wealthview-persistence` | JPA entities, repositories, Flyway migrations            |
| `wealthview-import`      | CSV/OFX parsers, Finnhub price feed client               |
| `wealthview-projection`  | Deterministic retirement modeling engine                  |
| `wealthview-app`         | Spring Boot main class, profile configs, JAR packaging   |

Module dependencies flow strictly downward: `api -> core -> persistence`. The `import` and `projection` modules depend on `core`. The `app` module assembles everything.

## Quick Start (Docker Compose)

### Prerequisites

- Docker with the Compose plugin (`docker compose`)
- Port 80 available on the host

### Launch

```bash
docker compose up --build -d
```

This builds a multi-stage Docker image (frontend + backend) and starts PostgreSQL and the application. Flyway runs migrations automatically on startup.

### Access

- **URL:** http://localhost
- **Super admin:** `admin@wealthview.local` / `admin123` (system administration)
- **Demo user:** `demo@wealthview.local` / `demo123` (pre-loaded with sample data)

Both accounts are created automatically on first startup. The demo user comes with sample investment accounts (Fidelity brokerage and 401k with holdings in AAPL, NVDA, GOOG, VOO, FXAIX, SCHD, VXUS), a bank account, and two rental properties with income/expense history.

### Environment Variables

| Variable               | Default                                                | Description                     |
|------------------------|--------------------------------------------------------|---------------------------------|
| `DB_PASSWORD`          | `wv_dev_pass`                                          | PostgreSQL password             |
| `JWT_SECRET`           | `production-secret-key-must-be-at-least-32-characters` | JWT signing key                 |
| `SUPER_ADMIN_PASSWORD` | `admin123`                                             | Initial admin password          |
| `FINNHUB_API_KEY`      | *(empty -- price sync disabled)*                       | Finnhub API key for live prices |

For production, set `JWT_SECRET` and `DB_PASSWORD` to strong random values:

```bash
JWT_SECRET=$(openssl rand -base64 48) DB_PASSWORD=$(openssl rand -base64 24) docker compose up --build -d
```

## Development Setup

### Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 20+
- PostgreSQL 16 (local or via Docker)

### Database

```bash
docker compose up -d db
```

### Backend

```bash
cd backend
mvn clean install                         # Build + run all tests
mvn -pl wealthview-app spring-boot:run    # Start with dev profile
```

The dev profile auto-creates the super-admin account and enables debug logging.

### Frontend

```bash
cd frontend
npm install
npm run dev                               # Dev server at http://localhost:5173
```

The Vite dev server proxies `/api` requests to the backend on port 8080.

### Running Tests

```bash
# Backend -- all modules (uses Testcontainers, requires Docker)
cd backend
mvn test

# Backend -- single module
mvn -pl wealthview-core test

# Backend -- single test class
mvn test -Dtest=AccountServiceTest

# Frontend -- all tests
cd frontend
npm run test

# Frontend -- specific test file
npx vitest run src/utils/projectionCalcs.test.ts

# TypeScript type check
npx tsc --noEmit
```

## Frontend Pages

| Route                  | Page                    | Description                                                    |
|------------------------|-------------------------|----------------------------------------------------------------|
| `/`                    | Dashboard               | Net worth, allocation pie chart, account balances              |
| `/accounts`            | Accounts List           | All investment accounts with balances                          |
| `/accounts/:id`        | Account Detail          | Holdings, transactions, theoretical portfolio history chart    |
| `/properties`          | Properties List         | Rental properties overview                                     |
| `/properties/:id`      | Property Detail         | Income/expenses, monthly cash flow chart                       |
| `/projections`         | Projections List        | Scenario card grid with key metrics and links                  |
| `/projections/:id`     | Projection Detail       | Config summary, run projection, tabbed results visualization   |
| `/import`              | Import                  | CSV/OFX file upload with format selection                      |
| `/prices`              | Prices                  | Stock price lookup and history                                 |
| `/settings`            | Settings                | Invite codes, user management (admin)                          |
| `/login`               | Login                   | Email/password authentication                                  |
| `/register`            | Register                | New user registration with invite code                         |

### Projection Visualization

The projection detail page provides three visualization modes via a tabbed interface:

- **Balance Over Time** -- Area chart showing projected portfolio balance with a cumulative contributions overlay and reference lines for retirement year and depletion year.
- **Annual Flows** -- Bar chart breaking down yearly contributions (green), investment growth (blue), and withdrawals (red).
- **Data Table** -- Year-by-year tabular data with the retirement transition row highlighted.

Result summary cards show Final Balance, Years in Retirement, Peak Balance (with year), and Depletion Year. A milestone strip provides an at-a-glance overview of key projection milestones.

## API Reference

All endpoints are under `/api/v1/` and require JWT authentication (except `/api/v1/auth/**`).

### Authentication

| Endpoint                 | Method | Description              |
|--------------------------|--------|--------------------------|
| `/api/v1/auth/login`     | POST   | Login, returns JWT tokens |
| `/api/v1/auth/register`  | POST   | Register with invite code |
| `/api/v1/auth/refresh`   | POST   | Refresh access token      |

### Accounts & Holdings

| Endpoint                              | Method         | Description                   |
|---------------------------------------|----------------|-------------------------------|
| `/api/v1/accounts`                    | GET, POST      | List or create accounts       |
| `/api/v1/accounts/{id}`               | GET, PUT, DELETE | Account CRUD               |
| `/api/v1/accounts/{id}/transactions`  | GET, POST      | Transactions for account      |
| `/api/v1/accounts/{id}/holdings`      | GET            | Holdings for account          |
| `/api/v1/accounts/{id}/theoretical-history` | GET      | Theoretical portfolio history |
| `/api/v1/holdings`                    | GET, POST      | All holdings / create manual  |
| `/api/v1/holdings/{id}`               | PUT            | Update holding                |
| `/api/v1/transactions/{id}`           | PUT, DELETE    | Update or delete transaction  |

### Properties

| Endpoint                              | Method | Description              |
|---------------------------------------|--------|--------------------------|
| `/api/v1/properties`                  | GET, POST | List or create properties |
| `/api/v1/properties/{id}`             | GET, PUT, DELETE | Property CRUD     |
| `/api/v1/properties/{id}/income`      | POST   | Add rental income         |
| `/api/v1/properties/{id}/expenses`    | POST   | Add property expense      |
| `/api/v1/properties/{id}/cashflow`    | GET    | Monthly cash flow report  |

### Import & Prices

| Endpoint                       | Method | Description                     |
|--------------------------------|--------|---------------------------------|
| `/api/v1/import/csv`           | POST   | Upload CSV for import           |
| `/api/v1/import/ofx`           | POST   | Upload OFX/QFX for import       |
| `/api/v1/import/jobs`          | GET    | List import job history         |
| `/api/v1/prices`               | POST   | Add stock price                 |
| `/api/v1/prices/{symbol}/latest` | GET  | Latest price for symbol         |

### Projections

| Endpoint                        | Method | Description                  |
|---------------------------------|--------|------------------------------|
| `/api/v1/projections`           | GET, POST | List or create scenarios  |
| `/api/v1/projections/{id}`      | GET, DELETE | Get or delete scenario  |
| `/api/v1/projections/{id}/run`  | GET    | Run projection, get results  |

### Dashboard & Tenant Management

| Endpoint                          | Method | Description                   |
|-----------------------------------|--------|-------------------------------|
| `/api/v1/dashboard/summary`       | GET    | Net worth and allocation      |
| `/api/v1/tenant/invite-codes`     | GET, POST | Manage invite codes (admin) |
| `/api/v1/tenant/users`            | GET    | List tenant users (admin)     |
| `/api/v1/tenant/users/{id}/role`  | PUT    | Update user role (admin)      |
| `/api/v1/tenant/users/{id}`       | DELETE | Remove user (admin)           |
| `/api/v1/admin/tenants`           | GET, POST | Super-admin tenant management |

## Project Structure

```
wealthview/
├── backend/
│   ├── pom.xml                            (parent POM)
│   ├── wealthview-api/                    (controllers, security)
│   ├── wealthview-core/                   (services, business logic)
│   ├── wealthview-persistence/            (entities, repos, migrations)
│   ├── wealthview-import/                 (CSV/OFX parsers, Finnhub client)
│   ├── wealthview-projection/             (retirement projection engine)
│   └── wealthview-app/                    (Spring Boot main, configs)
├── frontend/
│   ├── src/
│   │   ├── api/                           (Axios client, API call functions)
│   │   ├── components/                    (SummaryCard, ProjectionChart, MilestoneStrip, etc.)
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

## Database Migrations

Flyway migrations are in `backend/wealthview-persistence/src/main/resources/db/migration/`:

| Migration | Description                                     |
|-----------|-------------------------------------------------|
| V001      | Baseline tenants and users tables               |
| V002      | Invite codes table                              |
| V003      | Accounts table                                  |
| V004      | Transactions table                              |
| V005      | Holdings table with manual override flag        |
| V006      | Prices table                                    |
| V007      | Properties tables (properties, income, expenses)|
| V008      | Import jobs table                               |
| V009      | Projection tables (scenarios, accounts)         |
| V010      | Transaction hash column for deduplication       |
| V011      | Opening balance transaction type                |
| R__seed   | Repeatable seed data for stock prices           |

## License

Private -- all rights reserved.
