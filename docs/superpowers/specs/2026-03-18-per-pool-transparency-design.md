# Per-Pool Transparency for Projection Data Table

**Date:** 2026-03-18
**Status:** Approved
**Scope:** Backend (projection, core) + Frontend (projection detail page)

## Problem

The projection engine silently deducts tax payments from pool balances via `deductFromPools()` without exposing which pool paid how much. Growth is returned as a single aggregate number. Withdrawal per-pool breakdown is computed internally but discarded. This makes it impossible to audit year-over-year balance changes for each pool (taxable, traditional, roth) because the math doesn't add up without knowing where taxes came from, how much each pool grew, and which pool funded each withdrawal.

## Solution

Return per-pool breakdowns from operations that already compute them internally, surface 9 new fields on `ProjectionYearDto`, and display them in a collapsible "Pool Details" section in the frontend data table.

## Design

### 1. New Return Types on PoolStrategy

Three new/modified records on the `PoolStrategy` interface:

```java
record GrowthResult(BigDecimal total, BigDecimal taxable, BigDecimal traditional, BigDecimal roth) {}

record TaxSourceResult(BigDecimal fromTaxable, BigDecimal fromTraditional, BigDecimal fromRoth) {
    static final TaxSourceResult ZERO = new TaxSourceResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
}
```

Modified existing records:

```java
// Was: WithdrawalTaxResult(totalWithdrawn, taxLiability)
record WithdrawalTaxResult(
    BigDecimal totalWithdrawn, BigDecimal taxLiability,
    BigDecimal fromTaxable, BigDecimal fromTraditional, BigDecimal fromRoth,
    TaxSourceResult taxSource) {}

// Was: ConversionResult(amountConverted, taxLiability)
record ConversionResult(BigDecimal amountConverted, BigDecimal taxLiability, TaxSourceResult taxSource) {}
```

Method signature changes:
- `applyGrowth()`: returns `GrowthResult` instead of `BigDecimal`
- `deductFromPools()`: returns `TaxSourceResult` instead of `void`
- `WithdrawalTaxResult` and `ConversionResult`: gain per-pool fields as described above

SinglePool implementations return zeroed-out pool breakdowns (no behavioral change).

### 2. ProjectionYearDto Changes

Nine new nullable `BigDecimal` fields:

| Field | Source |
|-------|--------|
| `taxableGrowth` | `GrowthResult.taxable()` |
| `traditionalGrowth` | `GrowthResult.traditional()` |
| `rothGrowth` | `GrowthResult.roth()` |
| `taxPaidFromTaxable` | Sum of conversion + withdrawal `TaxSourceResult.fromTaxable()` |
| `taxPaidFromTraditional` | Sum of conversion + withdrawal `TaxSourceResult.fromTraditional()` |
| `taxPaidFromRoth` | Sum of conversion + withdrawal `TaxSourceResult.fromRoth()` |
| `withdrawalFromTaxable` | `WithdrawalTaxResult.fromTaxable()` |
| `withdrawalFromTraditional` | `WithdrawalTaxResult.fromTraditional()` |
| `withdrawalFromRoth` | `WithdrawalTaxResult.fromRoth()` |

All null for SinglePool scenarios (same pattern as existing `traditionalBalance`, `rothBalance`, etc.).

The `simple()` factory method gains 9 additional null arguments. The `buildYearDto()` method and copy-constructor helper methods (`applyIncomeSourceFields`, `applyPropertyEquity`, `applySurplusReinvested`) pass through the new fields.

Golden test JSON files gain the new fields as null.

### 3. Engine Plumbing (DeterministicProjectionEngine)

The main year-loop captures the newly returned data:

1. `applyGrowth()` — capture `GrowthResult`, use `.total()` where `totalGrowth` was used before
2. `executeRothConversion()` — capture `TaxSourceResult` from the conversion result
3. `executeWithdrawals()` — capture per-pool withdrawal amounts and withdrawal `TaxSourceResult`
4. Aggregate tax sources: add conversion and withdrawal `TaxSourceResult` fields together into combined `taxPaidFromTaxable`, `taxPaidFromTraditional`, `taxPaidFromRoth`
5. Pass all 9 values into `buildYearDto()` (gains new parameters), then flow through existing copy-constructor chain

The `RetirementWithdrawalResult` inner record gains per-pool withdrawal and tax source fields to bubble data from `processRetirementWithdrawals` to the main loop.

No logic changes — same operations in the same order, just capturing data that was previously discarded.

### 4. Frontend Data Table

**Type:** `ProjectionYear` interface in `projection.ts` gains 9 new nullable number fields (snake_case).

**Table UI:**
- Toggle button/chevron labeled "Pool Details" in the table header (collapsed by default)
- When expanded, shows columns: Taxable Growth, Traditional Growth, Roth Growth, Tax from Taxable, Tax from Traditional, Tax from Roth, Withdrawal from Taxable, Withdrawal from Traditional, Withdrawal from Roth, Surplus/Deficit, Surplus Reinvested, Status
- Surplus/Deficit, Surplus Reinvested, and Status columns move from the main table into this collapsible section
- Only renders when multi-pool data exists (same guard as existing pool balance columns)
- Toggle state is local React state (`useState`), no persistence

**CSV export:** The 9 new fields always included when multi-pool data exists (no collapsing needed for spreadsheets).

**Test fixtures:** `makeYear()` helpers gain 9 new null defaults.

## Reconciliation Formula

With these fields exposed, each pool can be audited year-over-year:

```
pool_end = pool_start + contributions + pool_growth + conversion_in - conversion_out
         - tax_paid_from_pool - withdrawal_from_pool + surplus_deposited
```

Where `contributions` are per-pool (from account inputs), `conversion_in` applies to roth, `conversion_out` applies to traditional, and `surplus_deposited` applies to taxable.

**Tax field scope:** The `taxPaidFrom*` fields cover conversion tax and withdrawal tax only. Self-employment tax is included in the aggregate `taxLiability` field but is not deducted from pool balances (it represents a liability reported separately). Surplus reinvestment tax reduces the deposited amount itself (`surplusReinvested` is already the after-tax value) and does not appear in `taxPaidFrom*`.

## Out of Scope

- Per-pool contribution breakdown (already derivable from account inputs)
- Income source deposit tracking (income offsets withdrawal need rather than depositing into pools)
- Passthrough losses / REPS / STR loopholes (future feature, this lays the transparency foundation)
- MonteCarloSpendingOptimizer: no changes needed (uses its own simplified single-aggregate-balance model, not PoolStrategy)
