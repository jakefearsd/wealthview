# Roth Conversion Optimizer — Design Spec

## Problem Statement

A retiree with a large traditional IRA faces a tax timing problem. Traditional
withdrawals are taxed as ordinary income. Required Minimum Distributions (RMDs)
starting at age 73 (or 75 for those born 1960+) force withdrawals that may push
the retiree into high tax brackets. Roth conversions before RMD age can reduce
the traditional balance, lowering future RMDs — but conversions are taxable
income NOW, and converting too aggressively wastes money on taxes and reduces
early retirement spending.

The current Monte Carlo spending optimizer doesn't reason about this tradeoff.
Its existing `fill_bracket` strategy in the deterministic engine converts
aggressively, which can suppress early-retirement spending unnecessarily.

## Design Goal

Build a two-phase optimizer that:

1. **Phase 1 — Minimize lifetime tax:** Find the annual Roth conversion schedule
   that minimizes total lifetime tax (conversion tax + withdrawal tax + RMD tax)
   while satisfying bracket and exhaustion constraints.
2. **Phase 2 — Optimize spending:** Feed the conversion schedule into the
   existing Monte Carlo spending optimizer so sustainable spending is computed
   in the context of the tax-optimized pool evolution.

The user sees: "Here's your conversion plan that minimizes your tax bill, and
here's how much you can spend given that plan."

## Key Constraints

### Bracket Constraints (Soft)

- **Conversion bracket:** User-chosen federal bracket (e.g., 22%). Annual
  conversions + other income should stay within this bracket. The optimizer
  searches for the fraction of available bracket space that minimizes lifetime
  tax — it does NOT necessarily fill the bracket.
- **RMD target bracket:** User-chosen federal bracket (e.g., 12%). RMD-era
  withdrawals from traditional should land within this bracket. Must be ≤
  conversion bracket.

These are soft constraints in service of the primary objective (minimize
lifetime tax). The optimizer may partially fill or fully fill either bracket
if doing so produces the lowest lifetime tax.

### Traditional Exhaustion Constraint (Hard)

Traditional IRA funds must be fully exhausted at least N years before the
plan's end age, where N is a user-configurable buffer (default 5). This
ensures the final years of retirement are spent drawing from Roth/taxable
only — no tax liability, maximum flexibility, no RMD complications.

### 59.5 Early Withdrawal Rule

- **Roth conversions** (traditional → Roth transfers) may occur at any
  retirement age. They are taxable as ordinary income but NOT subject to
  the 10% early withdrawal penalty.
- **Spending withdrawals before age 59.5** come from the taxable pool only.
  Traditional and Roth are off-limits for spending before 59.5 due to the
  10% penalty.
- **Conversion tax payments before 59.5** come from the taxable pool only.
  Paying tax from traditional/Roth would itself be a penalized withdrawal.
- **At age 59.5+**, all three pools are available per the configured
  withdrawal order.

### RMD Start Age (SECURE 2.0)

- Born before 1960 → RMD start age **73**
- Born 1960 or later → RMD start age **75**

## Architecture

### New Component: `RothConversionOptimizer`

A new class in `wealthview-projection` that runs Phase 1. It is NOT a Spring
component — it's a stateless calculator instantiated by `GuardrailProfileService`
or `MonteCarloSpendingOptimizer` with the required inputs.

#### Inputs

- Traditional, Roth, taxable starting balances
- Projected income streams per year (from existing `computeDeterministicIncome()`)
- Birth year → determines RMD start age
- Retirement age
- End age and exhaustion buffer → exhaustion age = endAge - buffer
- Conversion bracket rate and RMD target bracket rate
- Filing status
- Expected return (`returnMean`) for deterministic growth projection
- `FederalTaxCalculator` for bracket ceilings and tax computation

#### Algorithm

**Search variable:** `conversionFraction` ∈ [0.0, 1.0] — the fraction of
available conversion bracket space to use each year.

**Search method:** Ternary search (not binary search). The lifetime tax function
is approximately unimodal — conversion tax increases with fraction while RMD tax
decreases. Ternary search correctly finds the minimum of a unimodal function by
evaluating two interior points per iteration and eliminating the outer third that
cannot contain the minimum. ~40 iterations yield precision to 10⁻¹² on the
fraction, far more than needed.

**For each candidate fraction, forward-simulate the full retirement timeline:**

```
for each year from retirement to endAge:
    age = retirementAge + yearIndex

    1. Apply growth: all pools *= (1 + returnMean)

    2. If pre-RMD and traditional > 0:
       a. bracketSpace = bracketCeiling(conversionBracketRate, filingStatus)
                         - otherIncome[year]
       b. maxConversion = bracketSpace * conversionFraction
       c. If age < 59.5:
            maxAffordableTax = estimateTaxOnConversion(maxConversion, otherIncome)
            if taxableBalance < maxAffordableTax:
                reduce maxConversion until taxableBalance can cover the tax
       d. actualConversion = min(maxConversion, traditionalBalance)
       e. traditional -= actualConversion; roth += actualConversion
       f. conversionTax = computeTax(actualConversion + otherIncome) - computeTax(otherIncome)
       g. Deduct conversionTax from pools:
            - Before 59.5: taxable only
            - At 59.5+: taxable → traditional → roth cascade

    3. If RMD year (age ≥ rmdStartAge) and traditional > 0:
       a. rmd = priorYearEndTraditionalBalance / distributionPeriod(age)
       b. Force withdraw max(rmd, spendingNeedFromTraditional)
       c. If rmd > spending need: deposit excess to taxable (it's forced income)

    4. Execute spending withdrawals:
       a. Before 59.5: taxable pool only
       b. At 59.5+: split across pools per withdrawal order
       c. Spending estimate: use essentialFloor as proxy (see Known Approximations)

    5. Compute withdrawal tax on traditional withdrawals

    6. Accumulate lifetimeTax += conversionTax + withdrawalTax

    7. Track traditionalBalance trajectory and exhaustion year
```

**Scoring:** `lifetimeTax` — lower is better. A candidate is **feasible** if
traditional balance reaches $0 by the exhaustion age. Among feasible candidates,
pick the one with minimum lifetime tax. If no candidate is feasible (traditional
balance too large to exhaust even with maximum conversions), return the best
result with `exhaustionTargetMet = false` and a warning.

**Baseline:** Also evaluate fraction=0.0 to compute "lifetime tax without
conversions" for the comparison display.

#### Output: `RothConversionSchedule`

Internal record (not API-facing) consumed by `MonteCarloSpendingOptimizer`:

```java
record RothConversionSchedule(
    double[] conversionByYear,       // conversion amount per retirement year
    double[] conversionTaxByYear,    // tax cost per year
    double[] traditionalBalance,     // balance trajectory
    double[] projectedRmd,           // RMD per year (0 for pre-RMD years)
    double lifetimeTaxWith,          // total tax with conversions
    double lifetimeTaxWithout,       // total tax without conversions
    int exhaustionAge,               // age traditional reaches $0
    boolean exhaustionTargetMet,
    double conversionFraction        // the optimal fraction found
)
```

### New Component: `RmdCalculator`

Package-private utility class in `wealthview-projection`.

**Responsibilities:**

1. **RMD start age:** `rmdStartAge(int birthYear)` → 73 or 75
2. **RMD computation:** `computeRmd(double priorYearEndBalance, int age)` →
   required distribution amount
3. **Uniform Lifetime Table:** Full static `double[]` lookup for ages 72–120
   (IRS Publication 590-B, Table III)

**Design choice:** Stateless utility, no Spring injection. Used only by
`RothConversionOptimizer` and `MonteCarloSpendingOptimizer`.

**RMD timing rule:** The RMD for a given year is based on the account balance
as of December 31 of the **prior** year. The forward simulation computes RMD
at the start of each year using the end-of-year balance from the prior year
(which is the balance after growth, conversions, and withdrawals from the
prior year).

### Integration with `MonteCarloSpendingOptimizer` (Phase 2)

#### Flow

1. `GuardrailProfileService.optimize()` checks if the request includes
   conversion parameters (`optimizeConversions = true`)
2. If yes, build and run `RothConversionOptimizer` → produces
   `RothConversionSchedule`
3. Pass the schedule into `MonteCarloSpendingOptimizer.optimize()` via
   enhanced `GuardrailOptimizationInput`
4. The MC optimizer's `isSustainable()` uses the schedule during pool evolution
5. The spending binary search finds sustainable spending given the
   conversion-modified pool trajectory

#### Changes to `isSustainable()`

Accept `double[] conversionByYear` and `double[] conversionTaxByYear` from
the schedule (null when conversions not optimized — preserves existing behavior).

For each year in each MC trial:

```
1. Apply growth (existing)
2. If conversionByYear[y] > 0:
   a. pTraditional -= conversionByYear[y]
   b. pRoth += conversionByYear[y]
   c. Deduct conversionTaxByYear[y] from pools:
      - Before 59.5: taxable only. If taxable insufficient, cap at what's available.
      - At 59.5+: taxable → traditional → roth cascade
3. If RMD year and pTraditional > 0:
   a. Compute RMD from prior-year-end pTraditional
   b. Force traditional withdrawal of at least RMD amount
   c. Deposit excess (RMD - spending need from traditional) to taxable
4. Execute spending withdrawal (existing, but modified):
   a. Before 59.5: draw from taxable only
   b. At 59.5+: splitWithdrawal() per withdrawal order (existing)
5. Compute withdrawal tax (existing)
6. Surplus handling (existing)
```

#### Changes to `splitWithdrawal()`

Add an `age` parameter (or a `boolean preAge595`). When `preAge595 = true`,
return all withdrawal from taxable regardless of configured order. Traditional
and Roth are excluded.

#### What doesn't change

- MC trial generation (`runMonteCarloTrials`)
- Spending allocation logic (`allocateSpending`, `binarySearchDiscretionary`)
- Corridor computation and smoothing
- Final simulation for percentile balances (though it also needs the conversion
  schedule baked in for accurate balance trajectories)

#### Performance

Phase 1 is microseconds (~40 deterministic forward simulations of ~30 years).
Phase 2 runs exactly as before — same trial count, same binary search depth.
No meaningful performance impact.

## Data Model Changes

### `GuardrailOptimizationInput` (new fields)

```java
boolean optimizeConversions,          // enable Roth conversion optimizer
BigDecimal conversionBracketRate,     // e.g., 0.22
BigDecimal rmdTargetBracketRate,      // e.g., 0.12 — must be ≤ conversionBracketRate
int traditionalExhaustionBuffer       // years before endAge (default 5)
```

### New API DTOs (`wealthview-core`)

```java
record RothConversionScheduleResponse(
    BigDecimal lifetimeTaxWithConversions,
    BigDecimal lifetimeTaxWithout,
    BigDecimal taxSavings,
    int exhaustionAge,
    boolean exhaustionTargetMet,
    BigDecimal conversionBracketRate,
    BigDecimal rmdTargetBracketRate,
    int traditionalExhaustionBuffer,
    List<ConversionYearDetail> years
)

record ConversionYearDetail(
    int calendarYear,
    int age,
    BigDecimal conversionAmount,
    BigDecimal estimatedTax,
    BigDecimal traditionalBalanceAfter,
    BigDecimal rothBalanceAfter,
    BigDecimal projectedRmd,
    BigDecimal otherIncome,
    BigDecimal totalTaxableIncome,
    String bracketUsed
)
```

### `GuardrailProfileResponse` (new field)

```java
RothConversionScheduleResponse conversionSchedule  // null when not optimized
```

### `GuardrailSpendingProfileEntity` (new columns)

| Column                          | Type          | Default |
|---------------------------------|---------------|---------|
| `conversion_schedule`           | jsonb         | null    |
| `conversion_bracket_rate`       | numeric(5,4)  | null    |
| `rmd_target_bracket_rate`       | numeric(5,4)  | null    |
| `traditional_exhaustion_buffer` | int           | 5       |

### Flyway Migration

`V043__add_roth_conversion_optimizer_fields.sql` — adds the four columns above
to `guardrail_spending_profiles`.

## API Changes

No new endpoints. The existing optimization flow handles everything:

- `POST /api/v1/projections/{scenarioId}/optimize` — request gains optional
  fields: `optimize_conversions`, `conversion_bracket_rate`,
  `rmd_target_bracket_rate`, `traditional_exhaustion_buffer`. When
  `optimize_conversions` is false/absent, behavior is identical to today.
  Response gains `conversion_schedule` (null when not optimized).

- `GET /api/v1/projections/{scenarioId}/guardrail` — response includes
  `conversion_schedule` from persisted entity (null if not optimized).

- `POST /api/v1/projections/{scenarioId}/guardrail/reoptimize` — re-runs
  with persisted conversion parameters.

### Validation

- `rmd_target_bracket_rate` must be ≤ `conversion_bracket_rate`
- `traditional_exhaustion_buffer` must be ≥ 1 and ≤ 20
- Both bracket rates must be valid federal bracket rates (0.10, 0.12, 0.22,
  0.24, 0.32, 0.35, 0.37)

## Frontend Changes

### SpendingOptimizerPage — Form

Add a collapsible "Roth Conversion Strategy" section below existing optimizer
inputs. Collapsed by default.

**Inputs when expanded:**

| Input                | Type     | Default | Notes                                    |
|----------------------|----------|---------|------------------------------------------|
| Optimize Conversions | checkbox | off     | Enables/disables the section             |
| Conversion Bracket   | dropdown | 22%     | Federal brackets: 10–37%                 |
| RMD Target Bracket   | dropdown | 12%     | Must be ≤ conversion bracket             |
| Exhaustion Buffer    | number   | 5       | 1–15 years before plan end               |

### SpendingOptimizerPage — Results

Three new sections appear when `conversion_schedule` is non-null:

**1. Tax Savings Summary (card/banner)**

- "Lifetime tax with conversions: $X"
- "Lifetime tax without conversions: $Y"
- "Estimated savings: $Z"
- Warning banner if `exhaustionTargetMet = false`

**2. Conversion Schedule Table**

Year-by-year table spanning full retirement (conversion years + RMD years):

| Column               | Description                            |
|----------------------|----------------------------------------|
| Age                  | User's age                             |
| Year                 | Calendar year                          |
| Conversion Amount    | Amount converted (0 in RMD era)        |
| Est. Tax             | Tax on conversion or RMD withdrawal    |
| Traditional Balance  | Balance after conversion/withdrawal    |
| Roth Balance         | Balance after conversion               |
| Projected RMD        | RMD amount (0 in pre-RMD era)          |
| Bracket              | Federal bracket landed in              |

This is the "hand it to your CPA" output.

**3. Traditional Balance Chart**

Line chart showing traditional IRA balance from retirement through plan end:

- X-axis: age
- Y-axis: traditional balance ($)
- Primary line: traditional balance trajectory
- Reference line: balance that would produce RMDs at the target bracket ceiling
- Annotations: "Conversion Period", "RMD Period", "Tax-Free Period" (after
  exhaustion)
- Marker at exhaustion age

The existing spending corridors, fan chart, and year-by-year spending table
remain unchanged — they already reflect the conversion-optimized reality from
Phase 2.

## Edge Cases

| Case                                | Behavior                                                    |
|-------------------------------------|-------------------------------------------------------------|
| No traditional balance              | Return no-op result, no conversions recommended             |
| Already past RMD age at retirement  | No pre-RMD conversion years; compute RMD trajectory only    |
| Taxable depleted before 59.5        | Conversions stop (can't pay tax); resume at 59.5 if traditional remains |
| Very large traditional balance      | Maximum conversions may not exhaust on time; `exhaustionTargetMet = false` with warning |
| Retirement age ≥ 59.5              | No 59.5 restriction applies                                 |
| All-Roth portfolio                  | No conversions recommended                                  |
| Exhaustion buffer > remaining years | Immediate exhaustion needed; likely infeasible, warning      |
| SS or other income fills bracket    | Conversion amounts decrease dynamically in those years       |

## Known Approximations

**Spending estimate in Phase 1:** The forward simulation uses `essentialFloor`
as a proxy for spending withdrawals. Actual spending (determined in Phase 2)
may be higher, which would drain traditional faster. This is a conservative
approximation — slight over-conversion rather than under-conversion. The MC
validation in Phase 2 stress-tests the full picture.

**Future improvement:** After Phase 2 produces a spending schedule, re-run
Phase 1 with actual spending, then re-run Phase 2. This iteration converges
quickly but adds complexity. Deferred to a future version.

**Growth rate:** Phase 1 uses `returnMean` for deterministic growth projection.
Actual returns are stochastic. Phase 2's MC trials validate whether the plan
works across market conditions and reports what percentage of trials exhaust
traditional on time.

**Tax computation:** Phase 1 uses `FederalTaxCalculator` for bracket ceilings
and tax computation. State tax is computed on conversion amounts as a cost but
does not gate the conversion bracket ceiling (consistent with existing
`CombinedTaxCalculator.computeMaxIncomeForTargetRate()` behavior).

## Test Strategy

### Unit Tests: `RmdCalculatorTest`

- Distribution periods for key ages (72, 73, 75, 80, 85, 90, 95, 100, 115, 120)
  verified against IRS Publication 590-B Table III
- RMD start age: birth year 1959 → 73, birth year 1960 → 75, birth year 1970 → 75
- RMD computation: $1,000,000 at age 73 → $37,735.85 ($1M / 26.5)
- Edge: zero balance → 0, age below 72 → 0

### Unit Tests: `RothConversionOptimizerTest`

- **All-traditional, no other income:** Conversions recommended, lifetime tax
  lower than baseline
- **All-Roth:** No conversions recommended
- **Small traditional:** Modest conversions, exhaustion well before deadline
- **Large traditional:** Aggressive conversions, exhaustion target met or
  warning flag set
- **SS income mid-stream:** Conversion amounts decrease when SS starts and
  occupies bracket space
- **Early retiree (age 55):** Conversion tax from taxable only before 59.5;
  spending from taxable only before 59.5
- **59.5 boundary:** Withdrawal pools open up at 59.5
- **Birth year 1959 vs 1960:** Different RMD start ages produce different
  conversion schedules
- **Exhaustion buffer 3 vs 8:** Buffer=3 exhausts earlier than buffer=8
- **Filing status:** MFJ vs Single produces different conversion amounts
  (different bracket ceilings)
- **Taxable depleted before 59.5:** Conversions stop when taxable can't cover
  tax, resume at 59.5 with cascade
- **Already past RMD age:** No conversion years, RMD trajectory only

### Integration: `MonteCarloSpendingOptimizerTest`

- **With conversions vs without:** Same portfolio, verify conversion-enabled
  run produces sustainable spending with lower simulated tax
- **RMD enforcement:** RMD-era years force minimum traditional withdrawals in
  `isSustainable()`
- **59.5 rule:** Pre-59.5 withdrawals come only from taxable
- **Conversion schedule in MC trials:** Pool balances shift as expected across
  trials (traditional declines, Roth grows)
- **Exhaustion validation:** Report what % of MC trials exhaust traditional
  by the target age

### Controller/API Tests

- `optimize_conversions=true` request round-trips correctly
- Response includes `conversion_schedule` when enabled, null when disabled
- Validation: RMD target bracket ≤ conversion bracket (400 response)
- Validation: exhaustion buffer in range 1–20

### Frontend Tests (Vitest + React Testing Library)

- Roth Conversion Strategy section toggles visibility on checkbox
- Form validation: RMD target bracket dropdown disallows values > conversion
  bracket
- Conversion schedule table renders correct columns and data
- Tax savings summary displays when `conversion_schedule` present, hidden
  when null
- Traditional balance chart renders data points
- Warning banner appears when `exhaustionTargetMet = false`

## Implementation Sequence

1. `RmdCalculator` with Uniform Lifetime Table + tests
2. `RothConversionOptimizer` with ternary search + tests
3. Flyway migration V043
4. Data model changes (input record, response records, entity)
5. `isSustainable()` changes (conversion-aware pool evolution, 59.5 rule,
   RMD enforcement) + tests
6. `GuardrailProfileService` integration (Phase 1 → Phase 2 wiring)
7. API request/response changes + controller tests
8. Frontend: optimizer form changes (collapsible section, inputs)
9. Frontend: results display (tax summary, conversion table, balance chart)
10. End-to-end manual testing via Docker Compose
