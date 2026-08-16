# Roth Conversion Optimizer — Design Document

> **Status: SHIPPED, and since evolved.** This document was written before implementation. The
> problem statement and design intent below still describe why the feature exists and are kept as
> written. Everything under a **"What shipped"** callout records where the implementation differs
> from — or went beyond — the original design. Where the two conflict, the code is the authority:
> `backend/wealthview-projection/src/main/java/com/wealthview/projection/` —
> `RothConversionOptimizer`, `ConversionSimulator`, `FractionSearch`, `JointConversionSearch`.
> See also `backend/src/site/markdown/projection-engine.md`.

## Problem Statement

A retiree with a large traditional IRA faces a tax timing problem:
- Traditional withdrawals are taxed as ordinary income
- RMDs starting at age 73 force withdrawals that may push into high tax brackets
- Roth conversions before age 73 can reduce the traditional balance, lowering future RMDs
- But conversions are taxable income NOW — converting too aggressively wastes money on taxes

The optimizer should find the annual Roth conversion amount that minimizes lifetime
tax while maintaining portfolio sustainability at the user's confidence level.

> **What shipped — RMD start age.** Under SECURE 2.0 the start age depends on birth year, and the
> implementation honours that: **73** for owners born before 1960, **75** for those born 1960 or
> later (`RmdCalculator.rmdStartAge`). It is resolved per owner, so a household runs two independent
> RMD streams.

## Design Goal

**Target constraint:** Keep the traditional balance small enough by age 73 that RMDs
don't push the user beyond the 12% federal tax bracket (MFJ: ~$96,950 taxable income
= ~$126,950 gross with $30K standard deduction). For simplicity, flag when RMDs would
exceed ~$100K/year and can't be avoided.

**Optimization variable:** Annual Roth conversion amount (may vary by phase/age range).

**Objective:** Maximize after-tax sustainable spending across the full retirement horizon.

> **What shipped — the optimization variable is a *fraction*, not an amount.** The search variable
> is a single scalar **conversion fraction** in `[0, 1]`: the share of the year's remaining space
> under `conversion_bracket_rate` (default 0.22) to convert. `ConversionSimulator` turns that one
> scalar into a full year-by-year schedule, so conversions vary by age naturally as bracket space,
> income and the target balance change — without a per-phase parameter.
>
> **Both objectives shipped, at different layers.** `RothConversionOptimizer.optimize()` minimizes
> **lifetime total tax** (with a hard feasibility preference, below). The Monte Carlo layer's
> `JointConversionSearch` instead scores candidate fractions by **sustainable spending**. Which one
> runs depends on the withdrawal order — see *Optimization Algorithm*.
>
> **The "flag when RMDs exceed ~$100K" simplification was not built.** It was replaced by the
> target-balance approach and the `exhaustion_target_met` flag.

## Architecture

### New Component: `RothConversionOptimizer`

A new optimizer that wraps the existing `MonteCarloSpendingOptimizer` and adds a
conversion search layer on top.

```
RothConversionOptimizer
  ├── For each candidate conversion schedule:
  │     ├── Simulate conversions reducing traditional → increasing Roth
  │     ├── Compute conversion tax per year (at marginal rate)
  │     ├── Pass modified pool balances to MonteCarloSpendingOptimizer
  │     └── Get sustainable spending result
  ├── Binary search for optimal conversion amount
  └── Return: conversion schedule + spending recommendation + RMD projections
```

> **What shipped — the nesting is inverted.** `RothConversionOptimizer` does **not** wrap the Monte
> Carlo optimizer. It sits *inside* it, as one stage of the optimization pipeline, and it is
> package-private (not a Spring bean). It also holds no random state: identical inputs always yield
> an identical schedule.
>
> ```
> MonteCarloSpendingOptimizer  (@Component)
>   ├── OptimizationContextBuilder        (pools, return paths, income, tax tables)
>   ├── JointConversionSearch             <- the conversion search layer
>   │     └── RothConversionOptimizer     (package-private orchestrator)
>   │           ├── ConversionSimulator   (deterministic year-by-year sim for one fraction)
>   │           └── FractionSearch        (grid + ternary over fractions, scored by lifetime tax)
>   ├── SustainabilitySearch              (discretionary spending, the success gate)
>   └── GuardrailResponseBuilder          (terminal pass + response)
> ```
>
> Rather than re-running the *whole* MC optimizer per candidate (the original sketch, and the source
> of the 60-second estimate below), `JointConversionSearch` scores each candidate with one
> `evaluateSustainableSpending` call on a smaller, separately seeded path set.

### Three-Pool Evolution With Conversions

The existing `isSustainable` method tracks three pools per trial. Adding conversions:

```
for each year y in pre-RMD period (retirement to age 72):
    1. Apply growth to all three pools
    2. Execute Roth conversion: traditional -= convAmount, roth += convAmount
    3. Compute conversion tax (at marginal rate on convAmount + other income)
    4. Deduct conversion tax from pools (taxable → traditional → roth cascade)
    5. Execute spending withdrawal from pools (existing logic)
    6. Compute withdrawal tax on any traditional withdrawal (existing logic)
    7. Deposit surplus to taxable (existing logic)

for each year y in RMD period (age 73+):
    1. Apply growth to all three pools
    2. Compute RMD from traditional balance using Uniform Lifetime Table
    3. Withdraw max(RMD, spending_need_from_traditional) from traditional
    4. If RMD > spending need, deposit excess to taxable (it's forced income)
    5. Compute tax on traditional withdrawal (RMD or voluntary)
    6. Execute remaining spending from other pools
    7. Deposit surplus to taxable
```

> **What shipped.** `ConversionSimulator.simulateForFraction` follows this shape, with these
> refinements:
>
> * **Ordering is growth → conversion → RMD → spending draw**, matching IRS ordering (the RMD is
>   distributed before any conversion in the same year is *credited*, and conversions only run
>   pre-RMD-age).
> * **Conversion tax funding is age-dependent.** Before 59½ the tax is paid from taxable only (and
>   constrained by affordability, below); from 59½ it drains through the
>   taxable → traditional → Roth cascade (`PoolTaxCascade`).
> * **The after-tax RMD remainder is credited back to the taxable pool** (audit C4). A forced RMD
>   does not vanish — the owner receives it and, absent an offsetting spending need, holds it. Losing
>   those proceeds artificially penalised the *no-conversion* arm.
> * **MAGI convergence loop.** Conversions raise MAGI, which changes rental passive-loss
>   deductibility, which changes the effective income the conversion is priced against. The
>   conversion amount is iterated up to **3 times** with a **$100** convergence tolerance.
> * **Loss utilisation floor.** When rental losses create tax-free conversion capacity, the simulator
>   converts at least enough to use them.
> * **Everything is constant-real.** Pools grow at a real, fee-adjusted `returnMean`; bracket
>   ceilings are priced flat-real. Feeding a nominal rate here would overstate future traditional
>   growth relative to the flat ceilings, overstate RMD pressure, and bias conversions upward.

### RMD Computation

The Uniform Lifetime Table (IRS Publication 590-B, Table III) provides a
distribution period for each age. RMD = prior-year-end traditional balance /
distribution period.

Key values:
| Age | Distribution Period |
|-----|-------------------|
| 73  | 26.5              |
| 75  | 24.6              |
| 80  | 20.2              |
| 85  | 16.0              |
| 90  | 12.2              |
| 95  | 8.9               |
| 100 | 6.4               |

Implementation: a static lookup table in a new `RmdCalculator` class.

> **What shipped.** Exactly this. `RmdCalculator` carries the full table for ages **72–120** and
> returns 0 outside that range. It is stateless, package-private, and shared by the conversion
> simulator, the deterministic engine (via `RmdStreamCalculator`) and the Monte Carlo trial loop.

### Optimization Algorithm

**Outer search: conversion amount per year**

Binary search on annual conversion amount in `[0, bracket_ceiling - other_income]`:
- Lower bound: $0 (no conversion)
- Upper bound: fill to the 22% or 24% bracket ceiling (from existing fill_bracket logic)

For each candidate amount:
1. Project the traditional balance forward to age 73 (accounting for conversions + tax)
2. Compute projected RMDs from age 73+ using the Uniform Lifetime Table
3. Check: do RMDs stay under the 12% bracket ceiling?
4. Run `MonteCarloSpendingOptimizer.optimize()` with the modified starting pool
   balances (traditional reduced, Roth increased, taxable reduced by conversion tax)
5. Record the sustainable spending level

The optimal conversion amount maximizes sustainable spending while keeping
projected RMDs within the target bracket.

**Inner optimization: spending (existing MC optimizer)**

The existing MC optimizer handles spending optimization with three-pool
tracking and withdrawal tax. It receives the post-conversion pool balances
and produces sustainable spending.

> **What shipped — two searches, selected by withdrawal order.**
>
> **(a) Tax-minimizing search — `FractionSearch`.** Not a binary search: a **50-point grid scan**
> (`i/50`, `i = 1..50`) against the cached fraction-0 baseline, then **20 ternary-refinement
> iterations** within ±0.05 of the grid winner. Candidates are scored on **lifetime total tax**,
> with a **feasibility preference that dominates tax**: a schedule whose traditional balance at RMD
> start age is within `1.05 x targetTraditionalBalance` always beats an infeasible one (the
> refinement's direction test adds a `1e12` penalty to infeasible candidates).
>
> **(b) Joint spending-conversion search — `JointConversionSearch`.** For every withdrawal order
> *except* dynamic sequencing, the fraction is chosen by **maximum sustainable spending**, not by
> lifetime tax: a **21-point grid** (`i/20`, `i = 0..20`) plus **10 ternary iterations** within ±0.10
> of the winner. Each arm is scored with `SustainabilitySearch.evaluateSustainableSpending` on a
> smaller, separately seeded path set — `min(500, trial_count)` trials on rng `seed + 1`.
>
> **Dynamic Sequencing takes path (a) directly.** DS already draws traditional dollars for spending,
> so a joint search competes the two strategies against each other and makes conversions look
> artificially expensive. In DS mode the tax-minimization schedule is used as-is.
>
> **Joint-optimum arm scoring (T26).** Each arm is scored under the *same* objective the downstream
> discretionary search will gate on (`gate_on_adaptive_rules`), so a schedule tuned for the
> no-adaptation objective cannot leave spending on the table once the gate flips to with-rules.
> Cross-search coherence holds empirically on the pinned fixtures; it is not a proven theorem — the
> arm search is local and its final numbers come from a different path set than arm selection.
>
> **Target-balance approach replaced the "RMDs under 12%" check.** Instead of a pass/fail bracket
> test, the optimizer computes a concrete balance to aim for at RMD start age:
>
> ```
> availableForRmd = grossBracketCeiling(rmd_target_bracket_rate) x (1 − rmd_bracket_headroom)
>                   − otherTaxableIncomeAtRmdAge   (+ the rental tax adjustment at that age)
> targetBalance   = availableForRmd x distributionPeriod(rmdStartAge)
> ```
>
> Defaults: `rmd_target_bracket_rate` 0.12, `rmd_bracket_headroom` 0.10. Each year's conversion is
> then capped at the excess of the traditional balance over `targetBalance` discounted back from RMD
> age at `returnMean`. This avoids the all-or-nothing failure mode of an exhaustion-based constraint.
>
> **Affordability constraint (pre-59½ only).** The conversion tax must be payable from the taxable
> account without eating into essential spending. A **30-iteration binary search**
> (`findMaxAffordableConversion`) finds the largest conversion whose incremental tax fits the
> available budget.
>
> **Inflation indexing of brackets — the design's assumption does not apply.** The whole projection
> runs in **constant-real (today's-dollar)** terms, so bracket ceilings are priced at their seeded
> values with **no** annual indexing (`computeMaxIncomeForBracket(..., ZERO)`), matching the flat
> real growth rate the pools compound at. `FederalTaxCalculator` retains an inflation-indexing
> parameter, but it only engages as a fallback when the requested tax year has no seeded brackets at
> all. The thresholds that *are* deflated are the ones Congress froze in nominal terms — the Social
> Security provisional-income thresholds and the NIIT threshold — so their real erosion is modeled.

### Integration With Existing Fill-Bracket Strategy

The deterministic projection engine already has a `fill_bracket` Roth conversion
strategy. The new optimizer can be seen as the MC-aware version of the same concept:
- `fill_bracket` in the deterministic engine: converts up to a target bracket each year
- `RothConversionOptimizer`: finds the OPTIMAL target bracket (or conversion amount)
  that maximizes after-tax sustainable spending across MC scenarios

The optimizer's output can feed back into the deterministic engine as a
`GuardrailSpendingInput` that includes both the spending schedule AND the
conversion schedule.

> **What shipped — exactly as designed, and this is the integration seam.** The deterministic
> engine's `fill_bracket` / `fixed_amount` strategies live in `PoolStrategy.MultiPool`, driven by
> `params_json` (`roth_conversion_strategy`, `target_bracket_rate`, `annual_roth_conversion`,
> `roth_conversion_start_year`). `SpendingPlan.conversionSchedule()` is the feedback channel:
> `GuardrailSpendingInput` is the only implementation that returns a non-empty schedule, and
> `YearFinanceResolver` reads it in preference to the scenario's own strategy. `TierBasedSpendingPlan`
> never carries one.

### Data Model Changes

**New fields on `GuardrailOptimizationInput`:**
```java
boolean optimizeConversions;       // enable the conversion optimizer
BigDecimal rmdTargetBracketRate;   // target bracket for RMD constraint (default 0.12)
int rmdStartAge;                   // default 73 (SECURE 2.0)
```

**New fields on `GuardrailProfileResponse`:**
```java
BigDecimal recommendedAnnualConversion;  // the optimal conversion amount
int conversionEndAge;                     // age to stop converting (typically 72)
BigDecimal projectedRmdAtAge73;           // estimated first RMD
boolean rmdExceedsTarget;                 // flag: can't get RMDs below target bracket
List<ConversionYearRecommendation> conversionSchedule;  // per-year conversion amounts
```

**New record:**
```java
record ConversionYearRecommendation(int year, int age, BigDecimal conversionAmount,
    BigDecimal estimatedTax, BigDecimal traditionalBalanceAfter, BigDecimal projectedRmd)
```

> **What shipped.** `GuardrailOptimizationInput` carries `optimizeConversions`,
> `conversionBracketRate`, `rmdTargetBracketRate`, `traditionalExhaustionBuffer`,
> `rmdBracketHeadroom` and `dynamicSequencingBracketRate`. There is **no `rmdStartAge` field** — it
> is derived per owner from birth year via `RmdCalculator.rmdStartAge`.
>
> The response nests everything in one block instead of flattening it onto
> `GuardrailProfileResponse`:
>
> ```java
> record RothConversionScheduleResponse(
>     BigDecimal lifetimeTaxWithConversions,
>     BigDecimal lifetimeTaxWithout,
>     BigDecimal taxSavings,
>     int exhaustionAge,
>     boolean exhaustionTargetMet,
>     BigDecimal conversionBracketRate,
>     BigDecimal rmdTargetBracketRate,
>     int traditionalExhaustionBuffer,
>     BigDecimal mcExhaustionPct,          // share of MC trials that exhausted traditional
>     BigDecimal targetTraditionalBalance,
>     BigDecimal rmdBracketHeadroom,
>     List<ConversionYearDetail> years)
>
> record ConversionYearDetail(
>     int calendarYear, int age,
>     BigDecimal conversionAmount, BigDecimal estimatedTax,
>     BigDecimal traditionalBalanceAfter, BigDecimal rothBalanceAfter,
>     BigDecimal projectedRmd, BigDecimal otherIncome,
>     BigDecimal totalTaxableIncome, String bracketUsed)
> ```
>
> `recommendedAnnualConversion`, `conversionEndAge`, `projectedRmdAtAge73` and `rmdExceedsTarget`
> were **not** built — the per-year `years` list supersedes them, `exhaustionTargetMet` covers the
> warning case, and `bracketUsed` is currently always null on the write path.
>
> Persistence: `guardrail_spending_profiles.conversion_schedule` is a `jsonb` column mapped with
> `@JdbcTypeCode(SqlTypes.JSON)`, alongside `conversion_bracket_rate` and the other knobs.

### Frontend UX

The optimizer results page would gain:
- A "Roth Conversion Strategy" section showing the recommended conversion schedule
- A chart showing traditional balance declining over pre-RMD years
- Projected RMD amounts at age 73+ with the 12% bracket ceiling overlaid
- A warning banner when RMDs can't be kept below the target bracket
- A comparison: "With conversions: sustainable spending = $X. Without: $Y."

> **What shipped.** `ConversionScheduleTable`, `TraditionalBalanceChart` and `TaxSavingsSummary` on
> the optimizer results view. The headline comparison is expressed as **lifetime tax with vs.
> without conversions plus the savings**, not as a sustainable-spending delta. The scenario form's
> `RothConversionSection` configures the *deterministic* engine's `fixed_amount` / `fill_bracket`
> strategy and is a separate surface from the optimizer's output.

### Performance Considerations

The outer conversion search adds another dimension to the optimization. With
~20 binary search iterations for the conversion amount, each running the full
MC optimizer (which is ~3 seconds for 10,000 trials), the total time would be
~60 seconds. This is acceptable for a one-time optimization but should be
communicated to the user with a progress indicator.

Optimization: the outer search can use fewer trials (1,000) for the search
phase and then run the final result with the full trial count.

> **What shipped.** The reduced-trial idea landed, at a lower number: arm scoring uses
> `min(500, trial_count)` trials (`JOINT_SEARCH_TRIALS = 500`), and only the final schedule is
> evaluated at the full trial count (default 5,000) in the terminal pass. The full MC optimizer is
> never re-entered per candidate — each arm costs one `evaluateSustainableSpending` call, i.e. a
> 30-iteration bisection over the reduced path set. Two further savings: the fraction-0 baseline
> simulation is cached on the optimizer instance (the joint search asks for it ~41 times), and the
> per-year ordinary and LTCG tax tables are precomputed once per run into primitive arrays so the
> hot loop does no `BigDecimal` work.

### Test Strategy

1. **Unit: RMD computation** — verify against IRS table values
2. **Unit: conversion reduces traditional balance** — verify pool evolution
3. **Unit: conversion tax computed correctly** — marginal rate on conversion
4. **Integration: all-traditional portfolio with conversion vs without** —
   verify that the optimizer recommends conversions and the result has
   lower tax + higher sustainable spending
5. **Integration: RMD constraint** — verify that the recommended conversion
   amount produces RMDs below the target bracket
6. **Integration: small portfolio** — verify graceful handling when the
   portfolio is too small to benefit from conversions
7. **Edge: already Roth-heavy** — verify no conversions recommended
8. **Edge: very large traditional** — verify warning when RMDs can't be
   controlled even with maximum conversions

> **What shipped, additionally.** Because both searches use grid + ternary refinement over a
> non-convex objective, the suites also pin *search soundness* empirically rather than by proof:
> `GuardrailAdaptiveGateIntegrationTest` sweeps the discretionary bisection to assert exactly one
> sustainable→unsustainable transition, and `JointConversionSearchGatedObjectiveTest` pins the
> gated-vs-no-adapt arm-scoring relationship on fixed fixtures. Neither monotonicity property is a
> theorem; both are documented as fixture-pinned.

### Implementation Sequence

1. Add `RmdCalculator` with Uniform Lifetime Table lookup
2. Add conversion-aware pool evolution to `isSustainable`
3. Add outer binary search for conversion amount
4. Add RMD constraint check
5. Wire into `GuardrailProfileService` and API
6. Add frontend conversion schedule display
7. Integration tests

> **Delivered.** Steps 1, 2, 5, 6 and 7 as written; step 3 became the grid + ternary fraction search
> and step 4 became the target-balance cap. The API surface is
> `POST /api/v1/projections/{scenarioId}/optimize` and
> `POST /api/v1/projections/{scenarioId}/guardrail/reoptimize`, both routed through
> `GuardrailProfileService`.
