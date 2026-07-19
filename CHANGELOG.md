# Changelog

All notable user-facing and operational changes to WealthView are recorded in
this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

Nothing yet.

## [1.2.1] — 2026-07-19

Consolidates everything since v1.2.0. (Releases 1.1.0, 1.1.1, and 1.2.0 were
tagged without changelog entries; their highlights live in the annotated tag
messages.)

### Platform
- Migrated the backend to Java 25 / Spring Boot 4.1 / Jackson 3 / Hibernate 7.
- Release tags are now fully integration-test-gated in CI: the tag pipeline runs
  unit tests + quality gates, the full Testcontainers HTTP integration suite,
  and the Docker image build as separate jobs.

### Retirement projections
- Projection accuracy audit v2 remediation: taxable-pool yield split by
  allocation (bond interest taxed as ordinary income), scenario-level
  investment fee drag, accumulation-phase cost-basis tracking, optimizer
  gating on adaptive spending rules (default on), and joint-optimum arm
  scoring.
- Household/survivor modeling: two-person households with owner-aware account
  pools in both engines, per-owner RMD streams, and an atomic first-death
  transition (Social Security keep-larger, spousal rollover, basis step-up,
  survivor spending factor, MFJ-to-single filing flip).
- Stochastic mortality (opt-in): the Monte Carlo optimizer can sample each
  spouse's death year from sex-specific SSA life tables, reporting lifetime
  and longevity-conditional success probabilities plus death-age percentiles.
- Scenario form: per-account allocation reset no longer echoes a removed
  override as the "derived from holdings" mix.

### Fixed
- Unknown API paths now return the standard 404 NOT_FOUND envelope instead of
  a 500 with an ERROR-level stack trace.

### Database
- Migrations through V080 (accounts.owner, income-source owner and survivor
  percent, mortality_rates with SSA 2021 life-table seed).

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
