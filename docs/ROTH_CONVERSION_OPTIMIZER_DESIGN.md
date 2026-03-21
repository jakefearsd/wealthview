# Roth Conversion Optimizer — Design Document

## Problem Statement

A retiree with a large traditional IRA faces a tax timing problem:
- Traditional withdrawals are taxed as ordinary income
- RMDs starting at age 73 force withdrawals that may push into high tax brackets
- Roth conversions before age 73 can reduce the traditional balance, lowering future RMDs
- But conversions are taxable income NOW — converting too aggressively wastes money on taxes

The optimizer should find the annual Roth conversion amount that minimizes lifetime
tax while maintaining portfolio sustainability at the user's confidence level.

## Design Goal

**Target constraint:** Keep the traditional balance small enough by age 73 that RMDs
don't push the user beyond the 12% federal tax bracket (MFJ: ~$96,950 taxable income
= ~$126,950 gross with $30K standard deduction). For simplicity, flag when RMDs would
exceed ~$100K/year and can't be avoided.

**Optimization variable:** Annual Roth conversion amount (may vary by phase/age range).

**Objective:** Maximize after-tax sustainable spending across the full retirement horizon.

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

### Integration With Existing Fill-Bracket Strategy

The deterministic projection engine already has a `fill_bracket` Roth conversion
strategy. The new optimizer can be seen as the MC-aware version of the same concept:
- `fill_bracket` in the deterministic engine: converts up to a target bracket each year
- `RothConversionOptimizer`: finds the OPTIMAL target bracket (or conversion amount)
  that maximizes after-tax sustainable spending across MC scenarios

The optimizer's output can feed back into the deterministic engine as a
`GuardrailSpendingInput` that includes both the spending schedule AND the
conversion schedule.

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

### Frontend UX

The optimizer results page would gain:
- A "Roth Conversion Strategy" section showing the recommended conversion schedule
- A chart showing traditional balance declining over pre-RMD years
- Projected RMD amounts at age 73+ with the 12% bracket ceiling overlaid
- A warning banner when RMDs can't be kept below the target bracket
- A comparison: "With conversions: sustainable spending = $X. Without: $Y."

### Performance Considerations

The outer conversion search adds another dimension to the optimization. With
~20 binary search iterations for the conversion amount, each running the full
MC optimizer (which is ~3 seconds for 10,000 trials), the total time would be
~60 seconds. This is acceptable for a one-time optimization but should be
communicated to the user with a progress indicator.

Optimization: the outer search can use fewer trials (1,000) for the search
phase and then run the final result with the full trial count.

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

### Implementation Sequence

1. Add `RmdCalculator` with Uniform Lifetime Table lookup
2. Add conversion-aware pool evolution to `isSustainable`
3. Add outer binary search for conversion amount
4. Add RMD constraint check
5. Wire into `GuardrailProfileService` and API
6. Add frontend conversion schedule display
7. Integration tests
