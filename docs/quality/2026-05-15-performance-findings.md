# Performance Survey Findings — 2026-05-15

This document is the output of Task 27 (pre-release quality refactor): an
**investigation-only** survey for performance issues across the WealthView
backend and frontend. Nothing was changed here — fixes are Task 28, and Task 28
should only act on findings tagged **Confirmed**.

Method:

- **N+1 queries:** static inspection of `wealthview-core` service classes for
  per-row repository calls inside loops; audit of every JPA entity for
  `FetchType.EAGER` (none found); cross-check of the batch balance computation
  added by prior work (`computeBalancesByAccountIds`) — still batched, no regression.
- **Indexes:** every `CREATE INDEX` in the Flyway migrations was extracted and
  cross-checked against the repository finders that hit those tables.
- **Computation / unbounded results:** review of the Monte Carlo optimizer hot
  path (`TrialSimulator`, `SustainabilitySearch`, `PortfolioPathGenerator`),
  the projection engine, and a scan for unpaginated list endpoints.
- **Frontend:** `npm run build` in `frontend/` (Vite 7.3.3) for bundle size,
  plus inspection of the router and the two largest pages.

Confidence is honest. A finding is **Confirmed** only when backed by concrete
evidence (a visible loop, a measured number, an absent index for a named query).
Everything else is **Speculative**.

---

## Summary of counts

| Area                       | Confirmed | Speculative |
|----------------------------|-----------|-------------|
| N+1 queries                | 2         | 1           |
| Indexes                    | 1         | 1           |
| Repeated computation / unbounded | 0   | 2           |
| Frontend                   | 2         | 1           |
| **Total**                  | **5**     | **5**       |

Overall the backend is in good shape: the prior Phase 3 work (batch balance
computation, Caffeine caching) holds up, no `FetchType.EAGER` exists anywhere,
and the Monte Carlo hot path is already carefully written in primitive `double`
arithmetic with an explicit comment explaining why `BigDecimal` is kept out of
the inner loop. The genuine wins are concentrated in two places: an uncached
exchange-rate lookup called inside chart-building loops, and the un-code-split
frontend bundle.

---

## 1. N+1 Query Patterns

### 1.1 `convertToUsd` issues a DB query per chart data point — *Confirmed*

- **Location:** `wealthview-core/.../exchangerate/ExchangeRateService.java:108`
  (`convertToUsd`), called in loops at:
  - `wealthview-core/.../dashboard/CombinedPortfolioHistoryService.java:179` and `:184`
  - `wealthview-core/.../portfolio/TheoreticalPortfolioService.java:111`
- **Evidence:** `convertToUsd` is the only public method on `ExchangeRateService`
  with **no `@Cacheable`** annotation (`list()`, by contrast, is `@Cacheable`).
  Each call runs `exchangeRateRepository.findByTenant_IdAndCurrencyCode(...)` —
  a fresh SELECT. In `CombinedPortfolioHistoryService.computeHistory` the call
  sits inside the `for (var friday : dataPointDates)` loop (one iteration per
  weekly Friday, up to ~520 for the 10-year max window) and is invoked once per
  non-USD currency *per* data point. `TheoreticalPortfolioService` has the same
  shape: `for (var dp : dataPoints) { convertToUsd(...) }` (~52 points for a
  12-month window). For a tenant with one foreign-currency account this is
  dozens-to-hundreds of identical single-row queries per chart render.
- **Impact:** Medium. The query is an indexed unique lookup
  (`uq_exchange_rates_tenant_currency` covers `(tenant_id, currency_code)`), so
  each call is cheap individually, but the round-trip count is linear in the
  number of chart points. Noticeable latency on the dashboard / portfolio-history
  endpoints for multi-currency tenants; zero cost for USD-only tenants (the
  `"USD".equals(currency)` fast-path returns before any query).
- **Proposed fix:** Either (a) add `@Cacheable("exchangeRates")` keyed on
  `(tenantId, currency)` to `convertToUsd` — the `exchangeRates` cache is already
  evicted on rate create/update/delete, so correctness is preserved — or (b)
  hoist the rate lookup out of the loop: resolve each currency's rate once before
  the data-point loop and multiply inline. Option (b) is the cleaner fix for the
  two chart services and removes the round-trips entirely.

### 1.2 `recomputeAllForTenantAndSymbol` loads every transaction for a tenant — *Confirmed*

- **Location:** `wealthview-core/.../holding/HoldingsComputationService.java:49–62`
- **Evidence:** `recomputeAllForTenantAndSymbol` calls
  `transactionRepository.findByTenant_Id(tenantId)` and then filters in memory
  to the one symbol of interest. For a tenant with a large transaction history
  this loads the *entire* transaction table for the tenant just to find the
  accounts holding one symbol. It is called once per affected tenant inside the
  stock-split apply/unapply loop in `StockSplitService` (`applySplit:107`,
  `unapplySplit:161`), so a split touches `tenants × all-their-transactions` rows.
  There is no `findByTenant_IdAndSymbol` finder; the symbol filter is pure Java.
- **Impact:** Medium, but on a low-frequency path (daily split sync / manual
  admin action, not a user request). Memory and query cost scale with total
  transaction count, not with the symbol's transaction count.
- **Proposed fix:** Add a repository finder
  `findByTenant_IdAndSymbol(UUID tenantId, String symbol)` to
  `TransactionRepository` and use it here. The query is covered by the existing
  `idx_transactions_tenant_id` index (and could be narrowed further — see 2.2).

### 1.3 `StockSplitService.restorePrice` re-scans all prices per adjustment row — *Speculative*

- **Location:** `wealthview-core/.../split/StockSplitService.java:251–270`,
  called from the `for (var adj : adjustments)` loop at `:136`.
- **Evidence:** `unapplySplit` loops over every `stock_split_adjustments` row;
  for each `prices` adjustment it calls `restorePrice`, which runs
  `priceRepository.findBySymbolAndDateBefore(symbol, effectiveDate)` — the *same*
  query every iteration — then linearly scans the result to match a deterministic
  UUID. This is O(adjustments × prices) work and O(adjustments) identical
  queries. The transaction restore path (`restoreTransaction:235`) is fine — it
  does a direct `findById`.
- **Why speculative:** Unapply is a rare admin-only operation and the price-row
  count per symbol is bounded (a few years of daily closes). The cost is real
  but the path is cold, so the practical impact is small. Flagged for awareness,
  not urgency.
- **Proposed fix (if actioned):** Fetch `findBySymbolAndDateBefore` once before
  the loop, build a `Map<UUID, PriceEntity>` keyed on the deterministic
  `priceUuid`, and look up O(1) per adjustment.

---

## 2. Indexes vs. Query Patterns

### 2.1 `prices` range scans rely on the primary key — *Confirmed (acceptable, documented)*

- **Location:** `wealthview-persistence/.../repository/PriceRepository.java`:
  `findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc`,
  `findBySymbolAndDateBetweenOrderByDateDesc`, `findBySymbolAndDateBefore`.
- **Evidence:** `prices` (V006) has `PRIMARY KEY (symbol, date)` and **no other
  index**. All the symbol+date-range finders above filter on `symbol` (and
  optionally a `date` range) — exactly the leading-column prefix of the composite
  PK. PostgreSQL serves these from the primary-key B-tree; the `symbol`-prefix
  scan plus date-range bound is index-supported.
- **Impact:** None — this is correct as-is. Recorded so Task 28 does **not**
  "fix" it by adding a redundant `(symbol, date)` index.
- **Proposed fix:** No action. The composite PK is the right index.

### 2.2 `transactions` symbol queries scan a tenant-wide index — *Speculative*

- **Location:** `transactions` indexes from V004 / V010:
  `idx_transactions_account_id`, `idx_transactions_tenant_id`,
  `idx_transactions_account_id_symbol`, `idx_transactions_import_hash`.
  Affected queries: `findByTenant_Id` (used by `HoldingsComputationService` 1.2
  and `StockSplitService.listForTenant:291`), and a proposed
  `findByTenant_IdAndSymbol`.
- **Evidence:** There is an `(account_id, symbol)` index but **no
  `(tenant_id, symbol)` index**. `listForTenant` loads *all* of a tenant's
  transactions purely to derive the distinct symbol set; `recomputeAllForTenantAndSymbol`
  (finding 1.2) does the same. If 1.2's proposed `findByTenant_IdAndSymbol`
  finder is added, it would scan `idx_transactions_tenant_id` and filter `symbol`
  in the heap rather than using a covering index.
- **Why speculative:** Index value depends on tenant transaction volume and on
  whether finding 1.2 is actioned. For a single-user self-hosted deployment the
  row counts are modest and `idx_transactions_tenant_id` is adequate. Worth a
  composite `idx_transactions_tenant_id_symbol` only if 1.2 is fixed and volumes
  grow.
- **Proposed fix (conditional):** If Task 28 implements
  `findByTenant_IdAndSymbol`, add a Flyway migration creating
  `idx_transactions_tenant_id_symbol ON transactions (tenant_id, symbol)`.
  Otherwise no action.

All other hot tenant-scoped finders (`accounts`, `holdings`, `properties`,
`projection_scenarios`, `spending_profiles`, `income_sources`,
`exchange_rates`, `guardrail_spending_profiles`, `login_activity`,
`property_income/expenses`) **do** have a supporting `idx_<table>_tenant_id`
index, and the property income/expense date-range queries are covered by the
composite indexes added in V041. No missing index found there.

---

## 3. Repeated Computation & Unbounded Results

### 3.1 `SustainabilitySearch.isSustainable` recomputes nominal returns every call — *Speculative*

- **Location:** `wealthview-projection/.../SustainabilitySearch.java:285–290`
  (inside `isSustainable`).
- **Evidence:** `isSustainable` is called 30–40 times per binary search
  (`SPENDING_BINARY_SEARCH_ITERATIONS = 30`, `PHASE_BINARY_SEARCH_ITERATIONS = 40`),
  and the joint search invokes it across a grid of conversion fractions. Each
  call rebuilds `nominalReturns[y] = paths[t][y+1] / paths[t][y] - 1.0` for every
  trial × year. Those ratios are derived purely from `ctx.paths()`, which is
  **invariant** across all binary-search iterations of a given search — the
  division work is repeated for every iteration even though the inputs never
  change.
- **Why speculative:** The per-iteration cost is `trialCount × years` cheap
  `double` divisions (e.g. 10000 × 28 ≈ 280k flops) — fast in absolute terms.
  Whether hoisting this into a precomputed `double[trialCount][years]` array
  yields a *measurable* end-to-end win is unproven without a profiler run, and
  the simulation has strict characterization tests (Task's guidance: do not
  break determinism). The transformation is value-preserving (same arithmetic,
  just memoized), so it is safe — but the payoff is unconfirmed.
- **Proposed fix (if measured to matter):** Precompute the `nominalReturns`
  matrix once when the `SearchContext` / paths are built and pass it through,
  rather than re-deriving it inside every `isSustainable`. Must produce
  bit-identical results so the characterization tests still pass.

### 3.2 `SustainabilitySearch.binarySearchDiscretionary` clones an array per iteration — *Speculative*

- **Location:** `wealthview-projection/.../SustainabilitySearch.java:239`
- **Evidence:** Inside the 40-iteration binary-search loop,
  `double[] testDiscretionary = currentDiscretionary.clone()` allocates a fresh
  `double[years]` array every iteration. The array is then overwritten on the
  `[phaseStart..phaseEnd]` slice. Across a multi-phase allocation this is
  `phases × 40` short-lived array allocations.
- **Why speculative:** `years` is small (~28), so each clone is ~224 bytes of
  young-gen garbage; the JIT and a generational GC handle this trivially. The
  allocation is defensible because the slice outside the phase must retain the
  caller's `currentDiscretionary` values. A single reusable scratch buffer
  restored after each `isSustainable` call would remove the allocation but adds
  state-management risk for negligible gain.
- **Proposed fix:** Likely **no action**. Recorded only so Task 28 has the full
  picture; not worth the readability cost.

**Unbounded results:** no problem found. `TransactionRepository` finders that
back HTTP endpoints (`findByAccount_IdAndTenant_Id`) return `Page<>` and accept
`Pageable`; `AccountRepository.findByTenant_Id` has a `Pageable` overload. The
non-paginated `findByTenant_Id` variants are used only by internal
service-to-service computation (holdings recompute, projection input building),
not exposed directly as list endpoints. The Monte Carlo result arrays
(`finalBalances`, `minBalances`, `yearBalances`) are sized by `trialCount` /
`years`, both bounded inputs.

---

## 4. Frontend

Bundle measured with `npm run build` in `frontend/` (Vite 7.3.3, 855 modules):

```
dist/assets/index-23zx9d5R.js   1,029.02 kB │ gzip: 286.74 kB
dist/index.html                     0.84 kB │ gzip:   0.50 kB
```

### 4.1 Entire app ships as one un-code-split JS chunk — *Confirmed*

- **Location:** `frontend/src/App.tsx:7–25` (the import block).
- **Evidence:** Every route component is a **static** top-of-file import —
  `import DashboardPage from './pages/DashboardPage'`, …, all 20+ pages. There
  is **no `React.lazy` / `Suspense`** anywhere in `src/` (grep for `lazy(` /
  `Suspense` returns only test files). Vite consequently emits a single
  `index-*.js` of **1,029 kB (286.74 kB gzip)** and prints its own warning:
  *"Some chunks are larger than 500 kB after minification."* A first-time visitor
  to the login page downloads, parses, and compiles the projection engine UI,
  the Monte Carlo optimizer page, all property pages, and the admin area before
  the login form is interactive.
- **Impact:** Medium–High for cold loads, especially over a slow link or on the
  self-hosted deployment's first visit. Time-to-interactive is gated on the full
  bundle.
- **Proposed fix:** Route-level code splitting — convert the page imports in
  `App.tsx` to `const DashboardPage = lazy(() => import('./pages/DashboardPage'))`
  and wrap `<Routes>` in `<Suspense fallback={...}>`. Login/Register and the
  shared `Layout` can stay eager; the heavy authenticated pages
  (`ProjectionDetailPage`, `SpendingOptimizerPage`, property pages) become
  separate chunks loaded on navigation. This is the single highest-value
  frontend change.

### 4.2 `recharts` is bundled eagerly into the main chunk — *Confirmed*

- **Location:** `frontend/package.json` (`"recharts": "^3.8.1"`), imported by
  ~10 chart components under `frontend/src/components/` (e.g. `BalanceChart.tsx`,
  `PortfolioFanChart.tsx`, `CombinedPortfolioChart.tsx`,
  `SnapshotProjectionChart.tsx`, `TraditionalBalanceChart.tsx`).
- **Evidence:** `recharts` (with its `d3-*` transitive deps) is the largest
  third-party dependency by far and is the bulk of the 1 MB bundle. Because
  there is no code splitting (4.1), it loads on every page including pages with
  no charts (login, import, admin).
- **Impact:** Medium. Subsumed by 4.1 — once routes are lazy-loaded, `recharts`
  naturally falls into the chunks of the chart-bearing pages only. Optionally a
  `manualChunks` entry can isolate it as a long-lived cacheable vendor chunk.
- **Proposed fix:** Primarily addressed by 4.1. Optionally add a
  `build.rollupOptions.output.manualChunks` rule in `vite.config.ts` splitting
  `recharts` (and `react`/`react-dom`) into a stable `vendor` chunk so it is
  cached across app deploys.

### 4.3 Per-render computation on the optimizer / projection pages — *Speculative*

- **Location:** `frontend/src/pages/SpendingOptimizerPage.tsx` (~611 lines, 0
  `useMemo`/`useCallback`), `frontend/src/pages/ProjectionDetailPage.tsx` (~631
  lines, 3 `useMemo`).
- **Evidence:** `SpendingOptimizerPage` runs `.filter` / `.reduce` over
  `yearlySpending` and `phaseDiags` directly in the render body
  (`SpendingOptimizerPage.tsx:45,53,69`) with no memoization; `ProjectionDetailPage`
  is the largest page and only memoizes 3 values. These are the two heaviest
  authenticated pages.
- **Why speculative:** The arrays involved are small (a few dozen retirement
  years / phases) and JS array iteration over that size is sub-millisecond.
  Without React Profiler traces there is no evidence these recomputations cause
  a perceptible dropped frame. Re-running a `.reduce` over ~30 elements on each
  render is not a real performance problem; adding `useMemo` here would be
  cargo-culting.
- **Proposed fix:** Likely **no action** on performance grounds. If touched at
  all, only wrap genuinely expensive derived values, and only if a profiler
  shows a measurable cost. Recorded for completeness.

---

## Prioritized Summary for Task 28

Fix, in order, the **Confirmed** findings only:

1. **4.1 — Route-level code splitting in `App.tsx`** (`React.lazy` + `Suspense`).
   Highest user-visible win; turns one 1 MB chunk into per-route chunks.
   Subsumes 4.2.
2. **1.1 — Cache or hoist `convertToUsd`.** Add `@Cacheable` to
   `ExchangeRateService.convertToUsd`, or hoist the rate lookup out of the
   chart-building loops in `CombinedPortfolioHistoryService` /
   `TheoreticalPortfolioService`. Removes dozens-to-hundreds of redundant queries
   per multi-currency chart render.
3. **1.2 — Add `findByTenant_IdAndSymbol` to `TransactionRepository`** and use it
   in `HoldingsComputationService.recomputeAllForTenantAndSymbol` so a split no
   longer loads the tenant's entire transaction table.
4. **2.2 — (only if 1.2 is done)** add `idx_transactions_tenant_id_symbol` via a
   new Flyway migration.

Leave alone: **2.1** (the `prices` composite PK is already the right index — do
not add a redundant one), and the Speculative findings 1.3, 3.1, 3.2, 4.3
unless a profiler later proves a measurable cost. The Monte Carlo hot path is
already well-optimized and must not be changed in ways that break the
characterization tests.

**Conclusion:** the backend is largely sound — no `EAGER` fetches, batch balance
computation intact, caching in place, hot loops written in primitive arithmetic.
The real, evidenced wins are narrow: one uncached lookup and one frontend bundle
that was never code-split.
