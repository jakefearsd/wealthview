# WealthView — Personal Finance Tracking Platform

## Overview

WealthView is a self-hosted, multi-tenant personal finance application focused on investment portfolio monitoring, rental property income/expense tracking, and retirement projection modeling. It is designed for financially literate users who want full ownership of their data and the flexibility to share the platform with family or trusted peers.

**Current state:** Phases 1–8 in the [Feature Breakdown](#feature-breakdown) have
shipped; the latest release is **v1.2.4** (2026-07-27). This document describes
what the code actually does — anything not yet built is confined to the
[Roadmap](#roadmap--not-yet-built) subsection. For the release history see
[CHANGELOG.md](CHANGELOG.md); for contributor conventions see `CLAUDE.md`.

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
┌─────────────┐
│  React SPA  │──┐
│  (Vite)     │  │    ┌──────────────────────────────────────┐       ┌────────────┐
└─────────────┘  ├───►│  Spring Boot REST API (Maven)        │◄─────►│ PostgreSQL │
┌─────────────┐  │HTTP│  ├─ Spring Security + JWT auth + MFA │  JPA  │            │
│ React Native│──┘    │  ├─ Tenant isolation filter          │       └────────────┘
│   (mobile)  │       │  ├─ Portfolio service                │
└─────────────┘       │  ├─ Property service                 │
       ▲              │  ├─ Retirement projection engines    │
       │              │  │   (deterministic + Monte Carlo)   │
┌──────┴──────┐       │  ├─ Import service (CSV, OFX)        │
│  @wealthview│       │  ├─ @Scheduled price + split sync    │
│   /shared   │       │  └─ Audit log / export / admin       │
└─────────────┘       └──────────────────────────────────────┘
                                     │
                        ┌────────────┴────────────┐
                  ┌─────▼─────┐            ┌──────▼──────┐
                  │  Finnhub  │            │   Zillow    │
                  │ (prices,  │            │ (property   │
                  │  splits)  │            │  valuation) │
                  └───────────┘            └─────────────┘
```

### Tech Stack

| Layer          | Technology                                              |
| -------------- | ------------------------------------------------------- |
| Frontend       | React 19.2, TypeScript 5.9, Vite 8, React Router 8, Recharts 3 |
| Mobile         | React Native 0.87, React Navigation 7                   |
| Shared code    | `@wealthview/shared` npm workspace (API client, formatting, portfolio math) |
| HTTP Client    | Axios                                                   |
| Backend        | Java 25, Spring Boot 4.1, Spring Web MVC, Jackson 3      |
| Build          | Maven (multi-module backend), npm workspaces (JS)       |
| ORM / DB       | Spring Data JPA (Hibernate 7), PostgreSQL 16+, Flyway 13 |
| Auth           | Spring Security, JWT access + refresh tokens, TOTP MFA, bcrypt |
| Caching        | Caffeine (account balances, latest prices, exchange rates, mobile app versions) |
| Scheduling     | Spring @Scheduled (cron expressions)                    |
| Price Feed     | Finnhub API (free tier, 60 req/min) — quotes and stock splits |
| Property Data  | Zillow valuation scraping via jsoup (optional, off by default) |
| File Import    | Apache Commons CSV, OFX4J (OFX/QFX parsing)             |
| Testing        | JUnit 5, Mockito, AssertJ, Testcontainers (Postgres), Vitest 4, Playwright, Jest |
| Quality gates  | PMD, CPD, SpotBugs, Checkstyle, JaCoCo (all fail `mvn verify`); PIT advisory |
| Observability  | Micrometer + Actuator (`/actuator/prometheus`), structured JSON logs |
| Deployment     | Docker Compose (Spring Boot fat JAR serving the built SPA + Postgres) |

### Maven Module Structure

See [Repository Structure](#repository-structure) below for the full monorepo layout. The backend Maven modules are:

| Module                     | Responsibility                                                    |
| -------------------------- | ----------------------------------------------------------------- |
| `wealthview-api`           | REST controllers, Spring Security config, exception handlers      |
| `wealthview-core`          | Domain models, services, business logic                           |
| `wealthview-persistence`   | JPA entities, Spring Data repositories, Flyway migrations         |
| `wealthview-import`        | CSV parser, OFX parser, Finnhub price client                     |
| `wealthview-projection`    | Retirement modeling engine                                        |
| `wealthview-app`           | Spring Boot main class, `application.yml`, fat JAR packaging      |

The dependency direction is **strict** and enforced by the POMs:

```
wealthview-app         → depends on ALL modules (assembles the application)
wealthview-api         → wealthview-core
wealthview-core        → wealthview-persistence
wealthview-import      → wealthview-core
wealthview-projection  → wealthview-core
wealthview-persistence → nothing (leaf module)
```

`wealthview-api` never depends directly on `wealthview-persistence`,
`wealthview-import`, or `wealthview-projection`. Engine interfaces (e.g.
`ProjectionEngine`) are declared in `wealthview-core` and implemented in the
downstream module, so the API layer talks only to core.

---

## Key Design Decisions

### Repository Structure

Monorepo containing the backend, the web SPA, the mobile app, shared JS code,
and the operational tooling. The JavaScript side is an **npm workspaces**
monorepo — run `npm install` once at the root and `shared`, `frontend`, and
`mobile` wire themselves together (`@wealthview/shared` resolves to a symlink).

```
wealthview/
├── backend/                   (Maven multi-module project)
│   ├── pom.xml                (parent POM, spring-boot-starter-parent)
│   ├── pmd-ruleset.xml        (PMD rules — enforced on verify)
│   ├── spotbugs-exclude.xml   (SpotBugs exclusions, each with a comment)
│   ├── wealthview_checks.xml  (Checkstyle config)
│   ├── wealthview-api/
│   ├── wealthview-core/
│   ├── wealthview-persistence/
│   ├── wealthview-import/
│   ├── wealthview-projection/
│   └── wealthview-app/
├── frontend/                  (React + Vite web SPA — npm workspace)
│   ├── package.json
│   ├── e2e/                   (Playwright specs: a11y, visual regression, flows)
│   └── src/
├── mobile/                    (React Native app — npm workspace)
├── shared/                    (@wealthview/shared — cross-platform utilities)
├── bin/                       (wv dispatcher + wv-lib/ subcommand libraries)
├── docs/                      (user guide, deployment, administration, reference)
├── loadtest/                  (k6 scenarios, profiling, result reports)
├── .github/workflows/         (six tag-triggered CI workflows)
├── package.json               (npm workspaces root + cross-workspace scripts)
├── docker-compose.yml         (dev stack)
├── docker-compose.prod.yml    (prod stack: db, app, nightly backup)
├── docker-compose.observability.yml
├── Dockerfile
├── wv                         (thin shim → bin/wv)
├── CHANGELOG.md
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

### Valuation

The dashboard uses the most recent price per symbol to compute market value and
net worth, falling back to cost basis when no price exists. Prices arrive from
the scheduled Finnhub sync (`source = finnhub`), from Yahoo/CSV admin imports, or
from manual entry (`source = manual`) — manual entry was the Phase 1 mechanism
and remains available as an override and as the escape hatch for unpriced
symbols. Money-market symbols with no price data (e.g. SPAXX) are skipped rather
than zeroed.

Historical prices are kept split-adjusted automatically; see
[Stock Splits](docs/operations/stock-splits.md).

---

## Data Model (Core Entities)

All `id` columns are **UUID**. Tenant-owned tables carry a `tenant_id` foreign
key for row-level isolation. Reference/seed tables (tax brackets, standard
deductions, LTCG brackets, IRMAA tiers, asset class returns, mortality rates,
security asset classes, stock splits) are global rather than tenant-scoped.

The tables below are the load-bearing ones. The authoritative schema is the
Flyway migration set — **89 files** (`V001`..`V080` plus 9 `R__seed_*`
repeatables) in `backend/wealthview-persistence/src/main/resources/db/migration/`
— and the JPA entities in `wealthview-persistence`. See
[Data Model reference](docs/reference/data-model.md) for the full listing.

### Tenancy & Auth

| Table                   | Key Columns                                                                              |
| ----------------------- | ---------------------------------------------------------------------------------------- |
| `tenants`               | `id`, `name`, `created_at`                                                               |
| `users`                 | `id`, `tenant_id`, `email`, `password_hash`, `role` (admin, member, viewer), MFA secret/enabled flags |
| `invite_codes`          | `id`, `tenant_id`, `code` (unique), `created_by`, `consumed_by`, `consumed_at`, `expires_at` |
| `refresh_tokens`        | `id`, `user_id`, hashed token, `expires_at`, revocation state                             |
| `user_sessions`         | `id`, `user_id`, device/user-agent metadata, `last_seen_at`                               |
| `mfa_recovery_codes`    | `id`, `user_id`, hashed single-use recovery code                                          |
| `mfa_challenges`        | `id`, `user_id`, short-lived challenge issued between password and TOTP step              |
| `login_activity`        | `id`, `user_id`, outcome, source metadata, `created_at`                                   |
| `audit_log`             | `id`, `tenant_id`, actor, entity type/id, action, `created_at`                            |
| `system_config`         | `id`, `key`, `value` — runtime-editable configuration surfaced under `/admin`             |

### Portfolio / Investments

| Table                     | Key Columns                                                                     |
| ------------------------- | ------------------------------------------------------------------------------- |
| `accounts`                | `id`, `tenant_id`, `name`, `type` (brokerage, ira, 401k, roth, bank), `institution`, `currency` |
| `holdings`                | `id`, `account_id`, `symbol`, `quantity`, `cost_basis`, `is_manual_override`, `as_of_date` |
| `transactions`            | `id`, `account_id`, `date`, `type` (buy, sell, dividend, deposit, withdrawal), `symbol`, `amount`, `quantity`, `import_hash` |
| `prices`                  | `symbol`, `date`, `close_price`, `source` (manual, finnhub, …) — daily cache for historical charts |
| `exchange_rates`          | `id`, `tenant_id`, `currency_code`, `rate` — tenant-managed manual rates for non-base currencies |
| `stock_splits`            | `id`, `symbol`, `split_date`, numerator/denominator ratio, detection source     |
| `stock_split_adjustments` | `id`, `stock_split_id`, affected entity — the audit trail that makes a split un-appliable |
| `security_asset_class`    | `symbol` → asset class (global classification, seeded)                          |
| `security_class_override` | `id`, `tenant_id`, `symbol` → asset class (per-tenant override)                 |

### Rental Properties

| Table                            | Key Columns                                                                  |
| -------------------------------- | ---------------------------------------------------------------------------- |
| `properties`                     | `id`, `tenant_id`, `address`, `purchase_price`, `purchase_date`, `current_value`, `mortgage_balance`, `annual_appreciation_rate`, `annual_property_tax`, `annual_insurance_cost` |
| `property_income`                | `id`, `property_id`, `date`, `amount`, `category` (rent, other)              |
| `property_expenses`              | `id`, `property_id`, `date`, `amount`, `category` (mortgage, tax, insurance, maintenance, capex, hoa, mgmt_fee) |
| `property_valuations`            | `id`, `property_id`, `as_of_date`, `value`, source (Zillow scrape or manual)  |
| `property_depreciation_schedule` | `id`, `property_id`, per-year depreciation incl. cost-segregation asset classes and bonus depreciation |

### Retirement Projections

| Table                          | Key Columns                                                                        |
| ------------------------------ | ---------------------------------------------------------------------------------- |
| `projection_scenarios`         | `id`, `tenant_id`, `name`, `retirement_date`, `end_age`, `inflation_rate`, `params_json`, `spending_profile_id`, `guardrail_profile_id` |
| `projection_accounts`          | `id`, `scenario_id`, `linked_account_id`, `initial_balance`, `annual_contribution`, `expected_return`, allocation weights, cost basis, `owner` |
| `spending_profiles`            | `id`, `tenant_id`, `name`, `spending_tiers` (jsonb) — age-banded spending phases    |
| `guardrail_spending_profiles`  | `id`, `scenario_id`, Monte-Carlo-optimized yearly spending, risk tolerance, essential floor, `gate_on_adaptive_rules` |
| `income_sources`               | `id`, `tenant_id`, type, amount, start/end age, tax treatment, `owner`, `survivor_percent` |
| `scenario_income_sources`      | join table linking scenarios to the income sources they include                    |

`params_json` is `jsonb` (mapped with `@JdbcTypeCode(SqlTypes.JSON)`) and stores
flexible scenario parameters — withdrawal rate, Roth conversion settings, Social
Security start age, spouse/household details, investment fee rate, and so on.

**Spending plans are one concept, two implementations.** `SpendingPlan` is a
sealed interface with exactly two permitted forms: `TierBasedSpendingPlan`
(wrapping a `spending_profiles` row) and `GuardrailSpendingInput` (wrapping a
`guardrail_spending_profiles` row). A scenario has **at most one** active plan —
`spending_profile_id` and `guardrail_profile_id` are mutually exclusive, setting
one clears the other, and clearing both falls back to a withdrawal-rate strategy.
The UI presents a single unified "Spending Plan" dropdown.

### Tax & Actuarial Reference Data (global, seeded)

| Table                       | Purpose                                                             |
| --------------------------- | ------------------------------------------------------------------- |
| `tax_brackets`              | Federal ordinary-income brackets by year and filing status          |
| `standard_deductions`       | Federal standard deduction by year and filing status, incl. age-65 addition |
| `ltcg_brackets`             | Long-term capital gains brackets                                    |
| `irmaa_tiers`               | Medicare IRMAA surcharge tiers                                      |
| `state_tax_brackets`, `state_standard_deductions`, `state_tax_surcharges` | State income tax modeling         |
| `asset_class_returns`       | Per-asset-class return/volatility assumptions driving real-terms returns |
| `mortality_rates`           | SSA `qx` tables backing the opt-in stochastic mortality mode        |

### Import & Platform

| Table                 | Key Columns                                                                 |
| --------------------- | --------------------------------------------------------------------------- |
| `import_jobs`         | `id`, `tenant_id`, `source` (csv, ofx, manual), `status`, `started_at`, `completed_at`, `error_message` |
| `notification_preferences` | `id`, `user_id`, `notification_type`, `enabled`                        |
| `mobile_app_versions` | `platform`, minimum/latest supported version — drives the mobile force-upgrade check |

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

- [x] **Multi-tenant admin panel** — Manage tenants, view usage stats, disable accounts.
- [x] **Audit log** — Record all data mutations per tenant for debugging and compliance.
- [x] **Data export** — Full tenant data export as JSON, plus per-entity CSV (accounts, transactions, holdings, properties) with spreadsheet formula-injection neutralization.
- [x] **Notification preferences** — Per-user, per-type notification preferences are stored and editable via `/api/v1/notifications/preferences`. **No delivery channel is implemented** — there is no mail sender in the codebase, so nothing is actually sent yet. Email/webhook delivery remains future work (see Roadmap).
- [x] **HTTPS & hardening** — Host-managed TLS (nginx + Let's Encrypt, or a Cloudflare Tunnel), rate limiting, CSRF protection, HSTS, frame-options and Content-Security-Policy headers, plus a `ProductionConfigValidator` that refuses to boot prod with sentinel defaults.

### Phase 5 — Advanced Planning

- [x] **Monte Carlo guardrail spending optimizer** — Block-bootstrap return paths; solves for the sustainable spending level at a target success probability. Risk tolerance *is* the target success probability: conservative 0.95, moderate 0.90, aggressive 0.80.
- [x] **Spending tiers / phases** — Age-banded spending profiles with per-tier inflation reset (go-go / slow-go / no-go modeling).
- [x] **Roth conversion optimizer** — Joint spending-and-conversion optimization against a target balance, with inflation-indexed brackets and rental-loss integration.
- [x] **Dynamic withdrawal sequencing** — Per-year, tax-aware pool selection rather than a fixed drawdown order, with per-pool withdrawal transparency in the UI.
- [x] **Property hold-vs-sell ROI** — Per-income-source comparison of holding versus selling, including depreciation recapture and capital gains tax.
- [x] **Cost-segregation depreciation** — Structured asset-class allocations, bonus depreciation, and 481(a) catch-up, with a visible schedule breakdown.
- [x] **Multi-currency accounts** — Per-account currency with a tenant-scoped `exchange_rates` table; conversion applied at display and aggregation boundaries.

### Phase 6 — Projection Realism v2

- [x] **Allocation-driven real-terms returns** — Per-account allocation weights resolve against seeded asset-class return assumptions; projections run in real (inflation-adjusted) terms.
- [x] **Essential-spending-floor success metric** — Success is measured against a user-defined essential floor rather than pure portfolio survival; presets recalibrated accordingly.
- [x] **RMDs in the main projection** — Required minimum distributions are computed per owner and applied in the deterministic engine, not just in the optimizer.
- [x] **Capital gains on taxable accounts** — Per-lot FIFO cost basis tracking, long-term capital gains brackets plus NIIT, and a dividend drag on taxable pools.
- [x] **Investment fee drag** — A scenario-level all-in annual fee / expense-ratio assumption is subtracted from returns in both engines.
- [x] **Frontend surface for all of it** — Allocation editor, cost basis entry, dividend yield, holdings reclassification, success-probability display, and per-year RMD and capital-gains breakdowns.

### Phase 7 — Household & Survivor Modeling

- [x] **Spouse and owner-aware pools** — Accounts and income sources carry an `owner`; both engines maintain per-owner pools.
- [x] **Per-owner RMD streams** — RMDs computed independently per person against their own age and balances.
- [x] **First-death transition** — An atomic transition at first death: keep the larger Social Security benefit, spousal rollover of retirement accounts, cost-basis step-up, survivor spending scaled down, and a MFJ → single filing-status flip with per-person thresholds.
- [x] **Owner-age income windows** — Income source start/end ages resolve against the correct person's age.
- [x] **Stochastic mortality (opt-in)** — A success-probability-only Monte Carlo mode drawing per-trial mortality from seeded SSA `qx` tables on a separate RNG stream, with longevity-conditional metrics. Off by default.

### Phase 8 — Platform & Operations Maturity

- [x] **Automatic stock split handling** — Daily Finnhub split sync plus a one-time backfill keep transactions, holdings, and historical prices split-adjusted; every adjustment is recorded so a split can be un-applied. Manual entry under `/api/v1/admin/stock-splits`.
- [x] **Account security** — TOTP MFA with encrypted secrets and single-use recovery codes, refresh tokens, active-session listing and revocation, and login activity history.
- [x] **Consolidated admin area** — A single `/admin` page absorbing tenant settings, prices, audit log, and system stats; `/settings`, `/audit-log`, and `/admin/prices` redirect there.
- [x] **Mobile companion app** — React Native client (login, portfolio, account detail, settings, server config) against a token-auth endpoint family, with a version-check / force-upgrade gate. Shares API and formatting code via the `shared/` workspace.
- [x] **`wv` operations tool** — A single admin command surface (`up`, `down`, `restart`, `status`, `logs`, `psql`, `backup`, `backups`, `restore`, `verify`, `update`, `rollback`, `migrate-out`, `migrate-in`, `rotate-secret`, `config-check`, `help`) installable system-wide and able to drive a remote Docker host over SSH.
- [x] **Enforced quality gates** — PMD, CPD, SpotBugs, Checkstyle, and JaCoCo all fail `mvn verify`, with per-module line and branch floors. PIT mutation testing available as an advisory tool.
- [x] **Load test harness & observability** — k6 scenarios with profiling and report generation under `loadtest/`, a Micrometer/Actuator Prometheus endpoint, and an optional observability compose stack.
- [x] **Scaling work** — Batch balance computation replacing N+1 queries, Caffeine caching across five named caches, and a tuned HikariCP pool.

### Roadmap — Not Yet Built

- [ ] **Notification delivery** — A real channel (email/SMTP or webhook) behind the existing preference model, for sync failures, large transactions, and projection milestones.
- [ ] **Stochastic inflation** — Inflation is currently a fixed per-scenario rate; modeling it as a correlated stochastic process was scoped during the realism work but never selected.
- [ ] **Extended tail-risk return window** — Widening the bootstrap sample to a 1972–2025 historical window to capture stagflation-era sequences.
- [ ] **Automated bank/brokerage sync** — Still deliberately out of scope; import stays file-based (see Decision Log #4).

---

## API Design Conventions

- **Base path:** `/api/v1/`
- **Auth:** `Authorization: Bearer <JWT>` on all endpoints except the unauthenticated entry points (`/api/v1/auth/login`, `/register`, `/refresh`, `/mfa/challenge` and their `/api/v1/auth/token/*` mobile equivalents).
- **Tenant scoping:** The JWT contains `tenant_id`; a Spring Security filter enforces row-level access on every query.
- **Pagination:** `?page=0&size=25` with response envelope `{ data: [], page: 0, size: 25, total: 142 }`.
- **Errors:** Standard JSON error body `{ error: "NOT_FOUND", message: "Account not found", status: 404 }`.
- **Naming:** snake_case for JSON fields, matching PostgreSQL column conventions.

### Example Endpoints

A representative slice. The API is 28 controllers and roughly 136 mappings —
see the [API Reference](docs/reference/api-reference.md) for the full surface.

```
# Auth & Registration
POST   /api/v1/auth/register             (email, password, invite code)
POST   /api/v1/auth/login
POST   /api/v1/auth/mfa/challenge        (second factor after password)
POST   /api/v1/auth/refresh
POST   /api/v1/auth/logout
GET    /api/v1/auth/me
POST   /api/v1/auth/token/login          (mobile token-auth family)

# Account security
POST   /api/v1/auth/mfa/setup            (also verify-setup, disable, regenerate-recovery-codes)
GET    /api/v1/auth/mfa/status
GET    /api/v1/auth/sessions             (DELETE /{id} to revoke one, DELETE to revoke all)

# Tenant & User Management (admin only)
POST   /api/v1/tenant/invite-codes       (generate invite code)
GET    /api/v1/tenant/users
PUT    /api/v1/tenant/users/{id}/role    (change role)
DELETE /api/v1/tenant/users/{id}

# Super-admin
POST   /api/v1/admin/tenants             (create tenant)
GET    /api/v1/admin/tenants
GET    /api/v1/admin/system-stats
GET    /api/v1/admin/login-activity
GET    /api/v1/admin/config              (PUT /config/{key} to update)
GET    /api/v1/audit-log                 (admin or super-admin)

# Accounts & Portfolio
GET    /api/v1/accounts
POST   /api/v1/accounts
PUT    /api/v1/accounts/{id}
DELETE /api/v1/accounts/{id}
GET    /api/v1/accounts/{id}/holdings
GET    /api/v1/accounts/{id}/transactions
POST   /api/v1/accounts/{id}/transactions
GET    /api/v1/accounts/{id}/theoretical-history

# Holdings (manual override)
PUT    /api/v1/holdings/{id}             (manual override)
POST   /api/v1/holdings                  (manual create)

# Prices, splits & classification
POST   /api/v1/prices                    (symbol, date, close_price)
GET    /api/v1/prices/{symbol}/latest
POST   /api/v1/admin/prices/sync         (on-demand Finnhub sync)
GET    /api/v1/stock-splits
POST   /api/v1/admin/stock-splits        (manual entry; DELETE /{id} un-applies)
POST   /api/v1/admin/stock-splits/sync
PUT    /api/v1/securities/{symbol}/classification

# Multi-currency
GET    /api/v1/exchange-rates            (POST, PUT /{currencyCode}, DELETE /{currencyCode})

# File Import
POST   /api/v1/import/csv                (multipart upload)
POST   /api/v1/import/positions          (multipart upload, positions/holdings snapshot)
POST   /api/v1/import/ofx                (multipart upload, OFX/QFX)
GET    /api/v1/import/jobs               (import history)

# Properties
GET    /api/v1/properties
POST   /api/v1/properties
PUT    /api/v1/properties/{id}
DELETE /api/v1/properties/{id}
GET    /api/v1/properties/{id}/cashflow?from=2025-01&to=2025-12
GET    /api/v1/properties/{id}/cashflow-detail
GET    /api/v1/properties/{id}/analytics             (cap rate, cash-on-cash, equity)
GET    /api/v1/properties/{id}/depreciation-schedule
GET    /api/v1/properties/{id}/valuations            (POST /valuations/refresh to rescrape)
POST   /api/v1/properties/{id}/income
POST   /api/v1/properties/{id}/expenses
GET    /api/v1/properties/{propertyId}/income-sources/{sourceId}/roi-analysis

# Projections & spending plans
GET    /api/v1/projections
POST   /api/v1/projections
GET    /api/v1/projections/{id}
GET    /api/v1/projections/{id}/run
POST   /api/v1/projections/compare
POST   /api/v1/projections/{scenarioId}/optimize     (Monte Carlo guardrail optimization)
GET    /api/v1/projections/{scenarioId}/guardrail    (DELETE to clear, POST /reoptimize to rerun)
GET    /api/v1/spending-profiles
GET    /api/v1/income-sources

# Dashboard & export
GET    /api/v1/dashboard/summary
GET    /api/v1/dashboard/portfolio-history
GET    /api/v1/dashboard/snapshot-projection
GET    /api/v1/export/json
GET    /api/v1/export/csv/accounts       (also /transactions, /holdings, /properties)
```

---

## Frontend Pages

All authenticated pages are lazy-loaded (`React.lazy` + `<Suspense>`) inside a
shared `<Layout>`. Global providers: `AuthProvider` and `ProjectionCacheProvider`.
See the [Frontend Routes reference](docs/reference/frontend-routes.md) for the
full table.

| Route                          | Page                  | Description                                                              | Roles          |
| ------------------------------ | --------------------- | ------------------------------------------------------------------------ | -------------- |
| `/login`                       | Login                 | Email + password form, with the TOTP challenge step when MFA is enabled  | Public         |
| `/register`                    | Register              | Email, password, invite code; creates user and logs in                   | Public         |
| `/`                            | Dashboard             | Net worth summary, portfolio history chart, allocation breakdown         | All            |
| `/accounts`                    | Accounts List         | Table of all accounts with type, institution, balance                    | All            |
| `/accounts/:id`                | Account Detail        | Holdings table, transaction history, add transaction form                | All (write: admin, member) |
| `/accounts/:id/import`         | Import                | Upload CSV/OFX, preview parsed rows, confirm import                      | Admin, Member  |
| `/holdings/:id`                | Holding Detail        | Transaction history for symbol, manual override form                     | All (write: admin, member) |
| `/prices`                      | Prices                | Held symbols with latest price; inline edit for manual entry             | Admin, Member  |
| `/projections`                 | Projections           | Scenario list and creation                                               | All            |
| `/projections/compare`         | Projection Compare    | Side-by-side scenario comparison                                         | All            |
| `/projections/:id`             | Projection Detail     | Year-by-year results, per-pool withdrawals, RMDs, capital gains          | All            |
| `/projections/:id/optimize`    | Spending Optimizer    | Monte Carlo guardrail optimization and near-term spending guide          | Admin, Member  |
| `/spending-profiles`           | Spending Profiles     | Age-banded spending tiers                                                | All (write: admin, member) |
| `/income-sources`              | Income Sources        | Social Security, pensions, rental and other income, with owner/survivor  | All (write: admin, member) |
| `/properties`                  | Properties List       | Cards or table of all properties with summary metrics                    | All            |
| `/properties/:id`              | Property Detail       | Income/expense log, cash flow chart, analytics, depreciation, ROI        | All (write: admin, member) |
| `/admin`                       | Admin Area            | Consolidated: tenant settings, invite codes, users, prices, audit log, system stats | Admin |
| `/export`                      | Data Export           | Full-tenant JSON export and per-entity CSV downloads                     | All            |

`/settings`, `/audit-log`, and `/admin/prices` are kept as permanent redirects to
`/admin` so old links and bookmarks still resolve.

---

## Deployment

### Docker Compose

A multi-stage `Dockerfile` builds the frontend (node 24-alpine) and the backend
(maven + Eclipse Temurin 25), then ships a single JRE image that serves both the
REST API and the built SPA. All base images are pinned by digest. There is **no
nginx service in the compose stack** — TLS termination is a host concern
(nginx + Let's Encrypt, or a Cloudflare Tunnel; see the deployment docs).

| File | Services | Notes |
| ---- | -------- | ----- |
| `docker-compose.yml` | `db`, `app` | Dev stack, `docker` profile. DB published on `5433:5432` to avoid clashing with a native PostgreSQL; app on `80:8080`. |
| `docker-compose.prod.yml` | `db`, `app`, `backup` | Prod stack, `prod` profile. Uses the pinned image `wealthview:${WEALTHVIEW_VERSION}` — never `:latest`. Adds a nightly backup container with `BACKUP_RETENTION_DAYS`, plus `CORS_ORIGIN` and `APP_PORT`. `restart: unless-stopped` throughout. |
| `docker-compose.observability.yml` | optional | Metrics/tracing/profiling side stack. |

Required environment (all from `.env`, never committed): `DB_PASSWORD`,
`JWT_SECRET`, `SUPER_ADMIN_PASSWORD`, `MFA_ENCRYPTION_KEY`. Optional:
`FINNHUB_API_KEY`, `ZILLOW_ENABLED`. Setting `WEALTHVIEW_VERSION` is what flips
`./wv` from dev mode into prod mode.

### Operations

Day-to-day operation goes through `./wv` rather than raw `docker compose` — one
dispatcher (`bin/wv`) with subcommand libraries in `bin/wv-lib/`, installable
system-wide on a server so the source tree isn't needed on the host, and able to
drive a remote Docker host over SSH. It covers lifecycle (`up`, `down`,
`restart`, `status`, `logs`, `psql`), data safety (`backup`, `backups`,
`restore`, `verify`, `migrate-out`, `migrate-in`), and change management
(`update` with pre-update backup and health-check auto-rollback, `rollback`,
`rotate-secret`, `config-check`). See the
[Operations Handbook](docs/deployment/operations.md).

### Database Migrations

Use Flyway, applied automatically on application startup. Migration files live in
`backend/wealthview-persistence/src/main/resources/db/migration/` — currently 89
files: versioned `V001`..`V080` plus 9 `R__seed_*` repeatables carrying the tax,
LTCG, IRMAA, standard-deduction, asset-class-return, mortality, security-class,
and price seed data. Versioned migrations are immutable once committed.

---

## Decision Log

| # | Decision | Status | Notes |
|---|----------|--------|-------|
| 1 | ORM choice: Spring Data JPA + Hibernate | **Decided** | Battle-tested, familiar; Hibernate auto-configured via Spring Boot |
| 2 | Application framework: Spring Boot | **Decided** | Replaces raw servlet/Tomcat approach; auto-config for JPA, Security, Scheduling; fat JAR deployment |
| 3 | Price data provider: Finnhub | **Decided** | Free tier (60 req/min) more than sufficient; no licensing restrictions for multi-tenant display |
| 4 | Bank/brokerage data ingestion: CSV + OFX file import | **Decided** | No Plaid — avoids cost, complexity, and production review process; OFX4J for standardized parsing. The originally planned user-defined CSV column mapping was superseded by brokerage-specific parsers (Fidelity, Vanguard, Schwab). Plaid can be revisited if the app scales beyond friends & family |
| 5 | Primary keys: UUID | **Decided** | Prevents information leakage in URLs; simplifies data portability and future imports |
| 6 | Registration: invite-code gated | **Decided** | No open registration; admin generates single-use invite codes per tenant |
| 7 | User roles: admin / member / viewer | **Decided** | Three-tier RBAC scoped per tenant; enforced at Spring Security filter level |
| 8 | Holdings computation: auto-compute + manual override | **Decided** | Holdings auto-recomputed from transactions; manual override supported with conflict warnings |
| 9 | Phase 1 valuation: manual price entry | **Superseded** | Shipped as designed, then Phase 2's Finnhub feed became the primary source; manual entry remains as an override |
| 10 | Repository structure: monorepo | **Decided** | Single repo. Now also an npm workspaces monorepo (`shared`, `frontend`, `mobile`) alongside the Maven backend; separate dev and prod compose files |
| 11 | Multi-tenant isolation strategy: row-level | **Decided** | Row-level (`tenant_id` FK) — simpler ops for small tenant count |
| 12 | Frontend state management: Context API vs. Zustand vs. Redux | **Decided** | React Context + custom hooks (`useApiQuery`, `useApiMutation`, `useCrudForm`) proved sufficient; no external state library was needed |
| 13 | Cross-platform code sharing: `@wealthview/shared` workspace | **Decided** | API client, formatting, and portfolio math live in one workspace consumed by both the web SPA and the React Native app, rather than being duplicated |
| 14 | Spending plans: one sealed interface, two implementations | **Decided** | Tier-based and guardrail-optimized spending are the same concept; a scenario holds at most one, enforced by mutually exclusive FK columns |
| 15 | Projections run in real (inflation-adjusted) terms | **Decided** | Returns resolve from per-account allocation against seeded asset-class assumptions, net of an investment fee drag; avoids the nominal/real mixing that biased earlier results |
| 16 | Quality gates are build-failing, not advisory | **Decided** | PMD, CPD, SpotBugs, Checkstyle, and JaCoCo fail `mvn verify` with per-module coverage floors. PIT stays advisory — useful for finding weak tests, too noisy to gate on |
| 17 | CI triggers on release tags only | **Decided** | Solo project with a strong local pre-commit gate; tag-triggered workflows keep signal high. No auto-deploy — deployment is an explicit `./wv update` on the server |

---

## Non-Functional Requirements

- **Backup:** Nightly `pg_dump` from the prod stack's backup container, with retention; on-demand `./wv backup` (optionally age-encrypted), `./wv verify` for round-trip validation, and `./wv migrate-out` / `migrate-in` for moving hosts.
- **Performance:** Dashboard loads in < 2s for accounts with up to 10,000 transactions. Backed by batch balance computation, Caffeine caching, and a tuned HikariCP pool; validated with the k6 harness in `loadtest/`.
- **Security:** All secrets in environment variables; no credentials in source control, enforced by a gitleaks pre-commit hook and a CI scan. Passwords hashed with bcrypt. TOTP MFA with encrypted secrets, refresh tokens, revocable sessions, rate limiting, CSRF protection, and a strict Content-Security-Policy. `ProductionConfigValidator` refuses to boot prod if a sentinel default leaked through.
- **Observability:** Structured JSON logging (SLF4J + Logback) in prod, plain text in dev; Micrometer metrics at `/actuator/prometheus`; an optional observability compose stack.
- **Compatibility:** Targets modern evergreen browsers (Chrome, Firefox, Safari, Edge). Accessibility and visual regression are covered by Playwright specs in `frontend/e2e/`.

---

## Getting Started (Dev)

```bash
# Prerequisites: Java 25 (backend/.sdkmanrc pins 25.0.3-tem), Maven 3.9+,
#                Node 22+, Docker with the Compose plugin

# Install JS workspace deps once at the repo root (shared, frontend, mobile)
npm install

# Start Postgres (published on localhost:5433, not 5432)
docker compose up -d db

# Backend
cd backend
mvn clean install
mvn -pl wealthview-app spring-boot:run

# Frontend (separate terminal) — dev server on :5173, backend on :8080
cd frontend
npm run dev
```

To run the whole stack in containers instead, use `./wv up` (http://localhost,
`docker` profile, demo user `demo@wealthview.local` / `demo123`) and `./wv down`
to stop.

### Verification

```bash
cd backend
mvn verify -DskipITs      # unit + @DataJpaTest tests and all five quality gates
mvn verify                # the above plus the Testcontainers integration suite

npm run test:all          # Vitest (shared, frontend) + Jest (mobile)
npm run typecheck:all     # tsc --noEmit across all workspaces
```

Backend development is **TDD-first** (red → green → refactor) — see `CLAUDE.md`
for the full contributor conventions, including commit format, layering rules,
and the secrets policy.

---

## License

Private / personal use. Not open-sourced at this time.
