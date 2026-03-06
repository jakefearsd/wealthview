# WealthView

A self-hosted, multi-tenant personal finance application for tracking investment portfolios, rental properties, and retirement projections.

## Overview

WealthView consolidates financial visibility across brokerage accounts, rental properties, and retirement plans into a single dashboard. It supports multi-format import from brokerages (Fidelity, Vanguard, Schwab CSV and OFX/QFX), automated stock price feeds via Finnhub, tax-aware retirement projection modeling with multiple withdrawal strategies, and multi-tenant data isolation so one deployment can serve multiple independent users.

### Key Features

- **Investment Portfolio Tracking** -- Accounts, holdings, transactions with automatic cost basis and quantity computation. Theoretical portfolio history charts with historical price data.
- **Rental Property Management** -- Property records with income/expense tracking, monthly cash flow reports, loan amortization-based mortgage balance computation, and automated Zillow valuation scraping with history tracking.
- **Dashboard** -- Net worth summary combining investments, cash, and property equity with asset allocation pie chart.
- **Multi-Format Import** -- Fidelity, Vanguard, and Schwab CSV parsers plus OFX/QFX import. Content-hash deduplication prevents duplicate transactions across imports.
- **Live Price Feeds** -- Finnhub API integration with historical backfill and scheduled daily sync. Seed data covers major tickers (AAPL, AMZN, GOOG, MSFT, NVDA, VOO, VTI, and more).
- **Retirement Projections** -- Deterministic year-by-year projection engine with three withdrawal strategies (Fixed Percentage, Dynamic Percentage, Vanguard Dynamic Spending), per-pool balance tracking (traditional/roth/taxable), Roth conversion modeling with data-driven federal tax brackets, scenario comparison, and editable scenarios with save-and-rerun workflow.
- **Spending Profiles & Viability Analysis** -- Define essential and discretionary spending categories with income streams (Social Security, pensions, part-time work). Link a spending profile to a scenario to see whether your withdrawal strategy covers your actual spending needs. The engine computes inflation-adjusted spending vs withdrawal per year, with shortfalls absorbed by discretionary spending first.
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
| `wealthview-import`      | CSV/OFX parsers, Finnhub price feed client, Zillow scraper |
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
| `ZILLOW_ENABLED`       | `false`                                                | Enable Zillow property valuation scraping |

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
| `/projections/:id`       | Projection Detail       | Config summary, edit mode, run projection, tabbed results with spending analysis |
| `/spending-profiles`     | Spending Profiles       | Create and manage spending profiles with income streams        |
| `/properties`            | Properties List         | Rental properties overview                                     |
| `/properties/:id`        | Property Detail         | Income/expenses, monthly cash flow chart                       |
| `/settings`              | Settings                | Invite codes, user management (admin)                          |
| `/login`                 | Login                   | Email/password authentication                                  |
| `/register`              | Register                | New user registration with invite code                         |

### Projection Features

The projection engine supports four visualization modes via a tabbed interface:

- **Balance Over Time** -- Area chart showing projected portfolio balance. When pool data is present (traditional/roth/taxable accounts), displays stacked areas by account type. Includes cumulative contributions overlay and reference lines for retirement year and depletion year.
- **Annual Flows** -- Bar chart breaking down yearly contributions (green), investment growth (blue), and withdrawals (red).
- **Spending Analysis** -- Stacked area chart comparing withdrawals against spending needs. Shows essential expenses, discretionary spending (after any cuts), withdrawal line, and income streams overlay. Only appears when the scenario has a linked spending profile.
- **Data Table** -- Year-by-year tabular data with the retirement transition row highlighted. Includes pool breakdown columns (traditional, roth, taxable balances), Roth conversion amounts, tax liability, and spending viability columns (essential, discretionary, income, net need, surplus/deficit) when a spending profile is linked.

Result summary cards show Final Balance, Years in Retirement, Peak Balance (with year), and Depletion Year. A milestone strip provides an at-a-glance overview of key projection milestones.

#### Scenario Editing

Scenarios are fully editable. On the projection detail page, click **Edit** to switch into form mode. All fields (name, dates, accounts, strategy, spending profile) can be modified. Click **Save & Re-run** to persist changes and immediately re-run the projection with updated inputs.

#### Spending Profiles

Spending profiles are shared entities managed on the `/spending-profiles` page. Each profile defines:

- **Essential Expenses** -- Non-negotiable annual spending (housing, food, healthcare). Inflates annually at the scenario's inflation rate.
- **Discretionary Expenses** -- Flexible annual spending (travel, entertainment). Absorbs 100% of any shortfall when withdrawals don't cover total spending.
- **Income Streams** -- Named income sources with annual amount, start age, and optional end age. Examples: Social Security (starts at 67, no end), part-time work (age 60-67). Income reduces the withdrawal needed from the portfolio.

Link a spending profile to a scenario via the dropdown in the scenario form. The engine computes viability per year:
- `net_spending_need = (essential + discretionary) - income_streams` (inflation-adjusted)
- `surplus = withdrawal - net_spending_need` (positive = surplus, negative = deficit)
- When there's a deficit, essential expenses are fully covered first; discretionary absorbs the shortfall (floored at zero).

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
| `/api/v1/properties/{id}/valuations`  | GET    | Valuation history         |
| `/api/v1/properties/{id}/valuations/refresh` | POST | Trigger Zillow valuation scrape (202 Accepted) |

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
| `/api/v1/projections/{id}`      | GET, PUT, DELETE | Get, update, or delete scenario            |
| `/api/v1/projections/{id}/run`  | GET    | Run projection, get year-by-year results           |
| `/api/v1/projections/compare`   | POST   | Compare 2-3 scenarios side-by-side                 |

### Spending Profiles

| Endpoint                           | Method         | Description                    |
|------------------------------------|----------------|--------------------------------|
| `/api/v1/spending-profiles`        | GET, POST      | List or create profiles        |
| `/api/v1/spending-profiles/{id}`   | GET, PUT, DELETE | Profile CRUD                 |

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
| V014      | Spending profiles table (essential/discretionary/income streams) |
| V015      | Add spending_profile_id FK to projection scenarios       |
| V016      | Loan detail columns on properties (amortization support) |
| V017      | Property valuations history table                        |
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

### 9. Create a Rental Property (Manual Balance)

1. Navigate to **Properties** in the sidebar (`/properties`).
2. Click **New Property**.
3. Fill in:
   - **Address:** `123 Oak Street`
   - **Purchase Price:** `350000`
   - **Purchase Date:** `2020-06-01`
   - **Current Value:** `400000`
   - **Mortgage Balance:** `280000`
4. Leave the **Loan Details** section collapsed (do not provide loan fields).
5. Click **Create**.
6. **Verify:** The property appears in the properties list with equity displayed ($400,000 - $280,000 = $120,000). No "Computed Balance" badge.

---

### 10. Add Rental Income and Expenses

1. Click on **123 Oak Street** to open the property detail page.
2. **Verify:** The mortgage shows a **Manual** badge.
3. Add income:
   - **Date:** `2025-01-01`, **Amount:** `2200`, **Category:** `rent`
4. Add an expense:
   - **Date:** `2025-01-15`, **Amount:** `1500`, **Category:** `mortgage`
5. Add another expense:
   - **Date:** `2025-01-20`, **Amount:** `150`, **Category:** `insurance`
6. **Verify:** The cash flow chart shows January with $2,200 income and $1,650 expenses, yielding $550 net cash flow. The Dashboard net worth now includes property equity.

---

### 10a. Create a Property with Loan Details (Computed Balance)

1. Go back to **Properties** (`/properties`).
2. Click **New Property**.
3. Fill in the basic fields:
   - **Address:** `456 Elm Avenue`
   - **Purchase Price:** `300000`
   - **Purchase Date:** `2020-01-01`
   - **Current Value:** `350000`
   - **Mortgage Balance:** `250000`
4. Click **Show Loan Details** to expand the loan section.
5. Fill in:
   - **Loan Amount:** `280000`
   - **Annual Interest Rate:** `6.5`
   - **Loan Term (months):** `360`
   - **Loan Start Date:** `2020-01-01`
   - Check **Use computed mortgage balance (amortization)**
6. Click **Create**.
7. **Verify:**
   - The property appears with a **Computed Balance** badge in the list.
   - The mortgage balance is NOT $250,000 (the manual value); it is the amortization-computed remaining balance (approximately $257,034 as of March 2026, depending on current date).
   - Equity = Current Value minus the computed balance.

---

### 10b. View Loan Details on Property Detail Page

1. Click on **456 Elm Avenue** to open the detail page.
2. **Verify:**
   - The mortgage row shows a **Computed** badge (blue).
   - A **Loan Details** panel appears below the summary showing: Amount ($280,000), Rate (6.5%), Term (360 months), Start (2020-01-01).
   - The mortgage balance and equity are computed from amortization, not the manual $250,000.

---

### 10c. Toggle Between Computed and Manual Balance

1. Using the API (or by editing the property in a future edit form), update the property to set `use_computed_balance: false`:
   ```bash
   curl -X PUT http://localhost:8080/api/v1/properties/{id} \
     -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"address":"456 Elm Avenue","purchase_price":300000,"purchase_date":"2020-01-01",
          "current_value":350000,"mortgage_balance":250000,
          "loan_amount":280000,"annual_interest_rate":6.5,"loan_term_months":360,
          "loan_start_date":"2020-01-01","use_computed_balance":false}'
   ```
2. **Verify:** The mortgage balance reverts to $250,000 (manual) and equity becomes $100,000.
3. Toggle back to `use_computed_balance: true` and verify the computed balance returns.

---

### 10d. Partial Loan Details Validation

1. Using the API, try to create a property with only some loan fields:
   ```bash
   curl -X POST http://localhost:8080/api/v1/properties \
     -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"address":"Bad Property","purchase_price":200000,"purchase_date":"2023-01-01",
          "current_value":210000,"loan_amount":180000}'
   ```
2. **Verify:** Returns **400 Bad Request** with message "Loan details must be provided in full (loanAmount, annualInterestRate, loanTermMonths, loanStartDate) or not at all".

---

### 10e. Fully Paid-Off Loan

1. Create a property with a loan that started 30+ years ago:
   ```bash
   curl -X POST http://localhost:8080/api/v1/properties \
     -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"address":"700 Paid Off Circle","purchase_price":150000,"purchase_date":"1990-01-01",
          "current_value":400000,"mortgage_balance":0,
          "loan_amount":120000,"annual_interest_rate":8.0,"loan_term_months":360,
          "loan_start_date":"1990-01-01","use_computed_balance":true}'
   ```
2. **Verify:** Mortgage balance is $0 and equity equals the full current value ($400,000). The amortization calculator returns zero for loans past their term.

---

### 10f. Valuation History (Empty State)

1. Open any property's detail page.
2. **Verify:** If no valuations have been recorded, the page shows "No valuation history yet" with a **Refresh Valuation** button.

---

### 10g. Valuation Refresh (Zillow Disabled)

1. Click the **Refresh Valuation** button on any property detail page.
2. **Verify:** A toast error appears because Zillow scraping is disabled by default (503 Service Unavailable). This is expected behavior.

---

### 10h. Valuation Refresh (Zillow Enabled)

To test with Zillow enabled:

1. Set `app.zillow.enabled=true` in `application.yml` (or via environment variable) and restart the backend.
2. Navigate to a property detail page and click **Refresh Valuation**.
3. **Verify:** If the address is found on Zillow, a valuation is recorded and the history chart and table appear. The property's current value is updated to the Zestimate.
4. **Note:** Zillow may block or rate-limit scraping. Empty results are handled gracefully (no crash, warning logged server-side).

---

### 10i. Dashboard Reflects Computed Balances

1. Navigate to the **Dashboard** (`/`).
2. **Verify:**
   - Properties with `use_computed_balance: true` show equity computed from amortization (not the manual mortgage balance).
   - Properties with `use_computed_balance: false` show equity from the manual mortgage balance.
   - Net worth correctly sums all property equity, investment holdings, and cash.
   - The allocation pie chart includes a "property" slice.

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

### 17. Create a Spending Profile

1. Navigate to **Spending Profiles** in the sidebar (`/spending-profiles`).
2. Click **New Profile**.
3. Fill in:
   - **Name:** `Moderate Retirement`
   - **Essential Expenses:** `40000`
   - **Discretionary Expenses:** `20000`
4. Add an income stream:
   - **Name:** `Social Security`, **Annual Amount:** `24000`, **Start Age:** `67`, **End Age:** (leave blank)
5. Click **Create**.
6. **Verify:** The profile card appears showing essential ($40,000), discretionary ($20,000), and the Social Security income stream.
7. Optionally add a second income stream (e.g., `Part-time Work`, $30,000, ages 60-67) by editing the profile.

---

### 18. Link a Spending Profile to a Scenario

1. Navigate to **Projections** and open the **Basic 4% Rule** scenario detail page.
2. Click **Edit**.
3. In the **Spending Profile** dropdown, select `Moderate Retirement`.
4. Click **Save & Re-run**.
5. **Verify:**
   - A spending profile summary card appears on the detail page.
   - A fourth tab **Spending Analysis** appears in the results tabs.
   - Click **Spending Analysis** to see the stacked area chart with essential expenses (red), discretionary after cuts (amber), withdrawal line, and income streams line.
   - The **Data Table** now includes additional columns: Essential, Discretionary, Income, Net Need, Surplus/Deficit, and Discretionary After Cuts.
   - In early retirement years before Social Security starts (if applicable), the net spending need is higher because there's no income offset.
   - After Social Security kicks in at age 67, the net need drops and surplus increases.

---

### 19. Edit a Scenario

1. Open any scenario detail page.
2. Click **Edit** to enter edit mode.
3. Modify any fields -- e.g., change the withdrawal strategy from Fixed Percentage to Dynamic Percentage, add a second account, or change the end age.
4. Click **Save & Re-run**.
5. **Verify:** The scenario updates are saved, the projection re-runs automatically with the new parameters, and the results reflect the changes.
6. Click **Cancel** during editing to discard changes and return to the read-only view.

---

### 20. Verify Spending Viability with Shortfall

1. Create a scenario with low initial balance and high spending:
   - **Name:** `Shortfall Test`
   - **Account 1:** Initial Balance `200000`, Contribution `5000`, Return `0.05`
   - **Spending Profile:** `Moderate Retirement`
   - **Retirement Date:** 5 years from now
2. Run the projection.
3. **Verify:**
   - In the **Data Table**, some retirement years show a negative Surplus/Deficit value, meaning withdrawals don't cover spending.
   - The **Discretionary After Cuts** column shows reduced discretionary spending (below the original $20,000 inflated amount) in deficit years.
   - Essential expenses are always fully covered -- shortfalls come entirely from discretionary.
   - The **Spending Analysis** chart visually shows the gap between withdrawals and spending needs.

---

### 21. Delete a Scenario

1. From the **Projections** list, click the delete button on one of the test scenarios.
2. **Verify:** The scenario is removed from the list.

---

### 22. Invite a New User (Admin)

1. Navigate to **Settings** (`/settings`).
2. Under **Invite Codes**, click **Generate Invite Code**.
3. **Verify:** A new invite code appears in the list.
4. Copy the invite code.
5. Log out and navigate to `/register`.
6. Register a new user with the invite code.
7. **Verify:** Registration succeeds and the new user can log in. The new user belongs to the same tenant and can see the same accounts, properties, and projections.

---

### 23. Verify Backward Compatibility

1. Log back in as admin.
2. If pre-existing scenarios were created before spending profiles were added, open one and run it.
3. **Verify:**
   - The projection runs without errors.
   - Pool breakdown columns are absent (null) for legacy single-pool scenarios.
   - Spending viability columns are absent (null) for scenarios without a spending profile.
   - The default Fixed Percentage strategy is applied.

---

### 24. Check the Dashboard with Full Data

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
| 9 | Create property (manual) | Equity = current value - manual mortgage balance |
| 10 | Rental income/expenses | Cash flow chart and net calculation |
| 10a | Property with loan details | Computed balance via amortization, "Computed Balance" badge |
| 10b | Loan details display | Loan panel on detail page, Computed badge on mortgage |
| 10c | Toggle computed/manual | Balance switches between amortization and manual on toggle |
| 10d | Partial loan validation | 400 error when only some loan fields provided |
| 10e | Paid-off loan | Zero balance for loans past their term |
| 10f | Valuation history empty | "No valuation history" placeholder with refresh button |
| 10g | Valuation refresh (disabled) | 503 when Zillow disabled |
| 10h | Valuation refresh (enabled) | Zestimate recorded, chart and table appear |
| 10i | Dashboard computed balances | Net worth uses computed balance for flagged properties |
| 11 | Fixed % projection | Balance chart, flows, data table, summary cards |
| 12 | Dynamic % projection | Withdrawals track current balance, never depletes |
| 13 | Vanguard dynamic | Withdrawal changes capped at ceiling/floor |
| 14 | Roth conversion | Pool tracking, tax computed, stacked chart |
| 15 | All-Roth portfolio | Zero tax liability on withdrawals |
| 16 | Compare scenarios | Overlay chart, summary table, 2-3 scenarios |
| 17 | Spending profiles | Create profile with essential/discretionary/income streams |
| 18 | Link spending profile | Spending analysis tab and viability columns appear |
| 19 | Edit scenario | Save & re-run updates scenario and reruns projection |
| 20 | Spending shortfall | Discretionary absorbs deficit, essential fully covered |
| 21 | Delete scenario | Removed from list |
| 22 | Invite + register | New user joins tenant |
| 23 | Backward compat | Old scenarios run without errors, spending fields null |
| 24 | Dashboard with data | All assets reflected in net worth |
