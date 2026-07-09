# Projection Realism v2 (Tier 1) — Design

**Date:** 2026-07-09
**Status:** Approved (design), pending implementation plan
**Scope:** The four Tier‑1 correctness/realism gaps identified in the 2026‑07‑09 projection audit
(`memory/project_projection_realism_audit_2026_07.md`).

## Problem statement

The retirement projection has two engines with different, partly wrong assumptions:

1. **Deterministic engine** (`DeterministicProjectionEngine`) — a single fixed-return path using each
   account's `expectedReturn`.
2. **Monte Carlo guardrail optimizer** (`MonteCarloSpendingOptimizer` / `TrialSimulator` /
   `BlockBootstrapReturnGenerator`) — block-bootstrap of one historical series.

Tier‑1 defects being fixed:

- **#1 — MC ignores allocation.** It block-bootstraps a single S&P 500 real series (8.1% arith / 6.4%
  geo real, 18.2% vol) and applies it to *all* pools, ignoring the user's actual holdings and
  contradicting the deterministic engine. `returnMean` (the input users set) doesn't even drive MC
  dispersion.
- **#2 — Weak confidence presets.** Risk tolerance maps to conservative 0.85 / moderate 0.70 /
  aggressive 0.60; the optimizer maximizes spending against that bar, so "moderate" ships a
  ~30%-shortfall plan.
- **#3 — Inconsistent / optimistic reporting.** Two different success definitions (optimizer:
  terminal ≥ target at percentile; report: depletion to ≤0); RNG never seeded (non-reproducible);
  optimize vs. report draw independent path sets; the reported terminal simulation runs with
  `marginalRateByYear = null` so reported stats deduct **no** withdrawal tax.
- **#4 — Inflation.** Defaults to 0; when set, `computeTax` uses frozen latest-year brackets with no
  indexing while withdrawals grow → artificial bracket creep, and the Roth optimizer targets an
  *indexed* bracket ceiling while the tax charged is *un-indexed*.

## Decisions (from brainstorming)

| Area | Decision |
|---|---|
| Portfolio representation | **Hybrid**: auto-derive allocation from linked holdings via a curated symbol→class map; user-editable; manual for hypothetical accounts. |
| Asset classes | **4**: `US_STOCK`, `INTL_STOCK`, `BOND`, `CASH`. |
| Return generation | **Multi-asset joint block bootstrap** of per-class **real** return series (same sampled calendar year across classes → preserves cross-asset correlation, autocorrelation, fat tails). |
| Allocation level | **Per account (tax bucket)** — enables asset location; all accounts share the same sampled market year. |
| Rebalancing | **Annual rebalance to target** (fixed weights per pool). No glide path in v1. |
| `expectedReturn` | **Derived from allocation** (geometric blend) with an **optional manual override**; override applies to both engines. |
| Historical data home | **Database table** (seeded via Flyway, like `tax_brackets`), curated by us. |
| Success metric | **Essentials funded every year AND investable assets never deplete to $0**; discretionary may flex. Also report terminal balance P10/P50/P90 + bequest. |
| Confidence | Recalibrate presets to **conservative 0.95 / moderate 0.90 / aggressive 0.80** + explicit `targetSuccessProbability` override. |
| Reproducibility | **Seed = hash(scenario inputs)**; optimize + report share the same tax-aware path set. |
| Inflation frame | **Real terms (today's dollars)**; default **2.5%**, editable. |

## Non-goals (v1)

Stochastic inflation; glide paths; capital-gains/cost-basis tracking on taxable accounts; RMD
enforcement in the main projection; mortality/longevity distributions; granular (6–8) asset classes.
These remain in the Tier‑2/Tier‑3 backlog.

---

## Architecture

### Phase 1 — Allocation-driven, real-terms return model (Tier 1 #1 + #4)

**New domain types (`wealthview-core`):**

- `AssetClass` enum: `US_STOCK, INTL_STOCK, BOND, CASH` (each with a stable string key for
  persistence/JSON).
- `AssetAllocation` record wrapping `Map<AssetClass, BigDecimal>`; compact constructor validates
  non-negative weights and normalizes to sum 1.0 (empty → error). Provides `blend(double[] perClass)`
  helpers.
- `ProjectionAccountInput` gains `AssetAllocation allocation` and `Optional<BigDecimal>
  expectedReturnOverride` (nominal, converted to real using scenario inflation).

**Historical data (DB-backed):**

- Table `asset_class_returns` (Flyway `V066`): `id uuid pk`, `year int`, `asset_class text`,
  `real_return numeric(9,6)`, `created_at/updated_at`, `uq_asset_class_returns_year_class (year, asset_class)`,
  `chk_asset_class_returns_class` limiting `asset_class` to the four keys.
- Seed via repeatable `R__seed_asset_class_returns.sql`, curated 1972–2025 real annual returns for the
  four classes (US equity + bonds + CPI from Shiller; international from MSCI EAFE total-return; cash
  from 3‑month T‑bills). **We build this data set.** All four classes must be present for every seeded
  year (joint bootstrap requires alignment).
- `AssetClassReturnEntity` + `AssetClassReturnRepository` in `wealthview-persistence`.
- `CapitalMarketAssumptionsProvider` bean in `wealthview-core`: loads all rows once, caches
  (`ConcurrentHashMap`, like `FederalTaxCalculator`), exposes (a) the year-aligned real-return matrix
  `double[years][classes]`, (b) per-class geometric means. Validates that every year has all four
  classes; throws a domain exception if the seed is incomplete.

**Return model (`wealthview-projection`):**

- `BlockBootstrapReturnGenerator` generalized to produce a **sequence of sampled row indices**
  `int[years]` (5-year expected blocks, unchanged mechanics). One index sequence per trial is shared
  across all pools.
- `PortfolioReturnResolver`: `(int[] indexSequence, AssetAllocation)` → per-year real return
  `r[y] = Σ wᵢ · matrix[idx[y]][i]`. Annual rebalance ⇒ fixed weights ⇒ simple weighted blend.
- Deterministic derivation: `expectedRealReturn(allocation) = Σ wᵢ · geoMeanᵢ`.
- **Override contract:** if `expectedReturnOverride` is present, the pool uses a fixed real return
  (`(1+override_nominal)/(1+inflation) − 1`) every year in **both** engines. Consequence: an
  overridden pool contributes **no volatility** to the MC — a documented deterministic escape hatch.

**Classifier (hybrid):**

- Global seed map table `security_asset_class` (Flyway `V067`): `id uuid pk`, `symbol text`,
  `asset_class text`, `uq_security_asset_class_symbol (symbol)`, `chk_security_asset_class_class`
  (four keys); seeded via `R__seed_security_asset_class.sql` (VOO/VTI/FXAIX→US, VXUS→Intl, BND→Bond,
  SPAXX→Cash, …).
- Tenant override table `security_class_override` (Flyway `V068`): `tenant_id uuid`, `symbol text`,
  `asset_class text`, `uq_security_class_override_tenant_symbol`.
- `SecurityClassificationService` (`wealthview-core`): resolves a symbol's class as tenant-override →
  seed map → **default `US_STOCK` (flagged)**. Computes an account's allocation as the
  holding-value-weighted class mix of its current holdings. Unknown symbols are surfaced as
  `unclassifiedSymbols` in the projection response for UI reclassification.
- `ProjectionInputBuilder`: linked accounts get an auto-derived allocation (unless the account has a
  stored override allocation); hypothetical accounts use their stored `allocation`.

**Engine integration:**

- Deterministic `MultiPool.applyGrowth` / `SinglePool.applyGrowth`: per-pool real return (allocation
  blend or override) instead of one shared `weightedReturn`.
- MC `TrialSimulator`: `pools[i] *= (1 + r_i[y])`, each `r_i` from the shared index sequence × that
  pool's allocation. `SimulationConfig` carries per-pool allocations (or pre-resolved per-pool return
  sequences). Cash-reserve bucket keeps its own real cash return (the Cash *class* return); it remains
  a liquidity mechanism distinct from a Cash allocation sleeve.
- `PortfolioPathGenerator` drops `toNominal` (returns stay real).

**Real-terms & inflation mechanics:**

- Everything in today's dollars; remove `toNominal` re-inflation; default inflation 2.5% in
  `DeterministicProjectionEngine.resolveProjectionParams` and `GuardrailProfileService`.
- Spending (tiers, fixed-% withdrawal) constant-real by default — drop `(1+inflation)^n` escalators;
  explicit real growth still possible.
- Income sources: COLA (source infl ≈ general) → constant real; partial/no-COLA → deflated by
  `(1+source)^n / (1+infl)^n`; fixed-nominal → deflated by `(1+infl)^n`.
- Taxes: brackets + standard deduction use base-year seeded values **as-is (constant real)** — remove
  per-year fallback indexing entirely (kills the bracket-creep bug and the conversion-ceiling
  inconsistency in one move). Statutorily-fixed-nominal thresholds (SS provisional-income $25k/$34k/
  $32k/$44k and the $10k SALT cap) are **deflated** by `(1+infl)^n`.
- Fix the SS tier‑1 bug in `SocialSecurityTaxCalculator`: tier‑1 component must be
  `min(0.5 × benefits, 0.5 × (tier2 − tier1))`.

### Phase 2 — Optimizer: success, confidence, reproducibility, tax-aware reporting (Tier 1 #2 + #3)

- `TrialSimulator.TrialResult` gains a `boolean success` = essential floor funded every year AND
  investable assets never hit $0 (discretionary flex not counted as failure).
- `SustainabilitySearch`: objective becomes "maximize discretionary spend such that
  `successRate ≥ targetConfidence`" (replaces terminal-balance-at-percentile). Terminal target /
  portfolio floor become optional bequest constraints.
- `GuardrailProfileService` risk map → conservative 0.95 / moderate 0.90 / aggressive 0.80; add
  `targetSuccessProbability` request field that overrides the preset.
- Reproducibility: derive a deterministic seed from scenario inputs (accounts, allocations, spending,
  params) and use it for both the optimization search and the reported simulation, so both draw the
  same sampled index sequences.
- `GuardrailResponseBuilder` terminal simulation runs **with** `marginalRateByYear` (not `null`):
  reported headline success %, terminal P10/P50/P90, bequest, and fan chart are all tax-aware and on
  the same paths the plan was optimized against.

### Phase 3 — Frontend

- Per-account allocation editor (auto-derived shown, user-editable); reclassification UI for flagged
  unknown symbols; explicit target-success-probability input alongside the recalibrated risk labels;
  results presented in today's dollars with a clear real-terms label.

---

## Persistence & back-compat

- `projection_accounts`: add `allocation jsonb NULL`; `expected_return` becomes nullable (the optional
  override). Flyway `V069`.
- Existing scenarios: a non-null `expected_return` is interpreted as a **nominal override** → the
  deterministic path is unchanged and the MC honors it (as a fixed real return) instead of silently
  bootstrapping 100% S&P 500. Adding an allocation upgrades a scenario to stochastic multi-asset
  behavior.
- Default allocation for new hypothetical accounts: a global editable default (60% US / 20% Intl /
  15% Bond / 5% Cash).

## Module dependency check

Respects the strict direction: new entities/repositories in `wealthview-persistence`;
`CapitalMarketAssumptionsProvider`, `SecurityClassificationService`, `AssetClass`, `AssetAllocation`
in `wealthview-core`; bootstrap/resolver/engine changes in `wealthview-projection` (which depends on
core). No new `api → persistence/projection` edges.

## Testing strategy (TDD per CLAUDE.md)

**Unit (write first):**
- `AssetAllocation` invariants (normalize, reject negative/empty).
- `BlockBootstrapReturnGenerator` index sequence: same index applied across classes, block-length
  distribution, seeded reproducibility.
- `PortfolioReturnResolver`: weighted blend correctness; override path (fixed real return).
- `CapitalMarketAssumptionsProvider`: load/cache; reject years missing a class; geometric means.
- `SecurityClassificationService`: seed hit, tenant override precedence, unknown→default+flag,
  value-weighted allocation.
- Real-terms tax: constant-real brackets/deduction; SS threshold + SALT deflation; **SS tier‑1 fix**.
- Success definition (floor-breach and depletion cases); `SustainabilitySearch` new objective;
  seed reproducibility (same inputs → identical output).

**Integration (Testcontainers, PostgreSQL 16):**
- `AssetClassReturnRepository`, `security_class_override` repository.
- End-to-end scenario projections; **regenerate golden files** (values change by design — review diffs
  deliberately) and add a back-compat test that an existing `expected_return`-only scenario still runs
  and its deterministic output is unchanged within tolerance.

**Gates:** JaCoCo floors upheld (core/projection 90% line; branch floors not lowered); PMD/CPD/
SpotBugs/Checkstyle green; run `mvn verify` locally (ITs included) before tagging.

## Rollout / sequencing

1. Phase 1 (return model + real-terms) — largest, foundational.
2. Phase 2 (optimizer success/confidence/repro/tax-aware reporting) — on top of Phase 1.
3. Phase 3 (frontend).

Each phase is independently testable and mergeable on `main` (no feature branches, per project policy).
