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
- Filing status (sourced from the scenario's `params_json`, piped through
  `GuardrailProfileService` — not a new request field)
- Expected return (`returnMean`) for deterministic growth projection
- `FederalTaxCalculator` for bracket ceilings and tax computation

#### Algorithm

**Search variable:** `conversionFraction` ∈ [0.0, 1.0] — the fraction of
available conversion bracket space to use each year.

**Search method:** Hybrid grid scan + ternary refinement. The lifetime tax
function is approximately unimodal, but income dynamics (SS starting,
income sources ending) can introduce local irregularities. To guard against
non-unimodality:

1. **Coarse grid scan:** Evaluate lifetime tax at 50 evenly-spaced fractions
   across [0.0, 1.0]. Filter to feasible candidates (traditional exhausts by
   deadline). Record the fraction with minimum lifetime tax.
2. **Ternary refinement:** Refine around the best grid point using ternary
   search within a ±5% window (~30 iterations for sub-dollar precision).

Each evaluation is one deterministic forward simulation of ~30 years —
the full search completes in microseconds.

**For each candidate fraction, forward-simulate the full retirement timeline:**

```
Initialize: priorYearEndTraditional = initial traditional balance

for each year from retirement to endAge:
    age = retirementAge + yearIndex

    1. Apply growth: all pools *= (1 + returnMean)

    2. If pre-RMD and traditional > 0:
       a. bracketCeiling = taxCalculator.computeMaxIncomeForTargetRate(
              conversionBracketRate, calendarYear, filingStatus)
       b. bracketSpace = max(0, bracketCeiling - otherIncome[year])
       c. maxConversion = bracketSpace * conversionFraction
       d. If age < 59.5:
            // Guard 1: taxable must cover conversion tax
            conversionTaxEstimate = computeMarginalTax(maxConversion, otherIncome)
            if taxableBalance < conversionTaxEstimate:
                reduce maxConversion until taxableBalance can cover the tax
            // Guard 2: spending feasibility — taxable must still cover
            // this year's spending after paying conversion tax. Before 59.5,
            // taxable is the ONLY source for both spending AND conversion tax.
            // This naturally delays or reduces conversions when the taxable
            // pool is tight (e.g., early retiree with small taxable balance),
            // producing emergent "start conversions later" behavior without
            // needing a separate startAge search dimension.
            spendingNeed = essentialFloor * inflationFactor(year)
            taxAfterConversion = computeMarginalTax(maxConversion, otherIncome)
            availableForSpending = taxableBalance - taxAfterConversion
            if availableForSpending < spendingNeed:
                reduce maxConversion until
                    taxableBalance - tax(reducedConversion) >= spendingNeed
                (if no positive conversion satisfies this, set maxConversion = 0)
       e. actualConversion = min(maxConversion, traditionalBalance)
       f. traditional -= actualConversion; roth += actualConversion
       g. conversionTax = computeTax(actualConversion + otherIncome)
                          - computeTax(otherIncome)
       h. Deduct conversionTax from pools:
            - Before 59.5: taxable only
            - At 59.5+: taxable → traditional → roth cascade

    3. If RMD year (age ≥ rmdStartAge) and traditional > 0:
       a. rmd = priorYearEndTraditional / distributionPeriod(age)
       b. Force withdraw max(rmd, spendingNeedFromTraditional)
       c. If rmd > spending need: deposit excess to taxable (forced income)
       d. Compute tax on traditional withdrawal amount

    4. Execute spending withdrawals:
       a. Before 59.5: taxable pool only
       b. At 59.5+: split across pools per withdrawal order
       c. Spending estimate: use essentialFloor as proxy (see Known Approximations)

    5. Compute withdrawal tax on traditional withdrawals (if not already
       computed in step 3)

    6. Accumulate lifetimeTax += conversionTax + withdrawalTax

    7. Set priorYearEndTraditional = current traditionalBalance
       (after all operations — used for next year's RMD computation)

    8. Track traditionalBalance trajectory and exhaustion year
```

**Scoring:** `lifetimeTax` — lower is better. A candidate is **feasible** if
traditional balance reaches $0 by the exhaustion age. Among feasible candidates,
pick the one with minimum lifetime tax. If no candidate is feasible (traditional
balance too large to exhaust even with maximum conversions), return the best
result with `exhaustionTargetMet = false` and a warning.

**Baseline:** Also evaluate fraction=0.0 to compute "lifetime tax without
conversions" for the comparison display.

**Tax computation:** Uses `FederalTaxCalculator` for bracket ceilings. Lifetime
tax totals include both federal and state tax (computed via `computeTax()` which
accounts for both). Bracket ceilings are inflation-adjusted per year — the
existing `FederalTaxCalculator` supports year-based bracket lookups, falling
back to the latest available year when future years are not seeded.

#### Output: `RothConversionSchedule`

Internal record (not API-facing) consumed by `MonteCarloSpendingOptimizer`.
Uses `double` arrays for performance consistency with the MC hot loop:

```java
record RothConversionSchedule(
    double[] conversionByYear,       // conversion amount per retirement year
    double[] conversionTaxByYear,    // tax cost per year
    double[] traditionalBalance,     // balance trajectory
    double[] rothBalance,            // balance trajectory
    double[] taxableBalance,         // balance trajectory
    double[] projectedRmd,           // RMD per year (0 for pre-RMD years)
    double lifetimeTaxWith,          // total tax with conversions
    double lifetimeTaxWithout,       // total tax without conversions
    int exhaustionAge,               // age traditional reaches $0
    boolean exhaustionTargetMet,
    double conversionFraction        // the optimal fraction found
)
```

Conversion from `double[]` to `BigDecimal`-based API DTOs happens in the
response builder within `GuardrailProfileService` (same pattern as existing
`toBD()` conversion in the MC optimizer).

### New Component: `RmdCalculator`

Package-private utility class in `wealthview-projection`.

**Responsibilities:**

1. **RMD start age:** `rmdStartAge(int birthYear)` → 73 or 75
2. **RMD computation:** `computeRmd(double priorYearEndBalance, int age)` →
   required distribution amount. Returns 0 if balance ≤ 0 or age < 72.
   The caller (`RothConversionOptimizer`, `MonteCarloSpendingOptimizer`) is
   responsible for checking whether the individual has reached their personal
   RMD start age (73 or 75) before calling this method.
3. **Uniform Lifetime Table:** Full static `double[]` lookup for ages 72–120
   (IRS Publication 590-B, Table III)

**Design choice:** Stateless utility, no Spring injection. Used only by
`RothConversionOptimizer` and `MonteCarloSpendingOptimizer`.

**RMD timing rule:** The RMD for a given year is based on the account balance
as of December 31 of the **prior** year. The forward simulation tracks this
via a `priorYearEndTraditional` variable, set at the end of each loop
iteration to the traditional balance after all that year's operations
(growth, conversions, withdrawals). Initialized to the starting traditional
balance for the first year.

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
Also accept `int retirementAge` and `int birthYear` to compute age per year
for the 59.5 rule and RMD enforcement.

For each year in each MC trial:

```
1. Apply growth (existing)

2. If conversionByYear != null and conversionByYear[y] > 0:
   a. actualConversion = min(conversionByYear[y], pTraditional)
   b. pTraditional -= actualConversion
   c. pRoth += actualConversion
   d. actualTax = conversionTaxByYear[y]
          * (actualConversion / conversionByYear[y])   // scale if capped
   e. Deduct actualTax from pools:
      - If age < 59.5: taxable only
      - If age ≥ 59.5: taxable → traditional → roth cascade

3. If age ≥ rmdStartAge and pTraditional > 0:
   a. Track priorYearEndTraditional per trial
   b. rmd = RmdCalculator.computeRmd(priorYearEndTraditional, age)
   c. Force traditional withdrawal of at least rmd
   d. Deposit excess (rmd - spending need from traditional) to taxable

4. Execute spending withdrawal (existing, but modified):
   a. If age < 59.5: draw from taxable only
   b. If age ≥ 59.5: splitWithdrawal() per withdrawal order (existing)

5. Compute withdrawal tax (existing)

6. Surplus handling (existing)

7. Set priorYearEndTraditional = pTraditional (for next year's RMD)
```

#### Changes to `splitWithdrawal()`

Add a `boolean preAge595` parameter. When `preAge595 = true`, return all
withdrawal from taxable regardless of configured order. Traditional and
Roth pools are excluded. This preserves the existing method signature for
callers that don't need age awareness (they pass `false`).

#### What doesn't change

- MC trial generation (`runMonteCarloTrials`)
- Spending allocation logic (`allocateSpending`, `binarySearchDiscretionary`)
- Corridor computation and smoothing
- Final simulation for percentile balances (though it also needs the conversion
  schedule baked in for accurate balance trajectories)

#### Performance

Phase 1 is microseconds (~80 deterministic forward simulations of ~30 years).
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
    BigDecimal mcExhaustionPct,          // % of MC trials that exhaust on time
    List<ConversionYearDetail> years
)

record ConversionYearDetail(
    int calendarYear,
    int age,
    BigDecimal conversionAmount,
    BigDecimal estimatedTax,             // includes federal + state tax
    BigDecimal traditionalBalanceAfter,
    BigDecimal rothBalanceAfter,
    BigDecimal projectedRmd,
    BigDecimal otherIncome,
    BigDecimal totalTaxableIncome,
    String bracketUsed                   // format: "22%" (human-readable)
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
  with persisted conversion parameters. The `conversion_bracket_rate`,
  `rmd_target_bracket_rate`, and `traditional_exhaustion_buffer` are
  read from the persisted entity, matching the pattern used for other
  guardrail parameters.

### Validation

- `rmd_target_bracket_rate` must be ≤ `conversion_bracket_rate`
- `traditional_exhaustion_buffer` must be ≥ 1 and ≤ 15
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
- "X% of simulated scenarios exhaust traditional on time"
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
| Bracket              | Federal bracket landed in (e.g., "22%")|

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
| Taxable depleted before 59.5        | Conversions stop (can't pay tax); resume at 59.5 with cascade |
| Very large traditional balance      | Maximum conversions may not exhaust on time; `exhaustionTargetMet = false` with warning |
| Retirement age ≥ 59.5              | No 59.5 restriction applies                                 |
| All-Roth portfolio                  | No conversions recommended                                  |
| Exhaustion buffer > remaining years | Immediate exhaustion needed; likely infeasible, warning      |
| SS or other income fills bracket    | Conversion amounts decrease dynamically in those years       |
| Early retiree, small taxable pool   | Spending feasibility guard reduces/defers conversions before 59.5 |
| Market crash in MC trial            | `actualConversion = min(scheduled, pTraditional)` prevents negative balance |
| Filing status change (spouse death) | Not modeled in v1; noted as known limitation                 |

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
traditional on time (`mcExhaustionPct`).

**Tax computation:** Uses `FederalTaxCalculator` for bracket ceilings (which
are inflation-adjusted per year via year-based bracket lookups, falling back to
the latest available year when future years are not seeded). Lifetime tax totals
include both federal and state tax when a `CombinedTaxCalculator` is available.
The conversion bracket ceiling is based on federal brackets only — state tax is
a cost of the conversion, not a gating factor (consistent with existing
`CombinedTaxCalculator.computeMaxIncomeForTargetRate()` behavior).

**Conversion schedule fixed across MC trials:** Phase 1 produces a deterministic
conversion schedule applied identically to every MC trial. In trials where the
market crashes and the traditional balance is lower than the scheduled conversion,
the actual conversion is capped at the available traditional balance. This means
some trials will under-convert relative to the plan. The `mcExhaustionPct` metric
reports what percentage of trials successfully exhaust traditional on time.

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
- **Small taxable, early retiree:** Conversions are reduced or deferred to
  preserve taxable for spending before 59.5; conversions ramp up once spending
  pressure eases (other income starts, or 59.5 unlocks traditional/Roth)
- **Already past RMD age:** No conversion years, RMD trajectory only

### Integration: `MonteCarloSpendingOptimizerTest`

- **With conversions vs without:** Same portfolio, verify conversion-enabled
  run produces sustainable spending with lower simulated tax
- **RMD enforcement:** RMD-era years force minimum traditional withdrawals in
  `isSustainable()`
- **59.5 rule:** Pre-59.5 withdrawals come only from taxable
- **Conversion schedule in MC trials:** Pool balances shift as expected across
  trials (traditional declines, Roth grows)
- **MC exhaustion percentage:** Verify `mcExhaustionPct` is computed and
  reported correctly
- **Market crash capping:** Verify `actualConversion = min(scheduled, pTraditional)`
  prevents negative traditional balance

### Controller/API Tests

- `optimize_conversions=true` request round-trips correctly
- Response includes `conversion_schedule` when enabled, null when disabled
- Validation: RMD target bracket ≤ conversion bracket (400 response)
- Validation: exhaustion buffer in range 1–15

### Frontend Tests (Vitest + React Testing Library)

- Roth Conversion Strategy section toggles visibility on checkbox
- Form validation: RMD target bracket dropdown disallows values > conversion
  bracket
- Conversion schedule table renders correct columns and data
- Tax savings summary displays when `conversion_schedule` present, hidden
  when null
- Traditional balance chart renders data points
- Warning banner appears when `exhaustionTargetMet = false`
- MC exhaustion percentage displayed in summary

## Implementation Sequence

1. `RmdCalculator` with Uniform Lifetime Table + tests
2. `RothConversionOptimizer` with grid scan + ternary refinement + tests
3. Flyway migration V043
4. Data model changes (input record, response records, entity)
5. `isSustainable()` changes (conversion-aware pool evolution, 59.5 rule,
   RMD enforcement) + tests
6. `GuardrailProfileService` integration (Phase 1 → Phase 2 wiring)
7. API request/response changes + controller tests
8. Frontend: optimizer form changes (collapsible section, inputs)
9. Frontend: results display (tax summary, conversion table, balance chart)
10. End-to-end manual testing via Docker Compose
