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
- Before 59.5: Taxable only (existing 10% penalty rule)

**Option 2: Dynamic Sequencing**
- Order: Traditional up to bracket ceiling → Taxable → Roth
- Sub-input: bracket ceiling dropdown (10%, 12%, 22%) — default 12%
- Before 59.5: Taxable only (DS kicks in at 59.5+)
- When Roth conversions are active in the same year: DS Traditional withdrawals
  only fill remaining bracket space below the DS ceiling. If the conversion
  already exceeds the DS bracket, the DS withdrawal from Traditional is $0.
- Help text: "Draws from Traditional IRA first up to the selected bracket ceiling
  to burn down the balance at low tax rates, then switches to Taxable. Helps
  manage RMDs and avoid IRMAA surcharges."

**Migration:** Existing scenarios with `traditional_first`, `roth_first`, or
`pro_rata` silently default to `taxable_first` (the enum's existing fallback).

### Data Model

**`params_json` fields:**
- `withdrawal_order`: `"taxable_first"` or `"dynamic_sequencing"`
- `dynamic_sequencing_bracket_rate`: `0.10`, `0.12`, or `0.22` (only when DS active)

**`WithdrawalOrder` enum:** Add `DYNAMIC_SEQUENCING` value.

No database migration needed — `params_json` is a JSONB column that accepts
arbitrary fields.

### Backend — Withdrawal Logic

#### `PoolStrategy.MultiPool.executeWithdrawals()`

When `withdrawalOrder == DYNAMIC_SEQUENCING`:

1. Compute bracket ceiling: `taxCalculator.computeMaxIncomeForBracket(
   dsBracketRate, year, filingStatus)`
2. Compute available bracket space: `ceiling - effectiveOtherIncome - conversionAmount`
   (conversions already consumed some bracket space this year)
3. `traditionalDraw = min(bracketSpace, traditional, totalNeed)`
4. Remaining need → draw from Taxable
5. Remaining need → draw from Roth

New constructor parameter: `BigDecimal dynamicSequencingBracketRate` (nullable —
null when not using DS).

#### `MonteCarloSpendingOptimizer.splitWithdrawal()`

Add `"dynamic_sequencing"` handling. Since this method uses `double` arithmetic
in the MC hot loop, the bracket ceiling must be pre-computed per year (not
computed per trial). Add a `double[] dsBracketCeilingByYear` parameter, computed
once alongside `precomputeMarginalRates()`.

When `order == "dynamic_sequencing"`:
```
bracketSpace = max(0, dsCeiling[y] - otherIncome - conversionAmount)
fromTraditional = min(bracketSpace, traditional, need)
remaining = need - fromTraditional
fromTaxable = min(remaining, taxable)
remaining -= fromTaxable
fromRoth = min(remaining, roth)
```

#### `RothConversionOptimizer.processSpendingWithdrawal()`

Same Dynamic Sequencing logic as above. Uses `computeMaxIncomeForBracket()` with
inflation indexing (already available via the tax calculator).

### IRMAA Warning

**Rule:** Any year where age >= 63 and total income (other income + conversion +
Traditional withdrawal + RMD) exceeds the 22% bracket ceiling triggers an IRMAA
warning. This is Medicare's 2-year lookback — income at age 63 affects Part B
premiums at age 65.

**Backend:** Add `boolean irmaaWarning` field to `ProjectionYearDto`. Computed in
the deterministic engine after all withdrawals and conversions for the year:

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
visible only when selected.

**`ScenarioForm.tsx`:** Add `dynamicSequencingBracketRate` state (default 0.12).
Include in `params_json` when DS is active. Load from `params_json` when editing.

**`projection.ts`:** Add `irmaa_warning?: boolean` to `ProjectionYear` interface.

**`ProjectionDetailPage.tsx`:** Red IRMAA indicator on Data Table rows where
`irmaa_warning` is true.

### Test Strategy

**PoolStrategy / DeterministicProjectionEngine tests:**
- DS draws Traditional up to bracket ceiling, then Taxable, then Roth
- Conversion exceeds DS bracket → Traditional withdrawal is $0 for that year
- Traditional empty → falls through to Taxable → Roth
- Before 59.5 → Taxable only regardless of DS setting

**MC Optimizer splitWithdrawal tests:**
- `dynamic_sequencing` with bracket space → Traditional first
- `dynamic_sequencing` with zero bracket space → Taxable first
- `dynamic_sequencing` with `preAge595=true` → Taxable only

**RothConversionOptimizer tests:**
- DS withdrawal order produces lower lifetime tax than Taxable First

**IRMAA warning tests:**
- Age 63+ above 22% ceiling → warning true
- Age 63+ below 22% ceiling → warning false
- Age 62 above ceiling → warning false

**Frontend tests:**
- 2 options rendered (not 4)
- Bracket dropdown appears only when DS selected
- IRMAA indicator renders when flag is true

### Implementation Sequence

1. Add `DYNAMIC_SEQUENCING` to `WithdrawalOrder` enum + tests
2. Implement DS logic in `PoolStrategy.MultiPool.executeWithdrawals()` + tests
3. Implement DS in `MonteCarloSpendingOptimizer.splitWithdrawal()` + tests
4. Implement DS in `RothConversionOptimizer.processSpendingWithdrawal()` + tests
5. Add IRMAA warning to `ProjectionYearDto` and deterministic engine + tests
6. Parse `dynamic_sequencing_bracket_rate` from `params_json` and thread through
7. Frontend: replace WithdrawalStrategySection with 2 options + bracket dropdown
8. Frontend: add IRMAA warning display to Data Table
9. End-to-end manual testing via Docker Compose
