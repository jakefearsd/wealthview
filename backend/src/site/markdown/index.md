# WealthView — Personal Finance Platform

WealthView is a **self-hosted, multi-tenant personal finance application** designed for financially
literate users who want full ownership of their data. A single deployment serves multiple independent
households (tenants), each with fully isolated data.

---

## What it Does

| Domain | Capability |
|---|---|
| **Portfolio** | Track brokerage, IRA, 401k, Roth, and bank accounts; auto-compute holdings from transactions |
| **Properties** | Log rental income and expenses; track equity, cap rate, cash-on-cash return, depreciation, hold-vs-sell ROI |
| **Retirement** | Year-by-year deterministic and Monte Carlo projections with allocation-driven real returns, RMDs, capital gains, IRMAA, and household/survivor modeling |
| **Roth Optimization** | Lifetime tax-minimizing conversion schedule with rental loss integration |
| **Import** | Parse CSV exports from Fidelity, Vanguard, and Schwab; OFX/QFX files from any institution |
| **Prices** | Daily Finnhub price feed with historical backfill (Finnhub + Yahoo Finance); manual entry fallback |
| **Stock Splits** | Auto-detected daily from Finnhub, applied to transactions, holdings, and historical prices |
| **Multi-Currency** | Per-account currency with tenant-scoped exchange rates applied at display time |
| **Security** | JWT + cookie transports, TOTP MFA with recovery codes, per-device sessions, audit log |

---

## Technology Summary

| Layer | Technology |
|---|---|
| Frontend | React 19, Vite, TypeScript, Recharts, Axios |
| Backend | Java 25, Spring Boot 4.1.0, Spring Security, Spring Data JPA |
| JSON | Jackson 3 (`tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation`) |
| Database | PostgreSQL 16, Flyway 13 (80 versioned + 9 repeatable migrations), Hibernate 7 |
| Auth | JWT bearer tokens + CSRF-protected cookies, bcrypt (strength 12), TOTP MFA, invite-code registration |
| Import | Apache Commons CSV 1.14.1, OFX4J 1.39, jsoup 1.23.1 |
| Caching | Caffeine 3.2.4 — five named caches, Micrometer-instrumented |
| Scheduling | Spring `@Scheduled` — weekday price sync, nightly stock-split sync, weekly Zillow valuation |
| Observability | Micrometer + Prometheus, OpenTelemetry tracing bridge, JSON logging via logstash-logback-encoder |
| Testing | JUnit 5, Mockito (`@MockitoBean`), AssertJ, Testcontainers 2.0.5 (PostgreSQL 16) |
| Build | Maven (multi-module), Node 24 / npm workspaces |
| Deployment | Docker Compose (single fat JAR + PostgreSQL) |

---

## Module Overview

The backend is structured as a Maven multi-module project with strict one-directional
dependency enforcement. See the [Architecture](architecture.html) page for the full dependency
diagram and layer responsibilities.

```
wealthview-app          ← assembles everything; Spring Boot main class
  ├─ wealthview-api     ← REST controllers, Security config, filters
  ├─ wealthview-import  ← CSV/OFX parsers, Finnhub, Yahoo, Zillow
  └─ wealthview-projection ← deterministic + Monte Carlo retirement engines
       └─ wealthview-core  ← services, DTOs, tax model, domain interfaces
            └─ wealthview-persistence ← JPA entities, repositories, migrations
```

---

## Key Design Decisions

**UUID primary keys everywhere.** All entity IDs are UUIDs (`gen_random_uuid()` in PostgreSQL).
This prevents information leakage via sequential IDs in URLs and simplifies future data imports.

**Row-level multi-tenancy, defended twice.** Nearly every table carries a `tenant_id` foreign
key (the shared reference tables — `prices`, tax brackets, asset-class returns, mortality
rates — do not). A Spring Security filter extracts the tenant from the JWT and every repository
query filters by it. As a backstop, `TenantFilterAspect` enables a Hibernate `tenantFilter` on
the transaction-bound session for every `@Transactional` service method, catching query-shaped
mistakes that the explicit filtering might miss.

**Integrations are inverted.** `wealthview-core` owns the contracts (`ImportParser`,
`PriceFeedClient`, `SplitDetectionClient`, `ProjectionEngine`, `SpendingOptimizer`); the
implementations live in `wealthview-import` and `wealthview-projection`. Business code never
compiles against an HTTP client or a file parser.

**Holdings auto-computed from transactions.** When a transaction is created, updated, or deleted,
the `HoldingsComputationService` reaggregates all transactions for that account+symbol pair.
Manual overrides are supported and flagged with `is_manual_override = true`.

**One spending plan per scenario.** `SpendingPlan` is a sealed interface with exactly two
implementations — `TierBasedSpendingPlan` (user-defined age tiers) and `GuardrailSpendingInput`
(Monte-Carlo-optimized yearly spending). `projection_scenarios.spending_profile_id` and
`.guardrail_profile_id` are mutually exclusive, enforced on the entity itself; clearing both
falls back to a withdrawal-rate strategy.

**Invite-code gated registration.** There is no open self-registration. Admins generate
single-use invite codes per tenant; new users must supply a valid code to register.

**`BigDecimal` / `numeric(19,4)` for all money.** Floating-point types are never used for
monetary amounts anywhere in the stack.

**Projections run in real terms.** Every cash flow is expressed in today's dollars, so a
user-supplied nominal expected return is deflated by the scenario's own inflation rate before
it reaches the engine.

---

## Quick Links

* [Architecture](architecture.html) — module dependency graph, layer responsibilities
* [Data Model](data-model.html) — JPA entities and migration inventory
* [REST API Guide](api-guide.html) — endpoint reference by domain
* [Projection Engine](projection-engine.html) — deterministic, Monte Carlo, Roth optimizer
* [Development Guide](development-guide.html) — local setup, TDD workflow, Docker Compose
* [Code Quality](code-quality.html) — JaCoCo, SpotBugs, Checkstyle, PMD, Pitest
