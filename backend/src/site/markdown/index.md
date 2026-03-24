# WealthView — Personal Finance Platform

WealthView is a **self-hosted, multi-tenant personal finance application** designed for financially
literate users who want full ownership of their data. A single deployment serves multiple independent
households (tenants), each with fully isolated data.

---

## What it Does

| Domain | Capability |
|---|---|
| **Portfolio** | Track brokerage, IRA, 401k, Roth, and bank accounts; auto-compute holdings from transactions |
| **Properties** | Log rental income and expenses; track equity, cap rate, cash-on-cash return |
| **Retirement** | Year-by-year deterministic and Monte Carlo retirement projections with tax modeling |
| **Roth Optimization** | Lifetime tax-minimizing conversion schedule with rental loss integration |
| **Import** | Parse CSV exports from Fidelity, Vanguard, and Schwab; OFX/QFX files from any institution |
| **Prices** | Daily Finnhub price feed with 2-year historical backfill; manual entry fallback |

---

## Technology Summary

| Layer | Technology |
|---|---|
| Frontend | React 18, Vite, TypeScript, Recharts, Axios |
| Backend | Java 21, Spring Boot 3.3, Spring Security, Spring Data JPA |
| Database | PostgreSQL 16, Flyway (50 migrations), Hibernate 6 |
| Auth | JWT bearer tokens, bcrypt password hashing, invite-code registration |
| Import | Apache Commons CSV 1.14, OFX4J 1.7, jsoup 1.21 |
| Scheduling | Spring `@Scheduled` — daily price sync, weekly Zillow valuation |
| Testing | JUnit 5, Mockito, AssertJ, Testcontainers (PostgreSQL 16) |
| Build | Maven 3.9 (multi-module), Node 20 / npm |
| Deployment | Docker Compose (single fat JAR + PostgreSQL) |

---

## Module Overview

The backend is structured as a Maven multi-module project with strict one-directional
dependency enforcement. See the [Architecture](architecture.html) page for the full dependency
diagram and layer responsibilities.

```
wealthview-app          ← assembles everything; Spring Boot main class
  ├─ wealthview-api     ← REST controllers, Security config
  ├─ wealthview-import  ← CSV/OFX parsers, Finnhub, Zillow
  └─ wealthview-projection ← deterministic + MC retirement engine
       └─ wealthview-core  ← services, DTOs, domain interfaces
            └─ wealthview-persistence ← JPA entities, repositories, migrations
```

---

## Key Design Decisions

**UUID primary keys everywhere.** All entity IDs are UUIDs (`gen_random_uuid()` in PostgreSQL).
This prevents information leakage via sequential IDs in URLs and simplifies future data imports.

**Row-level multi-tenancy.** Every table (except `tenants` and `prices`) carries a `tenant_id`
foreign key. A Spring Security filter extracts the tenant from the JWT and every repository
query filters by it — there is no shared data pathway between tenants.

**Holdings auto-computed from transactions.** When a transaction is created, updated, or deleted,
the `HoldingsComputationService` reaggregates all transactions for that account+symbol pair.
Manual overrides are supported and flagged with `is_manual_override = true`.

**Invite-code gated registration.** There is no open self-registration. Admins generate
single-use invite codes per tenant; new users must supply a valid code to register.

**`BigDecimal` / `numeric(19,4)` for all money.** Floating-point types are never used for
monetary amounts anywhere in the stack.

---

## Quick Links

* [Architecture](architecture.html) — module dependency graph, layer responsibilities
* [Data Model](data-model.html) — 27 JPA entities across 8 domains
* [REST API Guide](api-guide.html) — endpoint reference by domain
* [Projection Engine](projection-engine.html) — deterministic, Monte Carlo, Roth optimizer
* [Development Guide](development-guide.html) — local setup, TDD workflow, Docker Compose
* [Code Quality](code-quality.html) — JaCoCo, SpotBugs, Checkstyle, PMD, Pitest
