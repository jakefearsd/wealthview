# WealthView

A self-hosted, multi-tenant personal finance application for tracking investment portfolios, rental properties, and retirement projections.

## Key Features

- **Investment Portfolio Tracking** -- Accounts, holdings, transactions with automatic cost basis computation, portfolio history charts, live price and market value columns, multi-currency support with tenant-managed exchange rates, and money market fund support
- **Rental Property Management** -- Income/expense tracking, cash flow reports, loan amortization, Zillow valuation scraping, hold-vs-sell ROI analysis, and cost-segregation depreciation with schedule transparency
- **Retirement Projections** -- Deterministic year-by-year projection engine plus a Monte Carlo guardrail spending optimizer with block-bootstrap returns, Roth conversion modeling, per-pool withdrawal transparency, rental property integration, and scenario comparison
- **Multi-Format Import** -- Fidelity, Vanguard, and Schwab CSV parsers plus OFX/QFX import with content-hash deduplication
- **Live Price Feeds** -- Finnhub API integration with historical backfill, scheduled daily sync, and on-demand admin sync
- **Dashboard** -- Net worth summary combining investments, cash, and property equity with asset allocation breakdown
- **Multi-Tenant** -- JWT-based auth with tenant isolation, invite code registration, and role-based access (admin/member/viewer)
- **Administrative Tooling** -- Super-admin tenant management, audit log, data export (JSON + CSV with formula-injection neutralization), and notification preferences
- **Self-Hosted** -- Single Docker Compose command to deploy; no third-party SaaS dependencies

## Quick Start

**Prerequisites:** Docker with the Compose plugin (`docker compose`), port 80 available.

```bash
cp .env.example .env                      # Fill in DB_PASSWORD, JWT_SECRET, SUPER_ADMIN_PASSWORD
./wv up                                   # Build, start, wait for health
./wv status                               # Verify
```

For a **production** deployment, also set `WEALTHVIEW_VERSION` in `.env` —
that flips `./wv` into prod mode (uses `docker-compose.prod.yml`, with the
nightly backup container and stricter config validation).

- **URL:** http://localhost
- **Super admin:** `admin@wealthview.local` / `admin123`
- **Demo user:** `demo@wealthview.local` / `demo123` (pre-loaded with sample data)

```bash
./wv down                                 # Stop (preserve data)
./wv down --with-volumes                  # Stop and delete database (prompts)
./wv help                                 # Full subcommand reference
```

For backups, restores, host migrations, secret rotation, and update +
rollback, see [Operations Handbook](docs/deployment/operations.md).

### Installing `wv` on a production server

The same `wv` tool is meant to be installed system-wide on production
hosts so you can operate the stack with just containers (no source tree)
on the box. The tool reads its layout from `/etc/wealthview/wv.conf` —
see `bin/wv.conf.example` for the schema. Quick install:

```bash
sudo install -m 0755 bin/wv /usr/local/bin/wv
sudo install -d /usr/local/lib/wv-lib
sudo install -m 0644 bin/wv-lib/*.sh /usr/local/lib/wv-lib/
sudo ln -snf /usr/local/lib/wv-lib /usr/local/bin/wv-lib
sudo install -d /etc/wealthview
sudo cp bin/wv.conf.example /etc/wealthview/wv.conf  # edit paths
sudo wv config-check
sudo wv up
```

`wv` can also drive a remote Docker host over SSH — set `WV_HOST` in
`wv.conf` (or pass `--host user@host`) to operate the prod stack from
your laptop. Run `wv help` for the full handbook including the override
hierarchy and remote operation requirements.

For raw `docker compose` access (rarely needed once `./wv` is in your hands):

```bash
docker compose up --build -d              # Dev stack, raw
```

### Developer setup (one-time)

```bash
./scripts/install-hooks.sh                # gitleaks pre-commit hook
direnv allow                              # auto-load .env on cd (install direnv first)
npm install                               # hoist workspace deps (frontend, shared, mobile)
```

The repo is an npm workspaces monorepo with three packages: `frontend/` (web SPA), `mobile/` (React Native app), and `shared/` (cross-platform utilities consumed by both). Run `npm install` once at the root and the workspaces wire themselves up.

`.env` is gitignored. Real secrets live there and only there. See [Secrets & Configuration](#secrets--configuration) below.

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
| [Operations Handbook](docs/deployment/operations.md) | `./wv` admin command reference for every routine operation |
| [Production Setup](docs/deployment/production-setup.md) | Full production deployment — db, app, backup, edge proxy choice |
| [Cloudflare Tunnel](docs/deployment/cloudflared.md) | Self-hosted deployment via `cloudflared` (no open ports) |
| [TLS & Nginx](docs/deployment/tls-and-nginx.md) | Host-managed TLS with nginx + Let's Encrypt |
| [Security Hardening](docs/deployment/security-hardening.md) | Firewall, SSH, secrets, app-level security |
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
| Frontend  | React 19, TypeScript, Vite, React Router, Recharts, Axios |
| Backend   | Java 21, Spring Boot 3.5, Spring Security, JPA/Hibernate  |
| Database  | PostgreSQL 16 with Flyway migrations                      |
| Caching   | Caffeine (balances, holdings, projections)                |
| Build     | Maven multi-module (backend), npm (frontend)              |
| Testing   | JUnit 5, Mockito, AssertJ, Testcontainers, Vitest         |
| Deploy    | Docker Compose (multi-stage build)                        |

## Secrets & Configuration

WealthView reads every credential from environment variables. There are no real secrets in any committed file.

| File | Purpose | Tracked? |
|------|---------|----------|
| `.env.example` | Schema and `CHANGE_ME` placeholders | ✅ committed |
| `.env` | Your actual secrets | ❌ gitignored |
| `.envrc` | direnv hook to auto-load `.env` | ✅ committed |
| `application-dev.yml` / `application-it.yml` | Sentinel fallbacks (`LOCAL_DEV_*`, `INTEGRATION_TEST_*`) so `mvn test` works without env | ✅ committed |
| `application.yml` / `application-prod.yml` / `application-docker.yml` | `${VAR}` references with no fallbacks; fail-loud at startup | ✅ committed |

**Layered guardrails against re-introducing secrets:**

1. **Pre-commit hook** (`scripts/install-hooks.sh`) — gitleaks scans the staged diff and blocks commits containing secret-shaped values.
2. **CI job** (`.github/workflows/secret-scan.yml`) — gitleaks scans full history on every push and PR; failure blocks the build.
3. **Allowlist** (`.gitleaks.toml`) — explicitly permits the documented sentinel values; everything else is suspect.
4. **Production startup validator** (`ProductionConfigValidator`) — refuses to boot the prod profile if any sentinel default leaked into a real deployment.

**The rule:** never put a real or real-looking value into any committed file. Always reference `${VAR}` and add the variable to `.env.example`.

## License

Private -- all rights reserved.
