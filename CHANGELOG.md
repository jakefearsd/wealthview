# Changelog

All notable user-facing and operational changes to WealthView are recorded in
this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.2.6] — 2026-08-16

Turns the release pipeline into an actual release: tagged builds now publish a
container image and cut a GitHub Release, and production deploys that image
instead of rebuilding from source on the server. No schema changes (still V080).

This is the first release whose image is published, so it is also the first one
`wv update` can pull.

### Upgrade notes
- **1.2.6 is the earliest version a server can pull.** `docker-compose.prod.yml`
  now resolves the app image from the registry, and nothing before this release
  was ever published — v1.2.5 and earlier only ever existed as images built on
  the server itself. Do not set `WEALTHVIEW_VERSION` below 1.2.6 on a host that
  has taken these files; there is no such image to pull.
- **The GHCR package is created PRIVATE on first push, even for a public repo.**
  Make it public under the repository's Packages settings, or give the host a
  `docker login ghcr.io` with a `read:packages` token. Otherwise the first pull
  fails with an unauthorized error whose cause is not obvious.

### Changed
- Tagged releases now publish a container image instead of building one and
  discarding it. The `docker-image` job ran a bare `docker build` whose output
  never left the runner, so the artifact deployed to production was one that no
  CI job had ever seen — the server rebuilt it independently from source. The
  job now builds with Buildx and pushes
  `ghcr.io/<owner>/wealthview:<version>` and `:latest`, gated behind the unit,
  quality-gate and full integration suites exactly as before. Publishing is
  restricted to `refs/tags/v*`; a manual `workflow_dispatch` still builds, to
  prove the image assembles, but never pushes — so it cannot move `:latest` or
  claim a version that was never tagged. Built for `linux/amd64` only: the
  Dockerfile compiles the entire Maven backend inside the build stage, so an
  emulated arm64 build would take 30-60+ minutes.
- A tagged release now also creates a GitHub Release, with notes lifted from the
  matching `CHANGELOG.md` section and a footer naming the image and the two
  commands needed to deploy it.
- **Production pulls the release image rather than building on the server.**
  `docker-compose.prod.yml` drops its `build:` key and resolves
  `${WEALTHVIEW_IMAGE:-ghcr.io/jakefearsd/wealthview}:${WEALTHVIEW_VERSION}`;
  `wv update` pulls it. This settles a long-standing contradiction — `wv help`
  has always said the production host does not need the source tree, while
  `wv update` required it to run `docker compose build`. The host now needs
  neither the source tree nor a JDK. The dev stack is unchanged and still builds
  locally. `WEALTHVIEW_IMAGE` is new and only needs setting for a fork, a
  private mirror, or an air-gapped registry.
- `wv update` gains `--build` (build locally instead of pulling) and `--no-pull`
  (reuse the local image). `--no-build` still works as a deprecated alias for
  `--no-pull` and says so. `--build` refuses to run when the resolved compose
  file's app service has no `build:` section: `docker compose build` on such a
  service exits 0 having done nothing, which would have deployed a stale image
  while reporting success.

### Fixed
- `wv rollback` would have produced a malformed image reference once the app
  image became registry-qualified. It recovered the previous tag by stripping a
  literal `wealthview:` prefix, which does not match
  `ghcr.io/owner/wealthview:1.2.5`, so the whole reference was substituted as if
  it were a version. Rollback now parses the reference properly and re-pins both
  the repository and the tag — splitting on the last colon only when what
  follows contains no slash, so a `registry:5000/wealthview` reference with no
  tag is rejected rather than being read as version "5000". Covered by five new
  bats cases.
- `deploy.sh` — the build-locally-ship-a-tarball path for constrained hosts —
  would have stopped working once the compose file resolved a registry
  reference. It `docker load`s an image tagged `wealthview:<version>`, which no
  longer matches what compose asks for, so compose would have ignored the loaded
  image and tried to pull from GHCR. It now exports
  `WEALTHVIEW_IMAGE=wealthview` on the remote so the loaded tarball is what
  actually runs.
- `docs/deployment/production-setup.md` advertised ARM64 support and recommended
  a Raspberry Pi 4. The release image is published for `linux/amd64` only, so
  the host requirement is now stated as x86-64, with a note on what an ARM user
  would need to do.

## [1.2.5] — 2026-08-16

A documentation-truth pass over the whole repository, and the twelve code
defects that pass uncovered. No schema changes (still V080).

Most reference, user-guide and administration pages had not been touched since
2026-03-14. Reconciling them against the code turned up bugs that were invisible
precisely because the docs described the broken behavior as if it were intended —
monitoring that had never collected a metric, an audit log any user could read,
and a price sync running twice a day.

### Upgrade notes

Three changes are operator-visible. None require a schema migration or a config
change to keep working, but read these before upgrading:

- **`docker-compose.prod.yml` now refuses to start when a required secret is
  missing.** `DB_PASSWORD`, `JWT_SECRET`, `SUPER_ADMIN_PASSWORD` and
  `MFA_ENCRYPTION_KEY` moved to the fail-loud `${VAR:?}` form. A deployment that
  was silently running with an empty secret will now stop and tell you. If you
  drive the stack with `./wv`, `wv_env_check` was already catching this.
- **`GET /api/v1/audit-log` now requires `admin` or `super_admin`.** It was
  readable by any authenticated user, including viewers. Anything scripted
  against that endpoint with a member token will start getting 403.
- **Scheduled backups are now named `wealthview_auto_<ts>.dump`.** Existing
  backups are untouched and still listed and restorable; only external tooling
  that globs the exact old scheduled filename needs updating.

### Security
- `GET /api/v1/audit-log` now requires `admin` or `super_admin`. It carried no
  matcher and fell through to `anyRequest().authenticated()`, so any member or
  viewer could read the tenant's entire audit trail — every action by every
  user, admin operations included — straight over HTTP. The web UI only linked
  it from the admin area, which made it look gated; UI-only gating is not access
  control.
- The bundled Prometheus/Grafana stack was collecting **nothing**, on every
  profile except `loadtest`, for its entire existence. `prometheus.yml` scraped
  with HTTP Basic, but the filter chain only ever accepted a JWT — Basic was
  never enabled and there is no `UserDetailsService` — so every scrape got 401.
  The configured scrape user (`super-admin@wealthview.local`) did not exist
  either; the super admin is `admin@wealthview.local`. Nothing failed loudly:
  monitoring was simply blind.

  Rather than bolt a second authentication mechanism onto the app for metrics
  alone, `/actuator/prometheus` and `/actuator/metrics` can now be opened
  explicitly via `app.observability.anonymous-metrics`, **default false**.
  `docker-compose.observability.yml` sets it, and the `basic_auth` block is gone.
  **Where the flag is on, those two endpoints are unauthenticated** — keep the
  app's port off the internet and block `/actuator` at the reverse proxy. A test
  asserts the flag does not unlock the rest of `/actuator`. The old hardcoded
  `loadtest`-profile carve-out in `SecurityConfig` collapsed into this same
  property, so there is one mechanism instead of two.
- `docker-compose.prod.yml` now fails loudly on missing secrets. See upgrade
  notes above.
- Cleared the last outstanding npm advisory (`brace-expansion` unbounded-expansion
  DoS, GHSA-mh99-v99m-4gvg, dev-tooling only). `npm dedupe` after the React Native
  bump collapsed the vulnerable 1.x paths; `npm audit` now reports 0 vulnerabilities,
  down from 1 high.

### Fixed
- The daily price sync ran **twice every weekday**, burning double the Finnhub
  quota. `PriceSyncService.syncDailyPrices()` carries its own `@Scheduled`
  (18:00 ET), and a leftover `PriceSyncScheduler` wrapper in `wealthview-app`
  scheduled a second call to it at 16:30 ET. The wrapper is deleted. A new
  `PriceSyncSchedulingTest` classpath-scans for `@Scheduled` methods and asserts
  exactly one trigger reaches the sweep, so a second one cannot creep back.
- The Grafana latency panel and the `WealthViewHttpP99Latency` alert read empty
  in production. `http.server.requests` histogram buckets were enabled only
  under the `loadtest` profile, so `histogram_quantile` had no series to read —
  an alert that could never fire looked exactly like healthy latency. Buckets
  are now published on every profile.
- The prod app container reported permanently `unhealthy`. `docker-compose.prod.yml`
  overrode the image healthcheck with `curl`, which is not installed in the
  `eclipse-temurin:25-jre-alpine` runtime. The override is deleted so the
  Dockerfile's `wget` probe is the single definition and the two cannot drift
  apart again. (Deploys were unaffected — `wv_wait_healthy` probes over HTTP from
  the host.)
- The backup retention sweep deleted on-demand backups. It matched
  `wealthview_*.dump`, which is also what `wv backup` produces, so a manual
  pre-change backup was aged out by the cron container. Scheduled dumps are now
  `wealthview_auto_<ts>.dump` and the sweep only reclaims what it created, plus
  the matching `.age` files it previously never pruned at all.
- Members were shown an "Add Manual Price" form the server rejects with 403.
  Prices are shared reference data aggregated across tenants, so the server rule
  is right and the client gate was wrong; it now matches admin/super-admin.
- Super admins saw no write controls on accounts, holdings or property detail.
  Four pages omitted `super_admin` from their gate while the server permits it.
  The role predicate is now a single shared helper rather than five copies.
- `JAVA_OPTS` was silently ignored — the exec-form `ENTRYPOINT` has no shell to
  expand it. The Dockerfile now documents `JAVA_TOOL_OPTIONS` as the supported
  knob and warns against "fixing" it with `sh -c`, which would regress signal
  handling and graceful shutdown.
- `ExchangeRateResolver` told users to add a missing rate "in Settings" — a page
  that no longer exists. It now points at Admin → Exchange Rates.
- `LtcgBracketEntity` inherited a `precision = 5` mapping for `rate` while
  `ltcg_brackets.rate` is `numeric(6,4)`. Corrected with an `@AttributeOverride`;
  no migration was touched.
- `shared/src/api/types.ts` declared `device_label` on `RegisterRequest`; the
  backend record has only email, password and invite code. (It is genuine on
  `LoginRequest` and was left alone.)
- `wv help` no longer advertises `MFA_ENCRYPTION_KEY` as a `rotate-secret`
  target. `rotate-secret.sh` accepts only `JWT_SECRET`, `SUPER_ADMIN_PASSWORD`
  and `DB_PASSWORD`, and rejects anything else — rotating the MFA key would
  make every stored TOTP secret undecryptable, which the help text now says.
- `mobile/.sdkmanrc` comment said "React Native 0.85.x"; the workspace is on
  0.87.0. The pinned JDK (17) was already correct.
- `GET /actuator/prometheus` no longer returns 500 after a guardrail
  optimization. `MonteCarloSpendingOptimizer.optimize` carried both `@Timed`
  and `@Observed` under one meter name; both register a timer, and because only
  one asked for histogram buckets the metric family held Histogram and Summary
  data points at once, which made the Prometheus text writer throw and fail the
  **entire** scrape — every metric, not just that one. In practice monitoring
  went dark after the first optimization and stayed dark until restart. The
  redundant `@Timed` is gone and the histogram is configured via a `MeterFilter`,
  so the Grafana p95 panel is unaffected. `DeterministicProjectionEngine.run`
  had the same double registration; it did not crash but was recording every
  projection run twice into one metric.
- `PUT /api/v1/notifications/preferences` returns 400 instead of 500 when an
  item omits `enabled` or `notification_type`. The request declared the list
  `@NotNull` but not `@Valid`, so Bean Validation never cascaded into the
  elements and a null reached the service, where it auto-unboxed into a
  primitive `boolean`.

### Documentation
- Repo-wide documentation truth pass: 58 files reconciled against the code they
  describe. Most reference, user-guide and administration pages had not been
  touched since 2026-03-14 and had accumulated five months of drift.
  The corrections that mattered most:
  - **API reference** was missing roughly 90 endpoints, documented six that do
    not exist, and described the auth scheme backwards — it claimed tokens are
    returned in the login body, when web auth is HttpOnly cookies with CSRF
    double-submit and Bearer is the *mobile* transport.
  - **Data model** documented 23 entities across 8 domains; the schema has 42
    across 9. All of auth/MFA/sessions, stock splits, exchange rates, the
    projection-realism tables, mortality, IRMAA and state tax were absent.
  - **Projection docs** were wrong on the numbers that matter: confidence levels
    (documented 90/80/70, actually 0.95/0.90/0.80), the definition of success
    (it is the essential floor being funded every year), and a claim that tax
    brackets are inflation-indexed "matching IRS COLA methodology" when the
    engine is constant-real. `PoolStrategy.SinglePool` and
    `IRMAA_BRACKET_RATE` were documented but do not exist.
  - **Spending tiers** were documented as having per-tier inflation.
    `TierBasedSpendingPlan.computeInflationFactor()` returns `1.0`
    unconditionally — tier amounts are today's dollars held constant real.
  - **PROJECT.md listed email alerts as shipped.** There is no mail sender in
    the codebase; notification preferences are storage plus a GET/PUT endpoint,
    with no delivery and no web UI. Moved to the roadmap.
  - **Cost basis** was documented as "buys minus sells"; it is average cost, and
    the worked example gave the wrong answer.
  - `docs/reference/configuration.md` had been publishing a `JWT_SECRET` default
    that `ProductionConfigValidator` actively rejects, plus
    `SUPER_ADMIN_PASSWORD=admin123`. Both removed.
  - Operator procedures across the administration and deployment guides now use
    the `./wv` command surface rather than the ad-hoc scripts it replaced.
  - `docs/DeploymentGuide.md`, an orphaned duplicate with no inbound links, is
    now a routing index rather than a fourth copy of the deployment facts.

### Changed
- Test coverage pass across the financial math, the HTTP surface, and the
  import parsers: +145 backend tests (household capital-gains taxation, bracket
  inflation-indexing, six previously untested controllers, and importer error
  paths). Import branch coverage 78.2% → 82.7%; `wealthview-app` integration
  tests 265 → 340. Frontend coverage is now measured and gated
  (`npm run test:coverage`), which it previously was not.
- Dependency refresh across the backend and all three npm workspaces. Backend:
  jsoup 1.22.2 → 1.23.1 and Checkstyle core 13.8.0 → 13.9.0. Two Spring Boot
  BOM-managed versions are now overridden ahead of the 4.1.0 BOM — PostgreSQL
  JDBC 42.7.11 → 42.7.13 and HttpClient5 5.6.1 → 5.6.3 — both patch-level and
  both covered by the existing test suite. OpenTelemetry and Flyway were left
  on the BOM deliberately (see the comment in `backend/pom.xml`). Frontend:
  Vite 8.1.5 → 8.2.0, `@vitejs/plugin-react` 6.0.4 → 6.0.5, Recharts
  3.10.0 → 3.10.1, Playwright 1.62.0 → 1.62.1, plus React type packages.
  Axios 1.18.1 → 1.19.0 in all three workspaces. Mobile: React Native
  0.86.0 → 0.86.2 with every `@react-native/*` package moved in lockstep.

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

[Unreleased]: https://github.com/jakefearsd/wealthview/compare/v1.2.6...HEAD
[1.2.6]: https://github.com/jakefearsd/wealthview/compare/v1.2.5...v1.2.6
[1.2.5]: https://github.com/jakefearsd/wealthview/compare/v1.2.4...v1.2.5
[1.2.4]: https://github.com/jakefearsd/wealthview/compare/v1.2.3...v1.2.4
[1.2.3]: https://github.com/jakefearsd/wealthview/compare/v1.2.2...v1.2.3
[1.2.2]: https://github.com/jakefearsd/wealthview/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/jakefearsd/wealthview/compare/v1.0.0...v1.2.1
[1.0.0]: https://github.com/jakefearsd/wealthview/releases/tag/v1.0.0
