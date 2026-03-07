# WealthView — Personal Finance Tracking Platform

## Overview

WealthView is a self-hosted, multi-tenant personal finance application focused on investment portfolio monitoring, rental property income/expense tracking, and retirement projection modeling. It is designed for financially literate users who want full ownership of their data and the flexibility to share the platform with family or trusted peers.

---

## Goals

1. **Consolidate financial visibility** — Provide a single dashboard spanning brokerage accounts, rental properties, and retirement plans.
2. **Automate data ingestion** — Minimize manual entry through CSV and OFX file imports and automated price feeds.
3. **Model the future** — Offer configurable retirement projection tools (withdrawal strategies, Roth conversions, tax-aware modeling).
4. **Multi-tenant by design** — Each tenant has fully isolated data; a single deployment serves multiple independent users.
5. **Self-hosted and private** — Runs on a personal server or VPS with no dependency on third-party SaaS for core functionality.

---

## Architecture

### High-Level Component Diagram

```
┌─────────────┐       ┌──────────────────────────────────┐       ┌────────────┐
│  React SPA  │◄─────►│  Spring Boot REST API (Maven)    │◄─────►│ PostgreSQL │
│  (Vite)     │  HTTP  │  ├─ Spring Security + JWT auth   │  JPA  │            │
└─────────────┘       │  ├─ Tenant isolation filter       │       └────────────┘
                      │  ├─ Portfolio service             │
                      │  ├─ Property service              │
                      │  ├─ Retirement projection engine  │
                      │  ├─ Import service (CSV, OFX)     │
                      │  └─ @Scheduled price sync         │
                      └──────────────────────────────────┘
                                     │
                               ┌─────▼─────┐
                               │  Finnhub  │
                               │  (prices) │
                               └───────────┘
```

### Tech Stack

| Layer          | Technology                                              |
| -------------- | ------------------------------------------------------- |
| Frontend       | React 18+, Vite, React Router, Recharts/D3 for charts  |
| HTTP Client    | Axios                                                   |
| Backend        | Java 21+, Spring Boot 3.3+, Spring Web MVC              |
| Build          | Maven (multi-module)                                    |
| ORM / DB       | Spring Data JPA (Hibernate 6+), PostgreSQL 16+           |
| Auth           | Spring Security, JWT-based session tokens, bcrypt        |
| Scheduling     | Spring @Scheduled (cron expressions)                    |
| Price Feed     | Finnhub API (free tier, 60 req/min)                     |
| File Import    | Apache Commons CSV, OFX4J (OFX/QFX parsing)             |
| Testing        | JUnit 5, Mockito, Testcontainers (Postgres)             |
| Deployment     | Docker Compose (Spring Boot fat JAR + Postgres), Nginx   |

### Maven Module Structure

See [Repository Structure](#repository-structure) above for the full monorepo layout. The backend Maven modules are:

| Module                     | Responsibility                                                    |
| -------------------------- | ----------------------------------------------------------------- |
| `wealthview-api`           | REST controllers, Spring Security config, exception handlers      |
| `wealthview-core`          | Domain models, services, business logic                           |
| `wealthview-persistence`   | JPA entities, Spring Data repositories, Flyway migrations         |
| `wealthview-import`        | CSV parser, OFX parser, Finnhub price client                     |
| `wealthview-projection`    | Retirement modeling engine                                        |
| `wealthview-app`           | Spring Boot main class, `application.yml`, fat JAR packaging      |

---

## Key Design Decisions

### Repository Structure

Monorepo containing both backend and frontend:

```
wealthview/
├── backend/                   (Maven multi-module project)
│   ├── pom.xml                (parent POM, spring-boot-starter-parent)
│   ├── wealthview-api/
│   ├── wealthview-core/
│   ├── wealthview-persistence/
│   ├── wealthview-import/
│   ├── wealthview-projection/
│   └── wealthview-app/
├── frontend/                  (React + Vite)
│   ├── package.json
│   └── src/
├── docker-compose.yml
├── Dockerfile
└── PROJECT.md
```

### Primary Keys

All entity IDs are **UUID** (`uuid` type in PostgreSQL, `UUID` in JPA). This prevents information leakage in URLs (e.g., `/api/v1/accounts/{uuid}` reveals nothing about how many accounts exist) and simplifies future data merges or imports.

### Registration & Onboarding

Registration is **invite-code gated**. There is no open public registration.

1. An **admin** creates a tenant and generates one or more single-use invite codes.
2. A new user visits `/register`, enters their email, password, and invite code.
3. The system validates the code, creates the user within the associated tenant, and marks the code as consumed.
4. The first user in a tenant automatically receives the `admin` role.

A **super-admin** role (your own account, seeded at first startup) can create new tenants and generate invite codes for any tenant.

### User Roles

Each user has one of three roles, scoped to their tenant:

| Role       | Permissions                                                                                      |
| ---------- | ------------------------------------------------------------------------------------------------ |
| `admin`    | Full CRUD on all tenant data; invite/remove users; manage tenant settings                        |
| `member`   | Full CRUD on accounts, holdings, transactions, properties, projections; cannot manage users       |
| `viewer`   | Read-only access to all tenant data; cannot create, update, or delete anything                    |

Authorization is enforced at the Spring Security filter level using role-based access checks on each endpoint.

### Holdings Computation

Holdings are **auto-computed from transactions** with **manual override** support:

- When a transaction is created, updated, or deleted, a service recomputes the affected holding row(s) for that account + symbol by aggregating all buy/sell transactions. The computed fields are `quantity` and `cost_basis`.
- A holding can also be manually created or adjusted (e.g., for assets transferred in from another brokerage where full transaction history isn't available). Manually overridden holdings are flagged with `is_manual_override = true`.
- If new transactions are later imported for a manually overridden holding, the system warns the user rather than silently overwriting.

### Phase 1 Valuation (Before Price Feed)

The `prices` table is available from Phase 1, but populated via **manual entry** rather than an API feed. Users can enter a current price per symbol through the UI. The dashboard uses the most recent price (manual or, later, from Finnhub) to compute market value and net worth. If no price exists for a symbol, the dashboard falls back to cost basis.

---

## Data Model (Core Entities)

All `id` columns are **UUID**. All tables except `tenants` and `prices` include a `tenant_id` foreign key for row-level isolation.

### Tenancy & Auth

| Table          | Key Columns                                                                              |
| -------------- | ---------------------------------------------------------------------------------------- |
| `tenants`      | `id`, `name`, `created_at`                                                               |
| `users`        | `id`, `tenant_id`, `email`, `password_hash`, `role` (admin, member, viewer), `created_at` |
| `invite_codes` | `id`, `tenant_id`, `code` (unique), `created_by`, `consumed_by`, `consumed_at`, `expires_at` |

### Portfolio / Investments

| Table               | Key Columns                                                                     |
| ------------------- | ------------------------------------------------------------------------------- |
| `accounts`          | `id`, `tenant_id`, `name`, `type` (brokerage, ira, 401k, roth, bank), `institution` |
| `holdings`          | `id`, `account_id`, `symbol`, `quantity`, `cost_basis`, `is_manual_override`, `as_of_date` |
| `transactions`      | `id`, `account_id`, `date`, `type` (buy, sell, dividend, deposit, withdrawal), `symbol`, `amount`, `quantity` |
| `prices`            | `symbol`, `date`, `close_price`, `source` (manual, finnhub) — daily cache for historical charts |

### Rental Properties

| Table                    | Key Columns                                                                  |
| ------------------------ | ---------------------------------------------------------------------------- |
| `properties`             | `id`, `tenant_id`, `address`, `purchase_price`, `purchase_date`, `current_value`, `mortgage_balance` |
| `property_income`        | `id`, `property_id`, `date`, `amount`, `category` (rent, other)              |
| `property_expenses`      | `id`, `property_id`, `date`, `amount`, `category` (mortgage, tax, insurance, maintenance, capex, hoa, mgmt_fee) |

### Retirement Projections

| Table                   | Key Columns                                                                        |
| ----------------------- | ---------------------------------------------------------------------------------- |
| `projection_scenarios`  | `id`, `tenant_id`, `name`, `retirement_date`, `end_age`, `inflation_rate`, `params_json` |
| `projection_accounts`   | `id`, `scenario_id`, `linked_account_id`, `initial_balance`, `annual_contribution`, `expected_return` |

`params_json` stores flexible scenario parameters (withdrawal rate, Roth conversion ladders, Social Security start age, tax brackets, etc.) as JSONB.

### Import Tracking

| Table          | Key Columns                                                                 |
| -------------- | --------------------------------------------------------------------------- |
| `import_jobs`  | `id`, `tenant_id`, `source` (csv, ofx, manual), `status`, `started_at`, `completed_at`, `error_message` |

---

## Feature Breakdown

### Phase 1 — Foundation (MVP)

- [x] **Project scaffolding** — Monorepo structure, Maven multi-module backend, Vite React frontend, Docker Compose for Postgres, Flyway baseline migration.
- [x] **Tenant & auth system** — Super-admin seeded on first startup; tenant creation with invite code generation; self-registration via invite code; JWT login/refresh; Spring Security filter for tenant-scoped row-level isolation; role-based endpoint authorization (admin/member/viewer).
- [x] **Account management** — CRUD for financial accounts (brokerage, IRA, 401k, Roth, bank); each account scoped to a tenant.
- [x] **Transaction entry** — CRUD for transactions (buy, sell, dividend, deposit, withdrawal); on create/update/delete, auto-recompute affected holdings.
- [x] **Holdings management** — Auto-computed holdings from transactions; manual create/override with `is_manual_override` flag; warning when transactions conflict with a manual override.
- [x] **Manual price entry** — Users can enter a current price per symbol; stored in `prices` table with `source = manual`; dashboard uses most recent price for valuation, falls back to cost basis if no price exists.
- [x] **CSV import (brokerage-specific)** — Upload a CSV file; brokerage-specific parsers (Fidelity, Vanguard, Schwab) handle each institution's format natively; parse and create transactions in bulk; basic error reporting (row-level errors).
- [x] **Dashboard** — Net worth summary (sum of holdings × latest price + property equity + cash accounts); account balances table; allocation pie chart (by account type or asset class).
- [x] **Rental property tracker** — CRUD for properties (address, purchase price, current value, mortgage balance); CRUD for income and expense line items; monthly cash flow summary view.

### Phase 2 — File Import & Price Feed

- [x] ~~**CSV column mapping UI**~~ — Replaced by brokerage-specific import parsers (Fidelity, Vanguard, Schwab) that handle each institution's CSV format natively.
- [x] **OFX/QFX import** — Parse OFX files (standard bank/brokerage download format) using OFX4J; single parser covers most US institutions.
- [x] **Import deduplication** — Detect and skip duplicate transactions across repeated imports based on date, amount, and description hashing (SHA-256 via TransactionHashUtil, `import_hash` column).
- [x] **Finnhub price feed** — Daily @Scheduled job to fetch close prices for all held symbols via Finnhub free API (60 req/min); replaces manual price entry as the primary valuation source.
- [x] **Historical price backfill** — On first symbol addition (via NewHoldingCreatedEvent), backfill daily close prices for the trailing 2 years.

### Phase 3 — Projections & Analytics

- [x] **Retirement projection engine** — Deterministic year-by-year projection with contributions, growth, and inflation-adjusted withdrawals.
- [x] **Scenario comparison** — Side-by-side comparison of multiple retirement scenarios.
- [x] **Roth conversion modeling** — Year-by-year Roth conversion ladder with tax impact estimates.
- [x] **Withdrawal strategy simulator** — Model different drawdown orders (taxable → tax-deferred → Roth).
- [x] **Property ROI analysis** — Cap rate, cash-on-cash return, and equity growth over time.

### Phase 4 — Polish & Operations

- [ ] **Multi-tenant admin panel** — Manage tenants, view usage stats, disable accounts.
- [ ] **Audit log** — Record all data mutations per tenant for debugging and compliance.
- [ ] **Data export** — Full tenant data export as JSON or CSV.
- [ ] **Notifications** — Email alerts for large transactions, sync failures, or projection milestones.
- [ ] **HTTPS & hardening** — TLS via Let's Encrypt, rate limiting, CSRF protection, Content-Security-Policy headers.

---

## API Design Conventions

- **Base path:** `/api/v1/`
- **Auth:** `Authorization: Bearer <JWT>` on all endpoints except `/api/v1/auth/login` and `/api/v1/auth/register`.
- **Tenant scoping:** The JWT contains `tenant_id`; a Spring Security filter enforces row-level access on every query.
- **Pagination:** `?page=0&size=25` with response envelope `{ data: [], page: 0, size: 25, total: 142 }`.
- **Errors:** Standard JSON error body `{ error: "NOT_FOUND", message: "Account not found", status: 404 }`.
- **Naming:** snake_case for JSON fields, matching PostgreSQL column conventions.

### Example Endpoints

```
# Auth & Registration
POST   /api/v1/auth/register             (email, password, invite code)
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh

# Tenant & User Management (admin only)
POST   /api/v1/tenant/invite-codes       (generate invite code)
GET    /api/v1/tenant/users
PUT    /api/v1/tenant/users/{id}/role     (change role)
DELETE /api/v1/tenant/users/{id}

# Super-admin
POST   /api/v1/admin/tenants             (create tenant)
GET    /api/v1/admin/tenants

# Accounts & Portfolio
GET    /api/v1/accounts
POST   /api/v1/accounts
PUT    /api/v1/accounts/{id}
DELETE /api/v1/accounts/{id}
GET    /api/v1/accounts/{id}/holdings
GET    /api/v1/accounts/{id}/transactions
POST   /api/v1/accounts/{id}/transactions

# Holdings (manual override)
PUT    /api/v1/holdings/{id}             (manual override)
POST   /api/v1/holdings                  (manual create)

# Prices (manual entry in Phase 1)
POST   /api/v1/prices                    (symbol, date, close_price)
GET    /api/v1/prices/{symbol}/latest

# File Import
POST   /api/v1/import/csv               (multipart upload)
POST   /api/v1/import/ofx               (multipart upload, OFX/QFX — Phase 2)
GET    /api/v1/import/mappings           (removed — replaced by brokerage-specific parsers)
POST   /api/v1/import/mappings           (removed — replaced by brokerage-specific parsers)
GET    /api/v1/import/jobs               (import history)

# Properties
GET    /api/v1/properties
POST   /api/v1/properties
PUT    /api/v1/properties/{id}
DELETE /api/v1/properties/{id}
GET    /api/v1/properties/{id}/cashflow?from=2025-01&to=2025-12
POST   /api/v1/properties/{id}/income
POST   /api/v1/properties/{id}/expenses

# Projections (Phase 3)
GET    /api/v1/projections/scenarios
POST   /api/v1/projections/scenarios
POST   /api/v1/projections/scenarios/{id}/run
GET    /api/v1/projections/scenarios/{id}/results

# Dashboard
GET    /api/v1/dashboard/summary
```

---

## Frontend Pages (Phase 1)

| Route                          | Page                  | Description                                                              | Roles          |
| ------------------------------ | --------------------- | ------------------------------------------------------------------------ | -------------- |
| `/login`                       | Login                 | Email + password form; redirects to dashboard on success                 | Public         |
| `/register`                    | Register              | Email, password, invite code; creates user and logs in                   | Public         |
| `/`                            | Dashboard             | Net worth summary, account balances, allocation pie chart                | All            |
| `/accounts`                    | Accounts List         | Table of all accounts with type, institution, balance                    | All            |
| `/accounts/:id`                | Account Detail        | Holdings table, transaction history, add transaction form                | All (write: admin, member) |
| `/accounts/:id/import`         | CSV Import            | Upload CSV, preview parsed rows, confirm import                          | Admin, Member  |
| `/holdings/:id`                | Holding Detail        | Transaction history for symbol, manual override form                     | All (write: admin, member) |
| `/prices`                      | Price Entry           | Table of held symbols with latest price; inline edit for manual entry     | Admin, Member  |
| `/properties`                  | Properties List       | Cards or table of all properties with summary metrics                    | All            |
| `/properties/:id`              | Property Detail       | Income/expense log, monthly cash flow chart, add income/expense forms    | All (write: admin, member) |
| `/settings`                    | Tenant Settings       | Invite code management, user list with role controls                     | Admin          |

---

## Deployment

### Docker Compose (Production)

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_DB: wealthview
      POSTGRES_USER: wv_app
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    ports:
      - "5432:5432"

  app:
    build: .
    depends_on:
      - db
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/wealthview
      SPRING_DATASOURCE_USERNAME: wv_app
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      APP_JWT_SECRET: ${JWT_SECRET}
      APP_FINNHUB_API_KEY: ${FINNHUB_API_KEY}
    ports:
      - "8080:8080"

  nginx:
    image: nginx:alpine
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf
      - ./frontend/dist:/usr/share/nginx/html
    ports:
      - "443:443"
      - "80:80"
    depends_on:
      - app

volumes:
  pgdata:
```

### Database Migrations

Use Flyway, applied automatically on application startup. Migration files live in `backend/wealthview-persistence/src/main/resources/db/migration/`.

---

## Decision Log

| # | Decision | Status | Notes |
|---|----------|--------|-------|
| 1 | ORM choice: Spring Data JPA + Hibernate | **Decided** | Battle-tested, familiar; Hibernate 6+ auto-configured via Spring Boot |
| 2 | Application framework: Spring Boot 3.3+ | **Decided** | Replaces raw servlet/Tomcat approach; auto-config for JPA, Security, Scheduling; fat JAR deployment |
| 3 | Price data provider: Finnhub | **Decided** | Free tier (60 req/min) more than sufficient; no licensing restrictions for multi-tenant display |
| 4 | Bank/brokerage data ingestion: CSV + OFX file import | **Decided** | No Plaid — avoids cost, complexity, and production review process; OFX4J for standardized parsing; CSV with user-defined column mappings. Plaid can be revisited if the app scales beyond friends & family |
| 5 | Primary keys: UUID | **Decided** | Prevents information leakage in URLs; simplifies data portability and future imports |
| 6 | Registration: invite-code gated | **Decided** | No open registration; admin generates single-use invite codes per tenant |
| 7 | User roles: admin / member / viewer | **Decided** | Three-tier RBAC scoped per tenant; enforced at Spring Security filter level |
| 8 | Holdings computation: auto-compute + manual override | **Decided** | Holdings auto-recomputed from transactions; manual override supported with conflict warnings |
| 9 | Phase 1 valuation: manual price entry | **Decided** | Users enter prices per symbol manually; Finnhub automated feed deferred to Phase 2 |
| 10 | Repository structure: monorepo | **Decided** | Single repo with `backend/` and `frontend/` directories; single Docker Compose for dev and prod |
| 11 | Multi-tenant isolation strategy: row-level | **Decided** | Row-level (`tenant_id` FK) — simpler ops for small tenant count |
| 12 | Frontend state management: Context API vs. Zustand vs. Redux | **Open** | Start with React Context + useReducer; migrate if complexity warrants it |

---

## Non-Functional Requirements

- **Backup:** Automated daily `pg_dump` to encrypted off-site storage.
- **Performance:** Dashboard loads in < 2s for accounts with up to 10,000 transactions.
- **Security:** All secrets in environment variables; no credentials in source control. Passwords hashed with bcrypt (cost factor 12+).
- **Observability:** Structured JSON logging (SLF4J + Logback); optional Prometheus metrics endpoint.
- **Compatibility:** Targets modern evergreen browsers (Chrome, Firefox, Safari, Edge).

---

## Getting Started (Dev)

```bash
# Prerequisites: Java 21+, Maven 3.9+, Node 20+, Docker

# Start Postgres
docker compose up -d db

# Backend
cd backend
mvn clean install
mvn -pl wealthview-app spring-boot:run

# Frontend (separate terminal)
cd frontend
npm install
npm run dev
```

---

## License

Private / personal use. Not open-sourced at this time.
