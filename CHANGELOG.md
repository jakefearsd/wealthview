# Changelog

All notable user-facing and operational changes to WealthView are recorded in
this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

Nothing yet.

## [1.2.4] — 2026-07-27

Consolidates 74 commits since v1.2.3 — a pattern-focused refactoring pass over
the projection engine, the whole backend test suite, and the frontend, executed
and independently reviewed task-by-task. One real engine bug and three
dead-plumbing defects fixed; the fixture and scaffolding duplication across
roughly two hundred test files collapsed into shared builders. No schema
changes (still V080).

### Fixed
- Roth-conversion arm scoring now actually funds spending withdrawals. The
  conversion simulator parsed the withdrawal order as a comma-separated pool
  list while production supplies enum tokens ("taxable_first"), so for ages
  59½+ without dynamic sequencing the spending step silently drew nothing and
  accrued no withdrawal tax — understating pool depletion and lifetime tax on
  every scored conversion arm. Withdrawal-order semantics are now defined once
  on the `WithdrawalOrder` enum and consumed by all three engines.
- Generic CSV import accepts `opening_balance`. The parser's hand-rolled type
  list had drifted from the API, the DB constraint, and the Fidelity positions
  parser; all four now share one `TransactionType` enum, making this class of
  drift impossible.
- Latest-price lookups are now cached as intended. The `latestPrices` cache
  was evicted at eight write paths but had no reader, so every read hit
  PostgreSQL; reads now go through a cached resolver (10-minute TTL). All
  in-app writes evict, so only out-of-band DB edits can be briefly stale.
- `POST /api/v1/admin/stock-splits/sync` returns the standard
  `{error, message, status}` envelope with 503 when split detection is not
  configured, instead of an empty body.

### Changed
- Transaction types are case-tolerant on input (`"BUY"` is accepted) and
  always normalized to the lowercase wire token.
- Web error toasts show the backend's actual message wherever a handler
  previously hard-coded a generic string; mobile login failures likewise
  surface the server's message instead of a fixed sentence.
- Inside conversion scoring, `pro_rata` draws taxable-first, matching the
  Monte Carlo trial path; the deterministic engine's truly proportional
  behavior is unchanged.
- Chart tooltips and the three pill-style range selectors now share one
  component each, with minor visual convergence on the two nonstandard sites.

### Notes for existing data
- Guardrail profiles optimized before this release were scored under the
  fixed no-op; re-optimizing a scenario will legitimately shift its schedule.
- Re-optimizing a dynamic-sequencing scenario does not carry the DS bracket
  rate over (pre-existing limitation, now material) — run a fresh optimize
  after changing DS settings.

### Internal
- Pattern consolidation: `TransactionType`, `PoolType`/`LotOwner` and
  `WithdrawalOrder.drawSequence()` end the stringly-typed dispatch; builders
  on the two 20/40-component guardrail records; the Monte Carlo trial-pass
  assembly, `SearchContext`, withdrawal signatures, and regime building moved
  behind factories and parameter objects with byte-identical outputs; UUID
  primary-key mapped superclasses cover 35 entities; the broker CSV parser
  Template Method is complete (new brokers are constants-only).
- Test infrastructure: shared fixture builders, object mothers, and stubs
  replace per-file duplication across backend and frontend suites (fixture
  builders for the guardrail/scenario records, one flat-tax stub, an API
  client facade adopted across 40+ integration-test classes, shared vi.mock
  scaffolding); 267 Testcontainers integration tests and all five build gates
  green.

## [1.2.3] — 2026-07-25

Consolidates 17 commits since v1.2.2 — one memory-leak fix, a repaired mobile
lint gate, the remaining code-quality backlog, and a dependency sweep that took
four majors. No schema changes (still V080).

### Fixed
- Rate limiting no longer leaks memory. The filter tracked one entry per
  distinct client key (source IP for auth paths, principal or IP elsewhere) and
  never removed any, so the map grew for the lifetime of the process — slowly
  under organic traffic, quickly under a burst from many source IPs. Expired
  windows are now swept out, bounding the map to clients seen in the last
  window, and the tracked-key count is exposed as the
  `wealthview.ratelimit.tracked_keys` gauge.

### Internal
- Mobile `npm run lint` was failing outright rather than reporting: a missing
  `eslint-plugin-jest` meant the shared React Native config referenced a Jest
  environment that did not exist, aborting config validation before any file was
  linted. With the linter running again, all 25 findings it had been hiding are
  resolved, including a dead constant in the portfolio screen. Mobile lint is now
  a working gate.
- Closed the remaining code-quality backlog: a `hasStateTax()` capability check
  replaces an `instanceof` test against the no-state-tax null object; holdings
  aggregation returns a named record instead of a two-element `BigDecimal[]`;
  the degenerate optimization setup is built by named factories instead of three
  positional argument lists of zeros and nulls; the four production-secret guards
  share one helper; the Zillow and Finnhub clients shape their outbound requests
  in one place each; and the monthly-interpolation helper reads its three
  retirement pools all-or-nothing, removing eleven non-null assertions.
- Added test coverage for the property income chart (12 tests over category
  ordering, trailing-window totals, inflation compounding, and depreciation) and
  removed dead scaffolding from the test suites, including an anonymous-class
  hook that silently overrode nothing.

### Dependencies
- Vite 8 with `@vitejs/plugin-react` 6 and Vitest 4.1.10. Vite 8 builds with
  rolldown, which rejects the object form of `manualChunks`; the chunk split is
  preserved (vendor-charts 436 kB vs 439 kB).
- React Native 0.86.0 with the matching `@react-native/*` 0.86.0 set, verified by
  an Android debug build. React 19.2.8.
- `@testing-library/react-native` 14, whose `render` is now async — every mobile
  test call site was migrated. Prettier 3.
- `typescript-eslint` 8.65. TypeScript stays on 5.9: typescript-eslint caps its
  peer at `<6.1.0`, so TypeScript 7 cannot be adopted without an unsupported
  dependency resolution.
- Backend dependencies were already at latest stable; nothing moved.

## [1.2.2] — 2026-07-21

Consolidates 13 commits since v1.2.1 — a code-quality pass plus a dependency
currency sweep. No schema changes (still V080).

### Fixed
- Fidelity positions import: a row whose cost basis is `--` now falls back to
  the snapshot market value instead of importing a zero cost basis.
- Theoretical portfolio history: duplicate-symbol holdings rows are merged
  rather than one silently overwriting the other.
- Yahoo chart parsing: a quote block missing its indicators is treated as
  no-data instead of throwing.
- Depreciation: asset-class life and bonus-eligibility metadata is now derived
  from one source, removing a drift risk between the calculator and the
  schedule builder.

### Internal
- Behavior-preserving refactors: `SimulationConfig.builder(...)` replaced five
  telescoping constructors across 30 call sites; `IncomeProjector.Context`
  made the three-precompute lockstep structural; shared price-history
  date-grid/price-map plumbing extracted; projection CSV export driven from a
  single column spec; `ScenarioForm` split from 865 lines into section
  components.
- Maven reactor now builds in parallel (`-T1C`), cutting build time ~15-20%.

### Dependencies
- Backend build tooling and the logstash encoder (9.x); npm minors across all
  workspaces plus react-router 8 and `@testing-library/jest-dom` 7. `npm audit`
  clean; all quality gates green.

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
