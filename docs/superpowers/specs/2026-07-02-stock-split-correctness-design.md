# Stock Split Correctness — Late-Arriving Transactions & Reverse-Split Precision

**Date:** 2026-07-02
**Status:** Approved design, pending implementation
**Scope:** `wealthview-core` (`split`, `transaction`, `importservice` packages). No schema changes, no frontend changes.

## Background

Stock splits are already fully implemented end-to-end (`StockSplitService`,
`StockSplitSyncService`, `StockSplitBackfillRunner`, admin API, frontend, docs at
`docs/operations/stock-splits.md`). Splits are global market events: applying a split
mutates historical transactions (quantity × ratio) and historical prices
(close × inverse ratio) in place, recording per-row before/after audit rows in
`stock_split_adjustments` so the operation is reversible.

Two correctness gaps in that existing implementation cause real trouble in day-to-day
use. This design fixes both. They are addressed together because both depend on a single
shared split-math helper.

## Problem 1 — Late-arriving pre-split transactions (correctness bug)

`StockSplitService.applySplit()` adjusts only the transactions that exist **at apply
time** (`findBySymbolAndDateOnOrBefore`). Every transaction-creation path —
`TransactionService.create`, `createWithHash`, `createWithHashNoRecompute` (the import
path) — inserts the **raw** quantity with no split awareness.

Consequence: once a split for a symbol has already been applied (via nightly sync,
backfill, or manual entry), any transaction *later* imported or entered that is dated
on/before that split's effective date lands with a **pre-split** quantity and is never
adjusted. Holdings and portfolio history silently understate those lots. This happens
routinely because CSV/OFX history is imported incrementally over time.

## Problem 2 — Reverse-split precision (rounding bug)

`StockSplitService` pre-divides the ratio into a scale-8 `BigDecimal`
(`ratio = numerator/denominator`, `inverseRatio = denominator/numerator`) and then
multiplies. For forward splits (denominator 1) this is exact. For reverse or odd ratios
that produce repeating decimals (1:3, 1:6, 1:7), the scale-8 rounding is baked in before
the multiply, introducing avoidable drift.

Note on fractional shares (explicitly decided): a reverse split of a non-divisible share
count yields fractional shares (e.g. 1:10 of 155 → 15.5). Real brokerages cash out the
fraction ("cash in lieu"). **We keep fractional shares.** This preserves total value and
cost basis exactly and needs no price lookup, which is the right behavior for a
net-worth/projection tool. Cash-in-lieu modeling is explicitly out of scope.

## Design

### 1. Shared helper: `SplitMath`

A small, pure, fully-unit-tested utility in `com.wealthview.core.split`.

```java
// Exact multiply-then-divide in a single step — no intermediate rounded ratio.
BigDecimal adjustShares(BigDecimal quantity, int numerator, int denominator);
    // quantity × numerator ÷ denominator, scale 4, HALF_UP

BigDecimal adjustPrice(BigDecimal closePrice, int numerator, int denominator);
    // closePrice × denominator ÷ numerator, scale 4, HALF_UP
```

This replaces the inline `ratio`/`inverseRatio` math in `StockSplitService`, fixing
Problem 2 in one place, and is reused by the insert path below so both code paths compute
identically (also avoids CPD duplication).

Behavior preserved: quantity/price stored at scale 4; adjustment-row `old_value`/
`new_value` continue to be stored at scale 8 (`VALUE_SCALE`).

### 2. `SplitAdjustmentApplier` (fixes Problem 1)

A focused component in `com.wealthview.core.split`, depending only on
`StockSplitRepository` and `StockSplitAdjustmentRepository` (plus `SplitMath`). Keeps
`TransactionService` decoupled from the full `StockSplitService`.

Contract:

> Given a freshly-saved transaction (symbol S, date D, tenant T, id), find all applied
> splits for S with `effectiveDate ≥ D`, ordered **oldest → newest**. Fold
> `SplitMath.adjustShares` over the transaction's quantity, updating the stored quantity,
> and write one `stock_split_adjustment` row per split (`target_table='transactions'`,
> `field='quantity'`, old→new for that step).

Properties:

- **Invariant:** every stored transaction is always split-adjusted ⇒ holdings/history are
  always correct regardless of import order.
- **Reversible:** because late inserts now write adjustment rows too, `unapplySplit`
  restores them with no change to `unapplySplit`. Oldest→newest folding makes per-split
  unapply compose correctly across multiple splits (e.g. AAPL 7:1 then 4:1: split1 row is
  raw→raw×7, split2 row is raw×7→raw×7×4; unapplying either restores the correct
  intermediate).
- **No double-adjust:** a fresh insert has no prior adjustment rows, and rows adjusted at
  apply-time are never re-touched by this path.
- **Skips no-op rows:** transactions with null or zero quantity are left untouched, matching
  `applySplit`'s existing behavior.

### 3. Wiring into transaction creation

`TransactionService` gains a `SplitAdjustmentApplier` dependency (constructor injection).
After `transactionRepository.save(txn)` and **before** holdings recompute, call the
applier. Applied to:

- `create` (manual single entry)
- `createWithHash`
- `createWithHashNoRecompute` (import path)

Ordering matters: the applier must run before the existing holdings recompute so holdings
are computed from adjusted quantities. In the import path, recompute already happens once
per symbol at the end of `ImportService.importTransactions`, which is unchanged and still
correct because quantities are adjusted at insert time.

**Dedup is unaffected:** import hashes are computed from the raw parsed quantity
(`TransactionHashUtil.computeHash` on `parsed.*`) before insert and stored in
`import_hash`. Adjusting the stored quantity after insert does not change the hash, so
re-imports of the same raw rows still dedupe.

**Performance:** the applier issues one split lookup per inserted transaction. Splits are
rare per symbol, but to avoid N queries during large imports the applier looks splits up
by symbol and the import loop may reuse a per-symbol result within a batch. Start simple
(per-transaction lookup); add per-symbol memoization only if an import benchmark shows it
matters.

## Decisions & rationale

| Decision | Choice | Why |
|---|---|---|
| Fractional shares on reverse splits | Keep them | Preserves total value + cost basis exactly; no price lookup; correct for a net-worth/projection tool |
| #1 approach | Adjust-on-insert invariant | Simplest guarantee that holdings are always correct; reuses existing audit/unapply machinery; no read-path changes |
| Precision | Multiply-then-divide, single divide, scale 4 HALF_UP | Removes intermediate rounded-ratio drift for reverse/odd ratios |
| Schema | No migration | Reuses `stock_split_adjustments` (`target_table='transactions'` already permitted) |

## Out of scope (explicit)

- **Cash-in-lieu modeling** for reverse-split fractional shares.
- **`TransactionService.update`** adjust-on-edit. Editing an existing transaction's
  quantity is semantically ambiguous (did the user type the raw or the adjusted value?),
  and existing adjustment rows for that txn would need reconciling. Deferred as a separate
  decision. Current behavior (edit stores the value as typed, no split re-evaluation) is
  unchanged.
- Any frontend change.
- Any change to detection (Finnhub sync/backfill) — this design is about applying splits
  correctly to transactions that arrive at any time.

## Testing (TDD, red first)

1. **`SplitMathTest`** (unit): forward 4:1; reverse 1:10 (155 → 15.5, fractional kept);
   precision 1:3 of 300 = exactly 100.0000; price inverse for a reverse split; scale/rounding.
2. **`StockSplitServiceTest`** (existing): refactor onto `SplitMath`; all existing tests stay
   green (regression guard for behavior preservation).
3. **`SplitAdjustmentApplierTest`** (unit): insert dated before an applied split → quantity
   adjusted + one adjustment row written; insert dated after the split → untouched; multi-split
   compose (two splits, correct cumulative + two rows); null/zero quantity skipped.
4. **`TransactionServiceTest`** (unit): each creation path invokes the applier before recompute.
5. **Unapply round-trip** (unit or IT): apply split → late-insert a pre-split transaction →
   `unapplySplit` restores the late-inserted transaction to its raw quantity.
6. **Import integration test** (`wealthview-app`, Testcontainers): apply a split, then import a
   CSV containing pre-split-dated transactions for that symbol → resulting holdings reflect the
   split (adjusted quantities), and re-importing the same file dedupes.

## Build order

1. `SplitMath` + tests; refactor `StockSplitService` onto it (Problem 2 fixed).
2. `SplitAdjustmentApplier` + tests.
3. Wire into the three `TransactionService` creation paths; add the import IT (Problem 1 fixed).

Each step is an independent green commit following the repo's TDD + conventional-commit conventions.
