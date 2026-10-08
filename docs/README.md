[← Back to README](../README.md)

# WealthView Documentation

One line per living document, grouped by who reads it.

## Users

- [Getting Started](user-guide/getting-started.md) — first login, navigation, key concepts
- [Investment Accounts](user-guide/investment-accounts.md) — accounts, transactions, holdings, cost basis
- [Data Import](user-guide/data-import.md) — Fidelity/Vanguard/Schwab CSV and OFX/QFX import, deduplication
- [Prices & Valuation](user-guide/prices-and-valuation.md) — price feeds, manual entry, portfolio valuation
- [Portfolio Analysis](user-guide/portfolio-analysis.md) — dashboard, charts, net worth breakdown
- [Rental Properties](user-guide/rental-properties.md) — properties, mortgages, income/expenses, analytics
- [Retirement Projections](user-guide/retirement-projections.md) — projection engine, strategies, scenario comparison
- [Spending & Income](user-guide/spending-and-income.md) — spending profiles, income sources, tax treatment
- [Settings & Export](user-guide/settings-and-export.md) — admin area, invite codes, users, data export
- [Monte Carlo Details](MCDetails.md) — what the spending optimizer's confidence levels and percentiles mean
- [Feature Walkthrough](feature_walkthrough.md) — step-by-step tour of every feature (doubles as a manual test script)

## Administrators and operators

Deployment:

- [Deployment Guide](DeploymentGuide.md) — orientation page that links the deployment docs below
- [Quick Start Deployment](deployment/quickstart.md) — running in a few minutes with Docker
- [Production Setup](deployment/production-setup.md) — full production deployment: db, app, backup, edge proxy
- [Cloudflare Tunnel](deployment/cloudflared.md) — exposing the app via `cloudflared` with no open ports
- [TLS & Nginx](deployment/tls-and-nginx.md) — host-managed TLS with nginx and Let's Encrypt
- [Security Hardening](deployment/security-hardening.md) — firewall, SSH, secrets, app-level security
- [Upgrading](deployment/upgrading.md) — upgrades, rollback, Flyway migrations
- [Operations Handbook](deployment/operations.md) — the `./wv` command reference for every routine operation

Administration and operations:

- [Tenant & User Management](administration/tenant-and-user-management.md) — tenants, roles, invite codes, audit log
- [Backups](administration/backups.md) — automated backups and restore procedures
- [Monitoring & Logging](administration/monitoring-and-logging.md) — health checks, structured logs, alerting
- [Maintenance](administration/maintenance.md) — database, disk, scheduled jobs, capacity
- [Troubleshooting](administration/troubleshooting.md) — diagnostics, common problems, fixes
- [Stock Splits](operations/stock-splits.md) — auto-detection, backfill, manual entry and un-apply
- [Observability](OBSERVABILITY.md) — metrics, tracing, logging, and the optional observability stack

Reference:

- [Architecture](reference/architecture.md) — module structure, dependency rules, project tree
- [API Reference](reference/api-reference.md) — endpoints with examples
- [Data Model](reference/data-model.md) — entities, ER diagram, migrations
- [Configuration](reference/configuration.md) — environment variables and Spring profiles
- [Frontend Routes](reference/frontend-routes.md) — route table with page descriptions

## Developers

- [Development Guide](development.md) — local setup, build and test commands, quality gates, CI
- [PROJECT.md](../PROJECT.md) — architecture spec, feature status, and roadmap
- [CLAUDE.md](../CLAUDE.md) — contributor conventions: TDD, layering, commits, secrets policy
- [CHANGELOG.md](../CHANGELOG.md) — release history
- [Roth Conversion Optimizer Design](ROTH_CONVERSION_OPTIMIZER_DESIGN.md) — original design plus what shipped
- [Load Test Harness](../loadtest/README.md) — k6 scenarios, profiling, result reports

Mobile:

- [Mobile App](../mobile/README.md) — React Native client: stack, screens, local and native builds
- [Android Device Testing](deployment/mobile-android-testing.md) — running the app on a physical Android phone
- [Mobile API](MOBILE_API.md) — the token-auth endpoint contract for native clients
- [Shared Package](../shared/README.md) — `@wealthview/shared`, the API client and utilities shared by web and mobile

## Historical records

`docs/superpowers/`, `docs/plans/`, `docs/audits/`, `docs/quality/` and `docs/testing/` hold design
specs, implementation plans, audit findings and quality-pass notes written at the time of the work.
They are kept for context and are not maintained, so they may not match the current code. When one
disagrees with a living document above or with the code, the code and the living document win.
