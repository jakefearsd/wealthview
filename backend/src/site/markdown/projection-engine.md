# Projection Engine

The projection engine lives in `wealthview-projection` and exposes two Spring-managed components.
Everything else in the module is package-private collaborator code. The interfaces that decouple the
engine from the rest of the application are defined in `wealthview-core`, along with the tax
calculators and the capital-market assumptions provider (which need repository access and therefore
live on the core side of the module boundary).

---

## Design Overview

```
ProjectionEngine   (@FunctionalInterface, wealthview-core)
  └── DeterministicProjectionEngine  (@Component)

SpendingOptimizer  (@FunctionalInterface, wealthview-core)
  └── MonteCarloSpendingOptimizer    (@Component)
        ├── OptimizationContextBuilder  (pools, paths, income, tax tables)
        ├── JointConversionSearch       (Roth conversion arm search)
        │     └── RothConversionOptimizer -> ConversionSimulator + FractionSearch
        ├── SustainabilitySearch        (binary search + the success gate)
        ├── StochasticMortalityEvaluator (opt-in longevity pass)
        └── GuardrailResponseBuilder    (terminal pass + response assembly)

SpendingPlan  (sealed interface, wealthview-core)
  ├── TierBasedSpendingPlan     (wraps a SpendingProfile's tiers)
  └── GuardrailSpendingInput    (wraps MC-optimized yearly spending)

WithdrawalStrategy  (sealed interface, wealthview-core)
  ├── FixedPercentageWithdrawal
  ├── DynamicPercentageWithdrawal
  └── VanguardDynamicSpendingWithdrawal

PoolStrategy  (sealed interface, wealthview-projection)
  └── MultiPool   (taxable / traditional / Roth, owner-aware)
```

`PoolStrategy` permits exactly one implementation. A separate `SinglePool` fast path existed
historically and was removed (audit C11): it computed no tax at all, which silently understated
every all-taxable scenario. `MultiPool` supports empty sub-pools, so every scenario routes through it.

---

## Real-Terms Frame

**The whole projection runs in constant-real (today's-dollar) terms.** This is the single most
important thing to know before reading any number in the engine:

* Asset returns come from the `asset_class_returns` table as **real** (inflation-adjusted) annual
  returns. Pools grow at those real rates directly — there is no Fisher conversion to nominal.
* Spending tiers, phase targets, the essential floor and income amounts are held constant real.
  `TierBasedSpendingPlan.computeInflationFactor` always returns `1.0`.
* Tax brackets and standard deductions are used at their seeded dollar values with **no** annual
  indexing (`computeMaxIncomeForBracket(..., ZERO)`). `FederalTaxCalculator` still carries an
  inflation-indexing parameter, but every projection caller passes zero/null; indexing only kicks in
  as a fallback when the requested year has no seeded brackets at all.
* Thresholds that are **statutorily fixed in nominal terms** are the exception and *are* deflated by
  `1 / (1 + inflation)^yearsFromBase`, so their real erosion is modeled: the Social Security
  provisional-income thresholds (`SocialSecurityTaxCalculator`) and the NIIT thresholds
  ($200k single / $250k MFJ, `CapitalGainsTaxCalculator`).
* A user-supplied per-account expected-return override is a **nominal** wire value and is converted
  to real via `(1 + nominal) / (1 + inflation) − 1` before use (`PoolStrategy.realReturnFor`).

---

## Allocation-Driven Returns

Accounts no longer carry a hard-coded expected return. Each `projection_accounts` row has an
optional `allocation` (jsonb weights over `us_stock`, `intl_stock`, `bond`, `cash`) and an optional
`expected_return` **override**.

* `CapitalMarketAssumptionsProvider` loads `asset_class_returns` into a dense
  `RealReturnMatrix` (year x asset class) and caches it, plus the per-class geometric means. The
  default window is **1972–2025**; a scenario can opt into the full **1928–2025** seed (the
  Depression-era tail) via `params_json.include_depression_years`.
* `PoolStrategy.realReturnFor` resolves one account's real return: the override path Fisher-converts
  the nominal value; otherwise the account's allocation weights are blended against the geometric
  means. Either way the scenario's annual all-in fee rate is then subtracted at that single choke
  point.
* Pool-level returns are the balance-weighted average of their accounts' returns, computed once from
  initial balances (no allocation-drift tracking over the horizon).
* Where an allocation is not supplied, `SecurityClassificationService` derives one from the linked
  account's holdings. Classification precedence is **tenant override → global `security_asset_class`
  seed → `US_STOCK` default**; the override is upserted through
  `PUT /api/v1/securities/{symbol}/classification`.

---

## DeterministicProjectionEngine

**Entry point:** `run(ProjectionInput input)` → `ProjectionResultResponse`

The engine simulates year by year from `referenceYear` to `birthYear + endAge`. When a household's
*second* death falls inside the horizon the loop truncates there — that final row's balance is the
bequest.

### Year-loop Pipeline

```
For each year:
  1. age = year − birthYear; retired = year >= retirementYear
  2. Contributions (pre-retirement only)         -> pool.applyContributions()
  3. Snapshot each owner's traditional balance   (pre-growth: the IRS RMD basis)
  4. Growth                                      -> pool.applyGrowth(retired)
  5. RMDs, one stream per owner, forced out      -> RmdStreamCalculator
  6. First-death transition (once) + survivor income/spending
                                                 -> HouseholdTransition
  7. IRMAA surcharge from the MAGI 2 years prior -> IrmaaSurchargeCalculator
  8. Income, spending target, Roth conversion, withdrawals, taxes
                                                 -> YearFinanceResolver
  9. Floor pools at zero; property equity; assemble ProjectionYearDto
 10. Viability disclosures + tax annotation; roll the MAGI lookback window
```

### Defaults

| Parameter | Default | Where |
|---|---|---|
| Withdrawal rate | 4% (`DEFAULT_WITHDRAWAL_RATE`) | used when no `SpendingPlan` is configured |
| Inflation rate | 2.5% | `resolveProjectionParams` |
| End age | 90 | `resolveProjectionParams` |
| Birth year | `currentYear − 35` | when `params_json.birth_year` is absent |
| Retirement year | `currentYear + 30` | when no retirement date is set |
| Survivor spending factor | 0.75, clamped to `[0.5, 1.0]` | `DEFAULT_SURVIVOR_SPENDING_FACTOR` |
| Medicare age | 65 | IRMAA gate |

`SpendingFeasibilityAnalyzer` carries a `SHORTFALL_TOLERANCE` of `−$10`: a per-year spending surplus
above that is not reported as a shortfall.

### Taxable Pool: Lots, Dividends and the Accumulation-Phase Basis

`MultiPool` holds the taxable pool as a set of **FIFO cost-basis lots** (`TaxableLotsBd`), one seeded
per taxable account (basis = its cost basis, value = its balance, so embedded unrealized gain carries
into the projection). Each lot is tagged with its source account's owner.

* **Retired years** split the taxable pool's annual yield: the equity share (us_stock + intl_stock,
  balance-weighted from initial allocations) distributes at `dividend_yield` and is booked as
  qualified-dividend income; the remaining bond+cash share distributes at `interest_yield` and is
  booked as **ordinary interest** income. The distribution is reinvested as a fresh at-cost lot.
* **Accumulation years book no distribution at all** (audit C8). Lots appreciate at the full total
  return as pure unrealized appreciation; basis is untouched and no at-cost lot is created. Booking
  a reinvested distribution pre-retirement was a free, untaxed basis step-up.
* Contributions enter at cost (basis = value), tagged by the contributing account's owner.
* Realized long-term gains from spending sales are taxed through `CapitalGainsTaxCalculator`:
  the 0/15/20% LTCG brackets from `ltcg_brackets`, **stacked on top of ordinary taxable income**,
  plus the 3.8% NIIT on `min(net investment income, MAGI − threshold)`. Net rental income joins the
  NIIT base (IRC 1411) but *not* the LTCG bracket tax — rental income is ordinary.
* Secondary taxable sales (paying the withdrawal tax, replenishing the cash reserve) sell FIFO to
  keep lots in sync, but their gain is deliberately excluded from taxation — a documented
  second-order simplification, mirrored on both engines.

### RMDs

`RmdStreamCalculator` runs **one RMD stream per owner**, each keyed to that owner's own birth year:

* Start age from SECURE 2.0 (Pub. L. 117-328): **73** for owners born before 1960, **75** for 1960
  or later (`RmdCalculator.rmdStartAge`).
* Divisor from the IRS Uniform Lifetime Table III (Pub. 590-B), ages 72–120.
* Computed on that owner's **prior year-end** traditional balance, snapshotted before this year's
  growth.
* Gated on **age alone**, not on `retired` — a still-working owner past the start age still takes them.
* The distribution is physically forced out into a fresh at-cost taxable lot, after growth and
  **before** any Roth conversion (IRS ordering). An age-gap couple therefore runs two independent
  streams, neither driving the other's IRA.

### Tax Calculation

The engine uses a pluggable `TaxCalculationStrategy` built by `TaxStrategyFactory`:

* **`CombinedTaxCalculator`** — federal + state
* **`FederalOnlyTaxStrategy`** — federal only
* **`NullStateTaxCalculator`** — no-op for states with no income tax

State-specific behaviour lives in `CaliforniaStateTaxCalculator` and
`BracketBasedStateTaxCalculator`, selected by `StateTaxCalculatorFactory`.

Additional obligations layered on top of the ordinary brackets:

| Item | Implementation | Notes |
|---|---|---|
| Age-65+ standard deduction | `FederalTaxCalculator`, `standard_deductions.additional_age65` (V074) | Applied **per qualifying person** — both spouses 65+ while filing jointly get two adders |
| Social Security inclusion | `SocialSecurityTaxCalculator` | IRS two-tier provisional-income formula (max 85% includable), resolved by a fixed-point convergence loop against the portfolio draw |
| Self-employment tax | `SelfEmploymentTaxCalculator` | Part-time / self-employment income sources |
| Rental passive losses | `RentalLossCalculator` | $25k allowance with MAGI phase-out, suspended-loss carryforward |
| Early withdrawal penalty | 10% (IRC 72(t)) | Pre-59½ traditional distributions |
| IRMAA | `IrmaaSurchargeCalculator`, `irmaa_tiers` (V075) | Part B + Part D monthly surcharge x12; keyed on MAGI from **two calendar years prior**; multiplied by the count of Medicare-enrolled (65+, alive) household members |

**Scope limits, stated plainly.** The MAGI used for IRMAA and the Social Security convergence is a
proxy (effective other income + conversion + traditional draw + realized LTCG + ordinary interest);
it does not add back tax-exempt interest. IRMAA is modeled in the **deterministic engine only** —
the Monte Carlo engine does not model it. IRMAA is also gated on `retired`, so working-past-Medicare
scenarios are out of scope. None of this is tax advice; treat the output as planning estimates.

---

## WithdrawalOrder (Pool Sequencing)

`WithdrawalOrder.drawSequence()` is the single source of truth for pool priority, consumed by the
deterministic engine, the Monte Carlo trial simulator, and Roth-conversion scoring alike.

| Value | Strategy |
|---|---|
| `TAXABLE_FIRST` | taxable → traditional → Roth (the default and the parse fallback) |
| `TRADITIONAL_FIRST` | traditional → taxable → Roth |
| `ROTH_FIRST` | Roth → taxable → traditional |
| `PRO_RATA` | Proportional across pools (dispatched ahead of the ordered draw) |
| `DYNAMIC_SEQUENCING` | Traditional up to the bracket ceiling, then taxable, then Roth |

**Dynamic Sequencing** draws traditional dollars up to the remaining space under
`dynamic_sequencing_bracket_rate` (net of other income, the year's RMD and any conversion), then
falls through to taxable and Roth. `PRO_RATA` and `DYNAMIC_SEQUENCING` report the taxable-first
sequence from `drawSequence()`, which is the documented fallback for paths that reach a plain
ordered draw (dynamic sequencing with no bracket rate configured, and the Monte Carlo trial path,
which has no proportional mode).

Before age 59½ the draw is restricted to the taxable pool where the caller opts into that guard, and
any traditional dollars that are drawn early attract the 10% IRC 72(t) additional tax.

---

## MonteCarloSpendingOptimizer

**Entry point:** `optimize(GuardrailOptimizationInput input)` → `GuardrailProfileResponse`

The optimizer finds the highest sustainable constant-real spending plan the portfolio supports at the
requested **target success probability**. `GuardrailProfileService` resolves that target from the
scenario's risk tolerance (see below) unless an explicit confidence level is supplied.

### Stage 1 — Context preparation (`OptimizationContextBuilder`)

Resolves pool balances and taxable cost basis, builds the Monte Carlo return paths, projects
deterministic per-year income (with the two-tier Social Security taxable share applied), builds the
per-year exact ordinary and LTCG tax tables, the dynamic-sequencing bracket ceilings and the rental
income delta. Also resolves household transition indices and, for a stochastic-mortality run, the
per-trial mortality draws and the joint/survivor regime arrays.

### Stage 2 — Portfolio path generation (`PortfolioPathGenerator`)

Each trial draws **one** block-bootstrap index sequence into the shared `RealReturnMatrix`
(`BlockBootstrapReturnGenerator`, expected block length **5 years**, geometric block termination).
Because the index sequence is shared across all pools and accounts within a trial, cross-asset
correlation and return autocorrelation (momentum / mean reversion) are preserved. Each account's real
return is then resolved from that sequence — fixed override or allocation blend — the scenario fee
rate is subtracted once per pool, and each pool grows at the balance-weighted average of its
accounts' returns.

### Stage 3 — Roth conversion search (`JointConversionSearch`)

Skipped entirely when `optimize_conversions` is off, there is no traditional balance, or no federal
tax calculator is wired.

* **Dynamic Sequencing mode:** the tax-minimization schedule from `RothConversionOptimizer.optimize()`
  is used directly. Competing conversions against DS in a joint search is wrong — DS already draws
  traditional for spending, which makes conversions look artificially expensive.
* **Every other withdrawal order:** a **joint search over conversion fractions scored by sustainable
  spending**. A 21-point grid (`JOINT_GRID_SIZE = 20`, fractions `i/20` for `i = 0..20`) is evaluated,
  then **10 golden-section-style ternary iterations** within ±0.10 of the grid winner. Each candidate
  is scored on a **separate, smaller path set** — `min(500, trialCount)` trials on an rng seeded
  `seed + 1` — via `SustainabilitySearch.evaluateSustainableSpending`.
* **Joint-optimum arm scoring (T26):** each arm is scored under the *same* objective the downstream
  discretionary search will gate on, so an arm tuned for the no-adaptation objective cannot leave
  spending on the table once the gate flips to with-rules. Cross-search coherence holds empirically
  on the pinned fixtures; it is not a proven theorem (the arm search is local and uses a different
  path set from the final numbers).

### Stage 4 — Discretionary allocation and smoothing

`SustainabilitySearch.allocateSpending` binary-searches the maximum sustainable uniform discretionary
level (**40 iterations**, ceiling `MAX_SPENDING_CEILING = $500,000`) per phase window. Phases with a
`target_spending` cap the found level to `target − average floor`; otherwise phases are filled
greedily in priority-weight order. `SpendingSmoother` then applies phase blending and year-over-year
smoothing bounded by `max_annual_adjustment_rate`, after which the plan is re-verified and scaled
down by 5% per iteration (up to 10 iterations) if smoothing broke sustainability.

### Stage 5 — Terminal pass and response (`GuardrailResponseBuilder`)

Runs the headline simulation over the final schedule to produce per-year P10/P25/median balances,
final-balance statistics, the success probability, the spending corridor, the conversion schedule
response, and the disclosure block.

### Key Parameters

| Parameter | Value | Meaning |
|---|---|---|
| Trial count | `DEFAULT_TRIAL_COUNT` = **5000** (request-overridable) | Trials per evaluation |
| Search trials (conversion arms) | `min(500, trialCount)` | Smaller path set for arm scoring |
| Block length | 5 years | Expected bootstrap block size |
| Confidence level | From risk tolerance, default 0.95 | Target success probability |
| `MAX_SPENDING_CEILING` | $500,000 / year | Binary-search upper bound |
| Phase binary search | 40 iterations | `binarySearchDiscretionary` |
| Spending binary search | 30 iterations | `evaluateSustainableSpending` |
| `CASH_REPLENISHMENT_RATE` | 10% | Equity → cash-reserve transfer rate |
| Cash reserve | 2 years @ 1.5% real (defaults) | `cash_reserve_years`, `cash_return_rate` |

---

## The Success Metric — Essential Floor Funded

Since the realism-v2 work, **success is defined as funding the essential floor in every simulated
year**, not as avoiding portfolio depletion. In `TrialSimulator`, a trial-year computes
`resources = income + pool draws + cash drawn`; if that falls below the year's floor, the whole trial
is marked unsuccessful.

`SustainabilitySearch.isSustainable` gates on that success rate:

1. **Primary gate:** `successCount / trialCount >= confidenceLevel`.
2. **Optional bequest constraints, layered on top** and only when the caller set a positive value:
   the terminal balance at the `1 − confidence` percentile must reach `terminal_balance_target`, and
   the minimum-balance at the same percentile must reach `portfolio_floor`. Neither drives
   sustainability on its own any more.

Before the search runs, `verifyEssentialFloor` checks the floor against portfolio capacity at the
confidence percentile and **clamps it down** where it is simply unaffordable. When that clamp fires,
the response discloses `floor_reduced` plus an `original_floor_success_probability` measured against
the user's unclamped floor in one extra read-only pass.

### Risk Tolerance *is* the Target Success Probability

`GuardrailProfileService.resolveConfidence`:

| Risk tolerance | Target success probability |
|---|---|
| `conservative` | **0.95** |
| `moderate` | **0.90** |
| `aggressive` | **0.80** |
| (none / unrecognised) | 0.95 (`DEFAULT_CONFIDENCE`) |

An explicit `confidence_level` on the request takes precedence over risk tolerance.

### The Gate-on-Adaptive-Rules Toggle

`guardrail_spending_profiles.gate_on_adaptive_rules` (V076) selects **which** success metric certifies
the search. With the toggle on and a positive `max_annual_adjustment_rate`, each candidate schedule is
evaluated with the simulated guardrail-adaptation rule active (spending cut toward the trial's own
portfolio ratio in down markets, floor-inviolate, never above plan) and the gate uses *that* success
rate. With it off, the search takes the original single no-adaptation pass.

* V076 added the column with `DEFAULT false` so existing profiles kept their original gate.
* **V077 flipped the column default to `true`**, and `GuardrailProfileService` resolves an
  absent/null request flag to `true`. **New optimizations gate on the with-rules metric by default.**
  Existing rows were deliberately not backfilled — a pre-V077 profile keeps gating on `false` until
  re-optimized. Explicit `false` is always honoured and is the conservative anchor.
* `GuardrailProfileResponse.Disclosure.gatedOn` reports which metric actually certified the run.

---

## RothConversionOptimizer

**Scope:** package-private, not a Spring bean. Instantiated by `JointConversionSearch` (the Monte
Carlo path). The deterministic engine does **not** use it — its conversions come from
`params_json` (`annual_roth_conversion`, or the `fill_bracket` strategy with `target_bracket_rate`
inside `MultiPool`), or from a `GuardrailSpendingInput`'s frozen conversion schedule.

**Goal:** find the conversion fraction (the share of the year's remaining bracket space to convert)
that minimises **lifetime total tax** while keeping the schedule *feasible*.

`RothConversionOptimizer` is a thin orchestrator: it derives a `RothConversionConfig`, computes the
target traditional balance, and delegates the year-by-year simulation to `ConversionSimulator` and
the fraction search to `FractionSearch`. It holds no random state — identical inputs always yield an
identical schedule.

### Target-balance approach

Rather than using portfolio exhaustion as the terminal constraint, the optimizer anchors on a
**target traditional balance at RMD start age**:

```
availableForRmd  = grossBracketCeiling x (1 − rmdBracketHeadroom) − otherTaxableIncomeAtRmdAge
targetBalance    = availableForRmd x distributionPeriod(rmdStartAge)
```

`grossBracketCeiling` comes from `rmd_target_bracket_rate` (default 0.12), priced flat-real;
`rmdBracketHeadroom` defaults to 0.10. `ConversionSimulator` then caps each year's conversion at the
excess of the traditional balance over `targetBalance` discounted back from RMD age at `returnMean`.

### Search

* **Grid scan:** 50 candidate fractions (`i/50`, `i = 1..50`) plus the cached fraction-0 baseline.
* **Ternary refinement:** 20 iterations within ±0.05 of the grid winner.
* **Scoring:** lifetime tax, with a **feasibility preference** — a schedule whose traditional balance
  at RMD start age is within `1.05 x targetBalance` is *always* preferred over an infeasible one
  (infeasible candidates carry a `1e12` penalty in the refinement's direction test).

### Per-year simulation (`ConversionSimulator`)

```
grow all three pools at returnMean (real, fee-adjusted)
  -> conversions (pre-RMD-age only, MAGI convergence loop)
  -> RMDs (Uniform Lifetime Table; after-tax remainder credited back to taxable)
  -> essential-floor spending withdrawal via the configured WithdrawalOrder
```

* **MAGI convergence:** conversions raise MAGI, which changes rental passive-loss deductibility, so
  the conversion amount is iterated up to **3 times** with a **$100** convergence tolerance.
* **Loss utilisation floor:** when rental losses create tax-free conversion capacity, the simulator
  converts at least enough to use them.
* **Affordability constraint (pre-59½ only):** conversion tax must be payable from the taxable
  account without eating into essential spending; a 30-iteration binary search finds the maximum
  affordable amount. From 59½ the tax drains through the taxable → traditional → Roth cascade.
* **Frame discipline (audit C4):** `returnMean` must already be a **real, fee-adjusted** rate,
  because the bracket ceilings this class prices against are flat-real. It is resolved once per run
  by `OptimizationContextBuilder.resolveReturnMean` — the allocation-blended, fee-adjusted real
  return by default, or an explicit (nominal) `return_mean` Fisher-converted and fee-netted.

### Output — `RothConversionSchedule`

```java
record RothConversionSchedule(
    double[] conversionByYear,          // annual conversion amounts
    double[] conversionTaxByYear,       // tax cost of each conversion
    double[] traditionalBalance,        // traditional pool balance per year
    double[] rothBalance,
    double[] taxableBalance,
    double[] projectedRmd,              // projected RMDs under this schedule
    double lifetimeTaxWith,             // total lifetime tax WITH conversions
    double lifetimeTaxWithout,          // the fraction-0 baseline
    int exhaustionAge,
    boolean exhaustionTargetMet,        // exhaustionAge <= endAge − exhaustionBuffer
    double conversionFraction,
    double targetTraditionalBalance
)
```

---

## Household and Survivor Modeling

A scenario becomes a household when `params_json.spouse_birth_year` is present. Both engines then
run **owner-aware pools**: the traditional and Roth pools carry a primary and a spouse slice, and
taxable lots are tagged `joint` / `primary` / `spouse`.

Death ages are fixed inputs (explicit, or resolved from an SSA default) unless stochastic mortality
is enabled. At the **first death**, one atomic transition fires — once, in the transition year, after
that year's growth and both owners' year-of-death RMDs, before any conversion or draw:

1. **Spousal rollover** — the decedent's traditional and Roth balances transfer to the survivor's own
   pools (conservation-preserving).
2. **Basis step-up, per owner** — each taxable lot steps by its own owner's statutory rate: the
   decedent's own lots fully, the survivor's not at all, and joint lots at
   `communityProperty ? 1.0 : 0.5`. Decedent-owned lots retag to the survivor.
3. **Social Security keep-larger** — the survivor keeps the larger of the two benefits; other
   deceased-owned income continues at that source's `survivor_percent` (V079).
4. **Spending scales by the survivor factor** — 0.75 by default, clamped to `[0.5, 1.0]`.
5. **Filing status flips MFJ → SINGLE** for that year and every later year.

The transition is whole-year, not prorated to the date of death — consistent with the engine's
existing whole-year `retired` convention. Related per-person behaviour:

* Per-owner RMD streams, each on that owner's own SECURE-2.0 start age (see above).
* Owner-age income windows: a spouse-owned, age-gated income source activates at the **spouse's**
  age, in both engines.
* Per-person thresholds: the age-65 additional standard deduction counts each qualifying spouse;
  the IRMAA surcharge multiplies by the number of Medicare-enrolled members 65+.
* When the **second** death falls inside the horizon, the projection truncates there; the final row's
  balance is the bequest. Monte Carlo trials carry that bequest forward across the unsimulated tail
  so tracked year-balances stay flat rather than collapsing to zero.

### Stochastic Mortality (opt-in, success-probability only)

`params_json.stochastic_mortality` enables a **separate evaluation pass** that sits *beside* the
recommendation. Requirements: the toggle on, a two-person household, and a loaded mortality table.

* **Data:** `mortality_rates` (V080) holds sex-specific SSA period-life `qx` values, seeded by
  `R__seed_mortality_rates`. The table is only loaded when the toggle is on.
* **Separate rng stream:** mortality draws use their own `Random`, seeded
  `seed + MORTALITY_SEED_OFFSET` (`0x4D4F5254`, the ASCII bytes of "MORT"), kept distinct from the
  return-path stream (offset 0) and the conversion-search stream (offset +1). Drawing death ages
  therefore never perturbs the return draws.
* **Per-trial draws:** each spouse's death age is sampled conditional on being alive at their
  retirement-year age, then mapped to transition/truncate indices through the *same*
  `HouseholdContext.of` + `HouseholdIndexMath` used by the fixed-death path.
* **Three-regime splice:** the base arrays are the JOINT (both-alive) phase; from each trial's own
  sampled first-death index the trial splices in that trial's survivor identity's regime
  (`PRIMARY_SURVIVES` / `SPOUSE_SURVIVES`) — that survivor's keep-larger-SS income, SINGLE-filer tax
  tables at that survivor's own age — and re-applies the survivor spending factor from *its* index.
* **The recommendation is not re-derived.** The pass re-runs trials over the already-optimized
  fixed-death schedule and reports only success statistics. With the toggle off (or a single-person
  scenario) the summary is `null` and the public response is byte-identical.
* **Longevity-conditional metrics** (`StochasticMortalitySummary`): the unconditional lifetime
  success probability; the success rate restricted to trials whose survivor reached
  `longevity_conditional_age` (default **95**) together with the fraction of trials that qualify;
  and P10/median/P90 of the sampled first- and second-death ages. An early death is never counted as
  a failure by itself — such a trial succeeds if its floor was met over its own shorter horizon.

---

## IncomeSourceProcessor

Processes the full set of income sources for a projection year:

* **Social Security** — the IRS two-tier provisional-income formula via `SocialSecurityTaxCalculator`
  (at most 85% includable). The deterministic engine resolves it with a fixed-point convergence loop
  against the portfolio draw; the Monte Carlo engine uses a single-pass approximation whose ordinary
  base is the expected draw (`essential floor − total income`), treated wholly as ordinary income —
  an upper bound.
* **Pension / annuity** — ordinary income.
* **Part-time employment** — subject to self-employment tax via `SelfEmploymentTaxCalculator`.
* **Rental income** — passive; `RentalLossCalculator` computes passive losses (depreciation,
  expenses) and applies the $25k allowance's MAGI phase-out, carrying forward suspended losses.

---

## SpendingPlan Type Hierarchy

```java
public sealed interface SpendingPlan
        permits TierBasedSpendingPlan, GuardrailSpendingInput {

    ResolvedYearSpending resolveYear(int year, int age, int yearsInRetirement,
                                     BigDecimal inflationRate, BigDecimal activeIncome);

    default Optional<Map<Integer, BigDecimal>> conversionSchedule() {
        return Optional.empty();
    }
}
```

**`TierBasedSpendingPlan`** — wraps a `SpendingProfileEntity`'s tiers. Each tier is
`(name, startAge, endAge, essentialExpenses, discretionaryExpenses)`; `endAge` may be null (open
ended). Resolution by age: exactly one matching tier wins; **overlapping** tiers are averaged;
an age that falls in a **gap** takes the midpoint of the bracketing tiers; with no tier data at all
it falls back to the profile's top-level essential/discretionary amounts. Amounts are constant real —
there is no per-tier inflation override or COLA field today.

**`GuardrailSpendingInput`** — wraps the year-indexed `GuardrailYearlySpending` array from an
optimization result. Simple year-based lookup (missing year → zero), and it is the only plan that can
carry a pre-computed Roth conversion schedule. Because the optimizer already survivor-scales the
schedule end to end, the deterministic engine consumes a guardrail plan at a survivor factor of
`1.0` — re-applying the year factor would double-scale it.

### Exactly One Active Plan

`projection_scenarios` carries two nullable FK columns, `spending_profile_id` and
`guardrail_profile_id`, and they are **mutually exclusive**. `ProjectionScenarioEntity` enforces the
XOR through paired mutators rather than raw setters:

* `activateSpendingProfile(...)` sets the spending profile **and clears the guardrail profile**.
* `activateGuardrailProfile(...)` sets the guardrail profile **and clears the spending profile**.
* `clearSpendingProfile()` / `clearGuardrailProfile()` clear one side only.

`ScenarioCrudService.updateScenario` calls `activateSpendingProfile` when a profile id is supplied and
`clearSpendingProfile` when it is not (guardrail profiles are managed by the optimizer, not the
scenario edit form); `GuardrailProfileService.optimize` activates the guardrail side. Editing a
scenario also recomputes the scenario hash and marks any existing guardrail profile **stale** when it
changes.

**When neither is set,** the engine falls back to the configured `WithdrawalStrategy` at the scenario's
withdrawal rate (default 4%). The UI presents all three cases through a single unified
"Spending Plan" dropdown, and `ScenarioResponse` carries both `spending_profile` and
`guardrail_profile` summaries so it can display whichever is active.
