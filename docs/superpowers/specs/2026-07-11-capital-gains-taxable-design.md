# Capital Gains on Taxable Accounts — Design

**Date:** 2026-07-11
**Status:** Approved (design), pending implementation plan
**Scope:** Tier-2 realism item #7 from the 2026-07-09 projection audit — track cost basis on the
taxable pool and tax realized long-term capital gains + qualified dividends. Second of the two
Tier-2 tax-realism sub-projects the user selected (RMDs done; this is capital gains). The largest
single feature of the realism effort.

## Problem statement

Taxable accounts are modeled as **tax-free** today: no cost basis, taxable-account withdrawals
generate zero taxable income (`MultiPool.executeWithdrawals` taxes only the traditional portion),
and taxable growth has no annual dividend/interest drag. This overstates after-tax outcomes and
makes withdrawal-sequencing analysis meaningless (drawing taxable-first appears to have no tax cost).
`HoldingEntity` already carries a real `costBasis`, so linked accounts have basis data available.

## Decisions (from brainstorming — full-fidelity model)

| Fork | Decision |
|---|---|
| Basis granularity | **Per-lot FIFO** in BOTH engines (accept the MC cost; a lot-consolidation cap is the safety valve). |
| LTCG rate | **0 / 15 / 20 brackets stacked on ordinary income, plus 3.8% NIIT.** Long-term only (retirement holding). |
| Dividend drag | **Yes** — an annual qualified-dividend yield taxed each year at LTCG rates and reinvested. |
| Real-terms | LTCG brackets held **constant real** (IRS-indexed, like ordinary brackets); NIIT thresholds are un-indexed → **deflated** by `(1+inflation)^yearsFromBase` (like the SS provisional thresholds). |
| Initial basis | Linked accounts: `Σ HoldingEntity.costBasis`. Hypothetical: default `basis = value` (no embedded gain) + optional `cost_basis` input. |

## Non-goals
- Short-term capital gains (retirement = long holding).
- Specific-lot identification / tax-loss-harvesting strategy (FIFO only).
- Step-up in basis at death, wash sales, Qualified Opportunity Funds.
- Per-symbol lots for linked accounts (holdings aggregate into one initial lot per taxable account).

---

## 1. Per-lot basis model (FIFO)

The taxable pool becomes an ordered list of lots, each `(basis, value)`, oldest first. Two
representations matching the existing engine money-type split: `BigDecimal` lots in the deterministic
`MultiPool`; `double` lots in the MC `TrialSimulator`.

Operations:
- **`addLot(amount)`** — append `(basis = amount, value = amount)`. Contributions and every
  reinvestment (RMD excess, income surplus, reinvested dividends) — new money has no embedded gain.
- **`grow(appreciationRate)`** — each lot's `value *= (1 + appreciationRate)`; basis unchanged.
- **`sellFifo(amount)`** — draw `amount` of value from the oldest lots first; realized gain from each
  consumed lot = `soldValue × (value − basis)/value`; remove the value + proportional basis (drop a
  fully-consumed lot); return total realized gain. Caps at total value.
- **`totalValue()` / `totalBasis()`** — sums.
- **Consolidation cap:** once the lot count exceeds a cap (~200), merge the oldest lots into one
  aggregate lot. Preserves FIFO order and is a no-op at realistic counts (~2 lots/year → ~100 over a
  50-year horizon); it's the MC performance safety valve.

All gains are long-term (no lot aging).

## 2. Initial basis
- **Linked accounts** (`ProjectionInputBuilder.toAccountInput`): initial lot =
  `(basis = Σ HoldingEntity.costBasis for the account, value = live USD value)` — the real embedded gain.
- **Hypothetical accounts:** default `basis = initial value` (no embedded gain); optional `cost_basis`
  field on `ProjectionAccountEntity`/the request DTO for a modeled built-in gain.

## 3. LTCG taxation (0/15/20 + NIIT, real-terms)
New `CapitalGainsTaxCalculator` (core, `com.wealthview.core.projection.tax`) computes tax on LTCG
income (realized gains + qualified dividends) stacked on ordinary income:
- New table **`ltcg_brackets`** (Flyway `V071`; columns like `tax_brackets`: `year, filing_status,
  rate, bracket_floor, bracket_ceiling`), seeded (2025 0%/15%/20% thresholds for single + MFJ).
  `LtcgBracketRepository` (persistence). Ordinary taxable income fills the brackets first; the LTCG
  amount is taxed at 0% up to the 0%-ceiling, 15% to the 15%-ceiling, 20% above. IRS-indexed →
  used at base-year values (**constant real**), consistent with the Phase-1 real-terms tax model.
- **NIIT:** `0.038 × min(netInvestmentIncome, MAGI − threshold)`, thresholds $200k single / $250k MFJ,
  **deflated** by `(1+inflation)^yearsFromBase` (statutorily un-indexed). `netInvestmentIncome` =
  realized gains + qualified dividends.
- The year's total tax = `ordinaryTax(ordinary income) + ltcgTax(LTCG income, stacked) + NIIT`,
  deducted from the pools via the existing cascade.

## 4. Annual dividend drag
A per-scenario `dividend_yield` param (default ~0.018 real). Each year the taxable pool's total real
return `r` splits: existing lots grow at `(r − yield)` (unrealized appreciation), and
`dividend = totalValue × yield` is distributed — taxed at LTCG/qualified rates and reinvested as a new
lot (basis = dividend). Total value still grows at `r`; the dividend tax is the annual drag. The
dividend joins the year's LTCG income for the combined tax computation.

## 5. Engine integration
- **Deterministic `MultiPool`:** `taxable` field → `TaxableLots`. `getTotal`/value = `totalValue()`.
  `applyContributions`/`depositToTaxable` → `addLot`. `applyGrowth` splits appreciation + dividend
  (accumulates the year's qualified-dividend income). `executeWithdrawals` draws taxable via
  `sellFifo` → realized gain; the year's LTCG income (dividend + realized gain) is taxed via
  `CapitalGainsTaxCalculator` stacked on ordinary income; LTCG tax joins the year's tax cascade.
- **MC `TrialSimulator`:** `pools[0]` → `double` lot list, same operations. LTCG taxed via a
  **precomputed per-year LTCG rate** (0/15/20, NIIT-adjusted) computed once alongside the existing
  ordinary `MarginalRateCalculator` rates, applied to realized gains + dividends in the hot loop.
- Interactions: RMD excess / income surplus / reinvested dividends all `addLot` (basis at cost);
  their combined LTCG income flows into the year's capital-gains tax.

## 6. Testing
- **Unit:** `TaxableLots` (add / grow / `sellFifo` realized-gain arithmetic / consolidation cap);
  `CapitalGainsTaxCalculator` (low-income retiree pays **0%**, mid pays 15%, stacking above ordinary
  income, NIIT deflation over the horizon); dividend drag (annual tax + reinvested lot, total return
  preserved); both engines — a taxable-heavy retiree now incurs LTCG tax on withdrawals (was zero),
  embedded-gain-from-holdings vs. no-gain hypothetical.
- **Golden regeneration (large):** taxable withdrawals now incur LTCG tax and taxable accounts now
  carry a dividend drag, so most existing golden/characterization fixtures with a taxable balance
  move. Regenerate deliberately, per-value direction-verified (more tax, lower after-tax balances).
  A fixture with no taxable balance, or one whose taxable withdrawals realize no gain (basis = value,
  no appreciation yet), must not change — if it does, investigate.
- **Full verify:** units + all 5 gates + Testcontainers ITs (chunked).

## Module / convention notes
- Persistence: `ltcg_brackets` table + `LtcgBracketRepository` (leaf module). Core:
  `CapitalGainsTaxCalculator`, `TaxableLots` value type (or one per money type). Projection: both
  engines' taxable-pool changes + the MC per-year LTCG-rate precompute. `BigDecimal` deterministic,
  `double` MC. Records/sealed types, no wildcard imports, coverage floors upheld. Commit on `main`;
  do not push. This is the largest sub-project — the plan decomposes into ~6-7 tasks each with its
  own golden regen where applicable.
