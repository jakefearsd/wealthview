# WealthView

A self-hosted, multi-tenant personal finance application for tracking investment portfolios, rental properties, and retirement projections.

## Overview

WealthView consolidates financial visibility across brokerage accounts, rental properties, and retirement plans into a single dashboard. It supports multi-format import from brokerages (Fidelity, Vanguard, Schwab CSV and OFX/QFX), automated stock price feeds via Finnhub, tax-aware retirement projection modeling with multiple withdrawal strategies, and multi-tenant data isolation so one deployment can serve multiple independent users.

### Key Features

- **Investment Portfolio Tracking** -- Accounts, holdings, transactions with automatic cost basis and quantity computation. Theoretical portfolio history charts with historical price data.
- **Rental Property Management** -- Property records with income/expense tracking and monthly cash flow reports.
- **Dashboard** -- Net worth summary combining investments, cash, and property equity with asset allocation pie chart.
- **Multi-Format Import** -- Fidelity, Vanguard, and Schwab CSV parsers plus OFX/QFX import. Content-hash deduplication prevents duplicate transactions across imports.
- **Live Price Feeds** -- Finnhub API integration with historical backfill and scheduled daily sync. Seed data covers major tickers (AAPL, AMZN, GOOG, MSFT, NVDA, VOO, VTI, and more).
- **Retirement Projections** -- Deterministic year-by-year projection engine with three withdrawal strategies (Fixed Percentage, Dynamic Percentage, Vanguard Dynamic Spending), per-pool balance tracking (traditional/roth/taxable), Roth conversion modeling with data-driven federal tax brackets, and scenario comparison.
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

Or use a locally installed PostgreSQL instance with database `wealthview`, user `wv_app`, password `wv_dev_pass`.

### Backend

```bash
cd backend
mvn clean install                         # Build + run all tests
mvn -pl wealthview-app spring-boot:run    # Start with dev profile (port 8080)
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
# Backend -- all modules (Testcontainers integration tests require Docker)
cd backend
mvn test

# Backend -- unit tests only (no Docker required)
mvn test -pl wealthview-core,wealthview-api,wealthview-import,wealthview-projection

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

| Route                    | Page                    | Description                                                    |
|--------------------------|-------------------------|----------------------------------------------------------------|
| `/`                      | Dashboard               | Net worth, allocation pie chart, account balances              |
| `/accounts`              | Accounts List           | All investment accounts with balances                          |
| `/accounts/:id`          | Account Detail          | Holdings, transactions, theoretical portfolio history chart    |
| `/accounts/:id/import`   | Import                  | CSV/OFX file upload with format selection                      |
| `/prices`                | Prices                  | Stock price lookup and history                                 |
| `/projections`           | Projections List        | Scenario card grid with create form, strategy selector         |
| `/projections/compare`   | Scenario Comparison     | Compare up to 3 scenarios with overlay chart and summary table |
| `/projections/:id`       | Projection Detail       | Config summary, run projection, tabbed results visualization   |
| `/properties`            | Properties List         | Rental properties overview                                     |
| `/properties/:id`        | Property Detail         | Income/expenses, monthly cash flow chart                       |
| `/settings`              | Settings                | Invite codes, user management (admin)                          |
| `/login`                 | Login                   | Email/password authentication                                  |
| `/register`              | Register                | New user registration with invite code                         |

### Projection Features

The projection engine supports three visualization modes via a tabbed interface:

- **Balance Over Time** -- Area chart showing projected portfolio balance. When pool data is present (traditional/roth/taxable accounts), displays stacked areas by account type. Includes cumulative contributions overlay and reference lines for retirement year and depletion year.
- **Annual Flows** -- Bar chart breaking down yearly contributions (green), investment growth (blue), and withdrawals (red).
- **Data Table** -- Year-by-year tabular data with the retirement transition row highlighted. Includes pool breakdown columns (traditional, roth, taxable balances), Roth conversion amounts, and tax liability when applicable.

Result summary cards show Final Balance, Years in Retirement, Peak Balance (with year), and Depletion Year. A milestone strip provides an at-a-glance overview of key projection milestones.

#### Withdrawal Strategies

| Strategy | Behavior |
|----------|----------|
| **Fixed Percentage** (default) | Year 1: `balance * rate`. Subsequent years: previous withdrawal adjusted for inflation. The classic "4% rule". |
| **Dynamic Percentage** | Every year: `current_balance * rate`. Withdrawals rise and fall with the portfolio -- never depletes to zero. |
| **Vanguard Dynamic Spending** | Year 1: `balance * rate`. Subsequent years: recalculated from current balance, but year-over-year change capped at a ceiling (+5% default) and floored (-2.5% default). Smooths spending volatility. |

#### Account Pool Tracking and Roth Conversion

When a scenario includes accounts with different types (traditional, roth, taxable), the engine tracks each pool separately:

- **Pre-retirement:** Contributions go to their respective pools. Growth applies per-pool.
- **Roth conversion:** Each year, moves up to the configured annual amount from traditional to roth. Federal tax is computed on the conversion amount (plus other income) using progressive bracket data stored in the database (2022-2025 brackets for single and married filing jointly). Tax is deducted from the taxable pool first, then traditional.
- **Retirement withdrawals:** Drawn in tax-efficient order -- taxable first, then traditional (taxed), then roth (tax-free).
- **Scenario comparison:** Compare up to 3 scenarios side-by-side on the `/projections/compare` page with an overlay chart and summary table.

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

| Endpoint                        | Method | Description                                        |
|---------------------------------|--------|----------------------------------------------------|
| `/api/v1/projections`           | GET, POST | List or create scenarios                        |
| `/api/v1/projections/{id}`      | GET, DELETE | Get or delete scenario                        |
| `/api/v1/projections/{id}/run`  | GET    | Run projection, get year-by-year results           |
| `/api/v1/projections/compare`   | POST   | Compare 2-3 scenarios side-by-side                 |

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

| Migration | Description                                              |
|-----------|----------------------------------------------------------|
| V001      | Baseline tenants and users tables                        |
| V002      | Invite codes table                                       |
| V003      | Accounts table                                           |
| V004      | Transactions table                                       |
| V005      | Holdings table with manual override flag                 |
| V006      | Prices table                                             |
| V007      | Properties tables (properties, income, expenses)         |
| V008      | Import jobs table                                        |
| V009      | Projection tables (scenarios, accounts)                  |
| V010      | Transaction hash column for deduplication                |
| V011      | Opening balance transaction type                         |
| V012      | Account type column on projection accounts               |
| V013      | Tax brackets table                                       |
| R__seed_stock_prices  | Repeatable seed data for stock prices           |
| R__seed_tax_brackets  | Repeatable seed data for 2022-2025 federal tax brackets |

## License

Private -- all rights reserved.

---

## Feature Walkthrough

This walkthrough steps through every major feature in WealthView. It doubles as a manual test script -- follow it end-to-end to verify the application is working correctly.

### Prerequisites

Start the backend and frontend dev servers:

```bash
# Terminal 1: Start PostgreSQL (if using Docker)
docker compose up -d db

# Terminal 2: Start backend
cd backend
mvn clean install -DskipTests
mvn -pl wealthview-app spring-boot:run

# Terminal 3: Start frontend
cd frontend
npm install
npm run dev
```

Open the frontend in a browser (typically http://localhost:5173).

---

### 1. Login

1. Navigate to http://localhost:5173/login.
2. Enter `admin@wealthview.local` / `admin123`.
3. Click **Login**.
4. **Verify:** You are redirected to the Dashboard page.

---

### 2. Dashboard

1. After login, you land on the Dashboard (`/`).
2. **Verify:** The page shows a Net Worth summary card, an asset allocation pie chart, and account balance cards.
3. If this is a fresh database, values will be zero until you add accounts and holdings.

---

### 3. Create an Investment Account

1. Navigate to **Accounts** in the sidebar (or go to `/accounts`).
2. Click **Add Account**.
3. Fill in:
   - **Name:** `Fidelity Brokerage`
   - **Type:** `brokerage`
   - **Institution:** `Fidelity`
4. Click **Create**.
5. **Verify:** The new account appears in the accounts list.

---

### 4. Add Transactions

1. Click on **Fidelity Brokerage** to open the account detail page.
2. In the **Add Transaction** form:
   - **Date:** `2025-01-15`
   - **Type:** `buy`
   - **Symbol:** `VOO`
   - **Quantity:** `10`
   - **Amount:** `5000`
3. Click **Add Transaction**.
4. **Verify:** The transaction appears in the history table and a **VOO** holding is auto-created with quantity 10 and cost basis $5,000.
5. Add a second transaction:
   - **Date:** `2025-02-01`, **Type:** `buy`, **Symbol:** `AAPL`, **Quantity:** `25`, **Amount:** `5500`
6. **Verify:** Two holdings now appear (VOO and AAPL).

---

### 5. Add a Price and Check Valuation

1. Navigate to **Prices** in the sidebar (`/prices`).
2. Enter a price for `VOO`:
   - **Symbol:** `VOO`
   - **Price:** `540`
   - **Date:** today's date
3. Click **Add Price**.
4. **Verify:** The price appears in the table.
5. Navigate back to the **Dashboard**.
6. **Verify:** Net worth now reflects the market value of VOO holdings (10 x $540 = $5,400). AAPL falls back to cost basis ($5,500) since no price was entered.

---

### 6. Import Transactions from CSV

1. Navigate to the **Fidelity Brokerage** account detail page.
2. Click **Import** (or navigate to `/accounts/{id}/import`).
3. Select **Format:** `fidelity`.
4. Upload a Fidelity CSV export file (or any CSV with columns matching the Fidelity format).
5. Click **Import**.
6. **Verify:** Imported transactions appear in the account's transaction list. Holdings are recomputed. Duplicate transactions (if re-importing the same file) are skipped via content-hash deduplication.

---

### 7. Import from OFX/QFX

1. From the import page, switch to **OFX** format.
2. Upload an OFX or QFX file downloaded from your brokerage.
3. **Verify:** Transactions are parsed and imported. The import job appears in the import history.

---

### 8. Portfolio History Chart

1. Navigate to an account with holdings and historical price data (seed data covers VOO, VTI, AAPL, etc.).
2. Scroll to the **Portfolio History** chart section.
3. **Verify:** A line chart shows the theoretical historical portfolio value based on holdings multiplied by historical daily prices. Symbols without price data (e.g., money market funds like SPAXX) are skipped gracefully.

---

### 9. Create a Rental Property

1. Navigate to **Properties** in the sidebar (`/properties`).
2. Click **Add Property**.
3. Fill in:
   - **Address:** `123 Oak Street`
   - **Purchase Price:** `350000`
   - **Current Value:** `400000`
   - **Mortgage Balance:** `280000`
4. Click **Create**.
5. **Verify:** The property appears in the properties list with equity displayed ($400,000 - $280,000 = $120,000).

---

### 10. Add Rental Income and Expenses

1. Click on **123 Oak Street** to open the property detail page.
2. Add income:
   - **Date:** `2025-01-01`, **Amount:** `2200`, **Category:** `rent`
3. Add an expense:
   - **Date:** `2025-01-15`, **Amount:** `1500`, **Category:** `mortgage`
4. Add another expense:
   - **Date:** `2025-01-20`, **Amount:** `150`, **Category:** `insurance`
5. **Verify:** The cash flow chart shows January with $2,200 income and $1,650 expenses, yielding $550 net cash flow. The Dashboard net worth now includes property equity.

---

### 11. Create a Basic Retirement Projection

1. Navigate to **Projections** in the sidebar (`/projections`).
2. Fill in the **Create Scenario** form:
   - **Name:** `Basic 4% Rule`
   - **Retirement Date:** 20-25 years from now (e.g., `2050-01-01`)
   - **End Age:** `90`
   - **Inflation Rate:** `0.03`
   - **Withdrawal Strategy:** `Fixed Percentage` (the default)
   - **Withdrawal Rate:** `0.04`
   - **Account 1:** Initial Balance `500000`, Annual Contribution `20000`, Expected Return `0.07`
3. Click **Create Scenario**.
4. **Verify:** The scenario card appears in the grid.
5. Click the scenario name to open the detail page.
6. Click **Run Projection**.
7. **Verify:**
   - The **Balance Over Time** chart shows growth during working years, then gradual drawdown in retirement.
   - The **Annual Flows** chart shows green contribution bars pre-retirement and red withdrawal bars post-retirement.
   - The **Data Table** shows year-by-year numbers with the retirement transition row highlighted.
   - Summary cards show Final Balance, Years in Retirement, Peak Balance, and Depletion Year (if the portfolio runs out).

---

### 12. Test Dynamic Percentage Strategy

1. Go back to **Projections** (`/projections`).
2. Create a new scenario:
   - **Name:** `Dynamic 4%`
   - Same parameters as above, but set **Withdrawal Strategy** to `Dynamic Percentage`.
3. Run the projection.
4. **Verify:** Unlike the fixed percentage strategy, withdrawals fluctuate with the portfolio balance. The portfolio should never fully deplete to zero (since you always withdraw a percentage of the remaining balance). Compare the data table withdrawals -- they should be `current_balance * 0.04` each year.

---

### 13. Test Vanguard Dynamic Spending Strategy

1. Create another scenario:
   - **Name:** `Vanguard Dynamic`
   - Set **Withdrawal Strategy** to `Vanguard Dynamic Spending`.
   - **Ceiling:** `0.05` (5% max increase year-over-year)
   - **Floor:** `-0.025` (2.5% max decrease year-over-year)
2. Run the projection.
3. **Verify:** Withdrawals are smoothed -- year-over-year changes in the data table should never exceed +5% or drop below -2.5% relative to the previous year's withdrawal.

---

### 14. Create a Multi-Pool Scenario with Roth Conversion

1. Create a new scenario:
   - **Name:** `Roth Conversion Ladder`
   - **Retirement Date:** 10 years from now
   - **End Age:** `85`
   - **Inflation Rate:** `0.02`
   - **Filing Status:** `Single`
   - **Annual Roth Conversion:** `50000`
   - **Other Income:** `0`
   - **Account 1:** Initial Balance `500000`, Contribution `20000`, Return `0.07`, **Account Type:** `Traditional`
   - **Account 2:** Initial Balance `100000`, Contribution `7000`, Return `0.07`, **Account Type:** `Roth`
2. Click **Create Scenario**, then open and run the projection.
3. **Verify:**
   - The **Balance Over Time** chart shows stacked colored areas: orange (traditional), green (roth), blue (taxable).
   - The **Data Table** includes columns for Traditional Balance, Roth Balance, Roth Conversion Amount, and Tax Liability.
   - Each year with a conversion shows a non-null Tax Liability computed from federal tax brackets.
   - Traditional balance decreases as funds convert to Roth.
   - Once the traditional balance is fully converted, no further conversions or conversion tax appear.
   - In retirement, withdrawals are drawn tax-free from the Roth pool.

---

### 15. Create an All-Roth Portfolio

1. Create a scenario:
   - **Name:** `All Roth`
   - **Filing Status:** `Single`
   - **Account 1:** Initial Balance `500000`, Contribution `7000`, Return `0.07`, **Account Type:** `Roth`
2. Run the projection.
3. **Verify:** Tax Liability is zero (or null) for every year. Roth withdrawals in retirement are entirely tax-free.

---

### 16. Compare Scenarios

1. Navigate to **Projections** and click the **Compare Scenarios** link (or go to `/projections/compare`).
2. Select 2-3 scenarios from the dropdowns (e.g., `Basic 4% Rule`, `Dynamic 4%`, `Roth Conversion Ladder`).
3. Click **Compare**.
4. **Verify:**
   - An overlay area chart displays all selected scenarios with distinct colors (blue, green, purple).
   - A summary table below shows each scenario's Final Balance, Peak Balance, Depletion Year, and Years in Retirement.
   - Selecting fewer than 2 or more than 3 scenarios is prevented by the UI.

---

### 17. Delete a Scenario

1. From the **Projections** list, click the delete button on one of the test scenarios.
2. **Verify:** The scenario is removed from the list.

---

### 18. Invite a New User (Admin)

1. Navigate to **Settings** (`/settings`).
2. Under **Invite Codes**, click **Generate Invite Code**.
3. **Verify:** A new invite code appears in the list.
4. Copy the invite code.
5. Log out and navigate to `/register`.
6. Register a new user with the invite code.
7. **Verify:** Registration succeeds and the new user can log in. The new user belongs to the same tenant and can see the same accounts, properties, and projections.

---

### 19. Verify Backward Compatibility

1. Log back in as admin.
2. If pre-existing scenarios were created before the Phase 3 updates (withdrawal strategies, account types), open one and run it.
3. **Verify:**
   - The projection runs without errors.
   - Pool breakdown columns are absent (null) -- the scenario uses the legacy single-pool path.
   - The default Fixed Percentage strategy is applied.

---

### 20. Check the Dashboard with Full Data

1. Navigate back to the **Dashboard** (`/`).
2. **Verify:** Net worth now includes:
   - Investment account holdings (valued at latest prices or cost basis).
   - Property equity (current value minus mortgage balance).
   - Cash account balances.
3. The allocation pie chart breaks down assets by type.

---

### Summary Checklist

| # | Feature | What to verify |
|---|---------|----------------|
| 1 | Login | Redirects to dashboard |
| 2 | Dashboard | Net worth, pie chart, account cards |
| 3 | Create account | Appears in list |
| 4 | Add transactions | Holdings auto-computed |
| 5 | Add price | Valuation updates on dashboard |
| 6 | CSV import | Transactions imported, dedup works |
| 7 | OFX import | Transactions parsed correctly |
| 8 | Portfolio history | Chart renders with historical prices |
| 9 | Create property | Equity calculated correctly |
| 10 | Rental income/expenses | Cash flow chart and net calculation |
| 11 | Fixed % projection | Balance chart, flows, data table, summary cards |
| 12 | Dynamic % projection | Withdrawals track current balance, never depletes |
| 13 | Vanguard dynamic | Withdrawal changes capped at ceiling/floor |
| 14 | Roth conversion | Pool tracking, tax computed, stacked chart |
| 15 | All-Roth portfolio | Zero tax liability on withdrawals |
| 16 | Compare scenarios | Overlay chart, summary table, 2-3 scenarios |
| 17 | Delete scenario | Removed from list |
| 18 | Invite + register | New user joins tenant |
| 19 | Backward compat | Old scenarios run without errors |
| 20 | Dashboard with data | All assets reflected in net worth |
