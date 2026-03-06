# WealthView

A self-hosted, multi-tenant personal finance application for tracking investment portfolios, rental properties, and retirement projections.

## Overview

WealthView consolidates financial visibility across brokerage accounts, rental properties, and retirement plans into a single dashboard. It supports CSV import from brokerages (Fidelity, generic), automated stock price feeds via Finnhub, and multi-tenant data isolation so one deployment can serve multiple independent users.

### Key Features

- **Investment Portfolio Tracking** -- Accounts, holdings, transactions with automatic cost basis and quantity computation
- **Rental Property Management** -- Property records with income/expense tracking and monthly cash flow reports
- **Dashboard** -- Net worth summary combining investments, cash, and property equity with asset allocation breakdown
- **CSV Import** -- Fidelity and generic CSV transaction import with deduplication via content hashing
- **Live Price Feeds** -- Finnhub API integration with historical backfill and scheduled daily sync
- **Multi-Tenant** -- JWT-based auth with tenant isolation; invite code registration system
- **Self-Hosted** -- Single Docker Compose command to deploy; no third-party SaaS dependencies

## Tech Stack

| Layer     | Technology                                       |
|-----------|--------------------------------------------------|
| Frontend  | React 18, TypeScript, Vite, React Router, Axios |
| Backend   | Java 21, Spring Boot 3.3, Spring Security, JPA  |
| Database  | PostgreSQL 16 with Flyway migrations             |
| Build     | Maven multi-module (backend), npm (frontend)     |
| Testing   | JUnit 5, Mockito, Testcontainers, Vitest         |
| Deploy    | Docker Compose (multi-stage build)               |

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
| `wealthview-projection`  | Retirement modeling engine                               |
| `wealthview-app`         | Spring Boot main class, profile configs, JAR packaging   |

Module dependencies flow strictly downward: `api -> core -> persistence`. The `import` and `projection` modules depend on `core`. The `app` module assembles everything.

## Quick Start (Docker Compose)

### Prerequisites

- Docker with the Compose plugin (`docker compose`)
- Ports 80 (app) available on the host

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

| Variable               | Default                                              | Description                    |
|------------------------|------------------------------------------------------|--------------------------------|
| `DB_PASSWORD`          | `wv_dev_pass`                                        | PostgreSQL password            |
| `JWT_SECRET`           | `production-secret-key-must-be-at-least-32-characters` | JWT signing key              |
| `SUPER_ADMIN_PASSWORD` | `admin123`                                           | Initial admin password         |
| `FINNHUB_API_KEY`      | *(empty -- price sync disabled)*                     | Finnhub API key for live prices |

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
mvn clean install          # Build + run all tests
mvn -pl wealthview-app spring-boot:run   # Start with dev profile
```

The dev profile auto-creates the super-admin account and enables debug logging.

### Frontend

```bash
cd frontend
npm install
npm run dev                # Dev server at http://localhost:5173
```

### Running Tests

```bash
# Backend (uses Testcontainers -- Docker required)
cd backend
mvn test

# Single module
mvn -pl wealthview-core test

# Frontend
cd frontend
npm run test
```

## API Overview

All API endpoints are under `/api/v1/` and require JWT authentication (except `/api/v1/auth/**`).

| Endpoint                        | Method | Description                      |
|---------------------------------|--------|----------------------------------|
| `/api/v1/auth/login`            | POST   | Login, returns JWT tokens        |
| `/api/v1/auth/register`         | POST   | Register with invite code        |
| `/api/v1/auth/refresh`          | POST   | Refresh access token             |
| `/api/v1/accounts`              | GET/POST | List or create accounts        |
| `/api/v1/accounts/{id}`         | GET/PUT/DELETE | Account CRUD              |
| `/api/v1/accounts/{id}/transactions` | GET/POST | Transactions for account |
| `/api/v1/accounts/{id}/holdings` | GET   | Holdings for account             |
| `/api/v1/holdings`              | GET/POST | All holdings / create manual   |
| `/api/v1/properties`            | GET/POST | Rental properties              |
| `/api/v1/properties/{id}/income` | POST  | Add rental income                |
| `/api/v1/properties/{id}/expenses` | POST | Add property expense           |
| `/api/v1/properties/{id}/cashflow` | GET  | Monthly cash flow report        |
| `/api/v1/import/csv`            | POST   | Upload CSV for import            |
| `/api/v1/prices`                | GET/POST | Stock prices                   |
| `/api/v1/dashboard/summary`     | GET    | Net worth and allocation summary |
| `/api/v1/tenant/invite-codes`   | POST/GET | Manage invite codes (admin)   |
| `/api/v1/tenant/users`          | GET    | List tenant users (admin)        |

## Project Structure

```
wealthview/
├── backend/
│   ├── pom.xml                          (parent POM)
│   ├── wealthview-api/                  (controllers, security)
│   ├── wealthview-core/                 (services, business logic)
│   ├── wealthview-persistence/          (entities, repos, migrations)
│   ├── wealthview-import/               (CSV parsers, Finnhub client)
│   ├── wealthview-projection/           (retirement engine)
│   └── wealthview-app/                  (Spring Boot main, configs)
├── frontend/
│   ├── src/
│   │   ├── components/                  (reusable UI components)
│   │   ├── pages/                       (route-level views)
│   │   ├── hooks/                       (custom React hooks)
│   │   ├── api/                         (Axios client, API calls)
│   │   └── context/                     (auth, tenant providers)
│   └── package.json
├── docker-compose.yml
├── Dockerfile
├── CLAUDE.md                            (AI coding conventions)
└── PROJECT.md                           (full architecture spec)
```

## License

Private -- all rights reserved.
