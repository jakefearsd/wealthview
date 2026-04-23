# Changelog

All notable user-facing and operational changes to WealthView are recorded in
this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

Nothing yet — next entry will be cut at the 1.0.0 tag.

## [1.0.0] — 2026-04

First tagged release. Consolidates all development prior to the 1.0 cut.

### Investment portfolio
- Accounts, holdings, and transactions with automatic cost-basis and quantity
  recomputation from aggregated buys/sells (manual overrides preserved).
- Live price feeds via Finnhub with historical backfill, scheduled daily sync,
  and on-demand admin sync. Graceful handling of unpriced symbols (e.g. money
  market funds) in portfolio history.
- Portfolio history chart and dashboard net-worth summary that combines
  investments, cash, and property equity.
- CSV import for Fidelity, Vanguard, and Schwab; OFX/QFX import with
  content-hash deduplication.
- Multi-currency account support with tenant-managed exchange rates and
  display-time conversion at aggregation boundaries.

### Rental properties
- Income and expense tracking, cash-flow reports, and loan amortization.
- Zillow valuation scraping (opt-in via `ZILLOW_ENABLED`).
- Hold-vs-sell ROI analysis with depreciation recapture and capital-gains tax
  integration per income source.
- Cost-segregation depreciation with structured asset-class allocations,
  bonus depreciation, and 481(a) catch-up; schedule transparency in the UI.

### Retirement projections
- Deterministic year-by-year projection engine with contributions, growth,
  inflation-adjusted withdrawals, and tiered spending profiles.
- Standard deduction in tax calculations (2022–2025 seeded); inflation-indexed
  bracket ceilings.
- Monte Carlo guardrail spending optimizer with block-bootstrap returns,
  withdrawal-tax modeling, portfolio fan chart, and spending-corridor view.
- Unified Roth conversion + withdrawal tax optimizer (joint spending-conversion
  optimization, rental loss integration, target-balance approach).
- Near-term adaptive spending guide (`NearTermSpendingGuide.tsx`): 5-year
  tactical view with P25=optimizer / P50=4% / P55=5.5% heuristics.
- Scenario comparison, dynamic sequencing, and per-pool withdrawal transparency.

### Multi-tenant platform
- JWT-based authentication with tenant isolation and invite-code registration
  (invite verified *before* email-uniqueness check to close an enumeration
  channel).
- Role-based access (admin / member / viewer) with server-side token-generation
  bumps on role change so stale role claims cannot be replayed.
- CSPRNG-generated invite codes (120 bits of entropy).
- Super-admin tenant management, audit log, data export (JSON + CSV with
  formula-injection neutralization), and notification preferences.

### Performance
- Batch `computeAllBalances` replaces the per-account N+1 path.
- Caffeine caching with five named caches (balances, holdings, projections, …).
- HikariCP pool tuned to 20 max connections.

### Security (2026-04-22 audit remediation — Phases 1 and 2)
- Production profile validates `CORS_ORIGIN` (non-empty https), `JWT_SECRET`
  (length + rejection of known defaults), and other required secrets at
  startup; the app refuses to boot if any are missing or weak.
- OFX uploads reject `DOCTYPE` declarations (blocks XXE).
- Price write endpoints require ADMIN or SUPER_ADMIN.
- Finnhub API token is sent in the request header rather than the query string.
- All outbound HTTP clients have explicit connect and read timeouts.
- User-controlled strings are sanitized before being written to logs.
- Audit event details are bounded (8 KB / depth 3) to prevent
  storage-amplification payloads.
- Optimistic locking (`@Version`) on user token generation resolves refresh-race
  double-revocations.
- Security headers include `Permissions-Policy` disabling geolocation,
  microphone, camera, and payment by default.
- Docker image runs as a non-root `wv` user and exposes a container
  `HEALTHCHECK` targeting `/actuator/health`.
- `APP_RATE_LIMIT_TRUSTED_PROXIES` captures the real client IP when deployed
  behind `cloudflared` or nginx.

### Deployment
- Three-service production Docker Compose stack: `db`, `app`, `backup`.
- Automated nightly `pg_dump -Fc` backup service with configurable retention.
- Deployment documentation: quickstart, production setup, Cloudflare Tunnel,
  host-managed TLS with nginx + Let's Encrypt, security hardening, and
  upgrading / rollback procedures.
- `deploy.sh` build-locally-ship-tarball path for constrained home servers,
  with image tag pinned to `wealthview:${git-describe}` instead of `:latest`.

### Tooling
- 56 API-level integration tests (11 IT classes) using Testcontainers +
  Failsafe against PostgreSQL 16.
- JaCoCo coverage targets: core and projection 90 %+, api and import 80 %+.
- Test-first workflow mandated by `CLAUDE.md`; no production code without a
  failing test.

[Unreleased]: https://github.com/jakefearsd/wealthview/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/jakefearsd/wealthview/releases/tag/v1.0.0
