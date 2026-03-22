# Dynamic Sequencing Withdrawal Order — Design Spec

## Problem Statement

The current withdrawal order options (Taxable First, Traditional First, Roth First,
Pro Rata) are simplistic. In practice, only two strategies make sense for tax-optimized
retirement planning:

1. **Taxable First** — the standard approach (Taxable → Traditional → Roth)
2. **Dynamic Sequencing** — draw from Traditional first up to a low bracket ceiling
   to burn down the balance at cheap tax rates, then Taxable, then Roth

Dynamic Sequencing complements the Roth conversion optimizer: conversions handle
aggressive Traditional balance reduction during the conversion window, while Dynamic
Sequencing provides steady-state low-bracket withdrawals during spending years. The
goal is to exhaust Traditional before late retirement, leaving only a Roth account
for tax-free withdrawals.

## Design

### Withdrawal Order Options

Replace the current 4-option grid with 2 options:

**Option 1: Taxable First** (default, unchanged)
- Order: Taxable → Traditional → Roth
- Before age 60 (proxy for 59.5, consistent with existing `EARLY_WITHDRAWAL_AGE`
  constant): Taxable only (existing 10% penalty rule)

**Option 2: Dynamic Sequencing**
- Order: Traditional up to bracket ceiling → Taxable → Roth
- Sub-input: bracket ceiling dropdown (10%, 12%, 22%) — default 12%
- Before age 60: Taxable only (DS kicks in at age 60+, same proxy as conversions)
- When Roth conversions are active in the same year: DS Traditional withdrawals
  only fill remaining bracket space below the DS ceiling. If the conversion
  already exceeds the DS bracket, the DS withdrawal from Traditional is $0.
- Help text: "Draws from Traditional IRA first up to the selected bracket ceiling
  to burn down the balance at low tax rates, then switches to Taxable. Helps
  manage RMDs and avoid IRMAA surcharges."

**Migration:** Existing scenarios with `traditional_first`, `roth_first`, or
`pro_rata` silently default to `taxable_first` (the enum's existing fallback).
This is a deliberate product decision — those options are being removed because
they don't reflect sound retirement tax planning. No user notification needed
for this self-hosted app.

### Data Model

**`params_json` fields:**
- `withdrawal_order`: `"taxable_first"` or `"dynamic_sequencing"`
- `dynamic_sequencing_bracket_rate`: `0.10`, `0.12`, or `0.22` (only when DS active)

**`WithdrawalOrder` enum:** Add `DYNAMIC_SEQUENCING` value to `fromString()`.

**`ScenarioParams` record** (in `DeterministicProjectionEngine`): Add
`BigDecimal dynamicSequencingBracketRate` field. `parseParams()` reads it
from `params_json`.

**`GuardrailOptimizationInput` record:** Add `BigDecimal dynamicSequencingBracketRate`
field so the MC optimizer can access it.

No database migration needed — `params_json` is a JSONB column that accepts
arbitrary fields.

### Backend — Withdrawal Logic

#### `PoolStrategy.MultiPool.executeWithdrawals()`

New constructor parameter: `BigDecimal dynamicSequencingBracketRate` (nullable —
null when not using DS).

When `withdrawalOrder == DYNAMIC_SEQUENCING`:

1. Compute bracket ceiling: `taxCalculator.computeMaxIncomeForTargetRate(
   dsBracketRate, year, filingStatus)` (uses `TaxCalculationStrategy` interface,
   not the concrete `FederalTaxCalculator`)
2. Compute available bracket space: `ceiling - effectiveOtherIncome -
   conversionAmount - rmdAmount` (conversions and RMDs already consumed bracket
   space this year — omitting RMDs would over-draw Traditional in RMD years)
3. `traditionalDraw = min(max(0, bracketSpace), traditional, totalNeed)`
4. Remaining need → draw from Taxable
5. Remaining need → draw from Roth

#### `MonteCarloSpendingOptimizer.splitWithdrawal()`

Add `"dynamic_sequencing"` handling. Since this method uses `double` arithmetic
in the MC hot loop, the bracket ceiling must be pre-computed per year (not
computed per trial). Add a `double dsBracketCeiling` parameter (the ceiling for
the current year), computed once alongside `precomputeMarginalRates()` using the
inflation-indexing 4-arg overload of `computeMaxIncomeForBracket()` for accurate
future-year ceilings.

Updated signature:
```java
static double[] splitWithdrawal(double taxable, double traditional, double roth,
                                double need, String order, boolean preAge595,
                                double dsBracketCeiling, double otherIncome,
                                double conversionAmount, double rmdAmount)
```

When `order == "dynamic_sequencing"` and `!preAge595`:
```
bracketSpace = max(0, dsBracketCeiling - otherIncome - conversionAmount - rmdAmount)
fromTraditional = min(bracketSpace, traditional, need)
remaining = need - fromTraditional
fromTaxable = min(remaining, taxable)
remaining -= fromTaxable
fromRoth = min(remaining, roth)
```

For non-DS orders, the new parameters are ignored (pass 0 for dsBracketCeiling).
Both call sites in `isSustainable()` and the final simulation loop must be updated.

#### `RothConversionOptimizer.processSpendingWithdrawal()`

The `withdrawalOrder` field is a `String` (not the enum — the optimizer uses
comma-delimited strings like `"taxable,traditional,roth"`). For Dynamic Sequencing:

- Check for `"dynamic_sequencing"` string value BEFORE calling `parseWithdrawalOrder()`
- If DS: compute bracket ceiling via `taxCalculator.computeMaxIncomeForBracket()`
  with inflation indexing, apply the bracket-capped Traditional-first logic
- If not DS: existing `parseWithdrawalOrder()` comma-split behavior unchanged

New constructor/builder parameter: `double dynamicSequencingBracketRate`.

### IRMAA Warning

**Rule:** Any year where age >= 63 and total income exceeds the 22% bracket
ceiling triggers an IRMAA warning. The 22% bracket ceiling is used as a **proxy**
for actual IRMAA thresholds (which are MAGI-based and differ slightly). The 22%
bracket is a reasonable conservative approximation because IRMAA surcharges begin
near that income level for MFJ filers, and the proxy avoids requiring separate
IRMAA threshold data.

Medicare's 2-year lookback means income at age 63 affects Part B premiums at 65.

**Backend:** Add `boolean irmaaWarning` field to `ProjectionYearDto`. Since this
record has 44+ fields with multiple `with*()` factory methods, add a
`withIrmaaWarning(boolean)` method following the existing pattern (e.g.,
`withTaxBreakdown()`). Computed in the deterministic engine after all withdrawals
and conversions for the year:

```
totalIncome = effectiveOtherIncome + conversionAmount + traditionalWithdrawal + rmdAmount
irmaaWarning = age >= 63 && totalIncome > bracketCeiling(0.22, year, filingStatus)
```

Display flag only — does not alter projection logic.

**Frontend:** In the Data Table on `ProjectionDetailPage`, years with
`irmaa_warning = true` get a red indicator with tooltip: "Income exceeds 22%
bracket — review IRMAA implications for Medicare (2-year lookback)."

### Frontend Changes

**`WithdrawalStrategySection.tsx`:** Replace 4-option grid with 2 radio cards.
Dynamic Sequencing card includes an inline bracket dropdown (10%, 12%, 22%)
visible only when selected. Updated props interface:
```typescript
interface WithdrawalStrategySectionProps {
    withdrawalOrder: string;
    onWithdrawalOrderChange: (order: string) => void;
    dynamicSequencingBracketRate: number;
    onDynamicSequencingBracketRateChange: (rate: number) => void;
}
```

**`ScenarioForm.tsx`:** Add `dynamicSequencingBracketRate` state (default 0.12).
Include in `params_json` when DS is active. Load from `params_json` when editing.

**`projection.ts`:** Add `irmaa_warning?: boolean` to `ProjectionYear` interface.

**`ProjectionDetailPage.tsx`:** Red IRMAA indicator on Data Table rows where
`irmaa_warning` is true.

### Test Strategy

**PoolStrategy / DeterministicProjectionEngine tests:**
- DS draws Traditional up to bracket ceiling, then Taxable, then Roth
- DS with Traditional < bracket space → draws all Traditional, remainder from Taxable
- Conversion exceeds DS bracket → Traditional withdrawal is $0 for that year
- RMD consumes bracket space → DS Traditional withdrawal reduced accordingly
- Traditional empty → falls through to Taxable → Roth
- Before age 60 → Taxable only regardless of DS setting
- DS at age 75 with active RMD — bracket space correctly reduced by RMD amount

**MC Optimizer splitWithdrawal tests:**
- `dynamic_sequencing` with bracket space → Traditional first up to ceiling
- `dynamic_sequencing` with zero bracket space → Taxable first
- `dynamic_sequencing` with `preAge595=true` → Taxable only
- `dynamic_sequencing` with RMD reducing bracket space

**RothConversionOptimizer tests:**
- DS withdrawal order with specific fixture: $1M traditional, $200K taxable,
  12% DS bracket, $40K essential floor → verify Traditional drawn at 12% rate,
  lifetime tax lower than Taxable First with same inputs

**IRMAA warning tests:**
- Age 63+ above 22% ceiling → warning true
- Age 63+ below 22% ceiling → warning false
- Age 62 above ceiling → warning false (too young for lookback)

**Frontend tests:**
- 2 options rendered (not 4)
- Bracket dropdown appears only when DS selected
- IRMAA indicator renders when flag is true

### Implementation Sequence

1. Add `DYNAMIC_SEQUENCING` to `WithdrawalOrder` enum + tests
2. Add `dynamicSequencingBracketRate` to `ScenarioParams`, `parseParams()`,
   `GuardrailOptimizationInput`, and `GuardrailOptimizationRequest`
3. Implement DS logic in `PoolStrategy.MultiPool.executeWithdrawals()` + tests
4. Thread DS bracket rate through `DeterministicProjectionEngine.buildPoolStrategy()`
5. Implement DS in `MonteCarloSpendingOptimizer.splitWithdrawal()` with
   pre-computed inflation-indexed ceilings + tests
6. Implement DS in `RothConversionOptimizer.processSpendingWithdrawal()` + tests
7. Add IRMAA warning `withIrmaaWarning()` to `ProjectionYearDto` + engine logic + tests
8. Frontend: replace WithdrawalStrategySection with 2 options + bracket dropdown
9. Frontend: add IRMAA warning display to Data Table
10. End-to-end manual testing via Docker Compose
