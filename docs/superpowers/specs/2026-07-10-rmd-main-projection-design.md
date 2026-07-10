# RMDs in the Main Projection — Design

**Date:** 2026-07-10
**Status:** Approved (design), pending implementation plan
**Scope:** Tier-2 realism item #6 from the 2026-07-09 projection audit — enforce Required Minimum
Distributions (and their tax) in the deterministic and Monte Carlo retirement withdrawal loops.
This is the first of two Tier-2 tax-realism sub-projects the user selected (RMDs, then capital gains
on taxable accounts — a separate spec).

## Problem statement

RMDs are modeled today ONLY in the Roth-conversion optimizer (`ConversionSimulator.applyRmds`,
`RothConversionOptimizer`). The main deterministic projection and the Monte Carlo optimizer both pass
`rmdAmount = 0` (`RetirementWithdrawalProcessor` → `PoolStrategy.executeWithdrawals(..., BigDecimal.ZERO, age)`;
`TrialSimulator.simulateTrial` → `splitWithdrawal(..., 0)`). So a retiree with a large traditional
balance who spends less than their RMD is never forced to take (and pay ordinary-income tax on) the
mandatory distribution. This understates taxes for tax-deferred-heavy retirees and lets the traditional
pool grow unrealistically. `RmdCalculator` already exists (correct IRS Uniform Lifetime Table + SECURE
Act 2.0 start ages) and is reused unchanged.

## Decision (from brainstorming): PHYSICAL model

Model RMDs physically (not a tax-only approximation): force the full RMD out of the traditional pool
each year at/after the RMD start age, tax it as ordinary income, spend what's needed, and reinvest the
after-tax excess into the taxable pool. The physical model is accurate at every spending level; the
tax-only alternative was rejected because it leaves the un-withdrawn money in the traditional pool,
so the taxable base never shrinks and it *overstates* cumulative RMD tax for low-spending retirees
(compounding base). For the common high-spender case (`tradForSpending ≥ rmd`) both models agree exactly.

## Non-goals

- Capital gains / cost-basis on taxable accounts (the next Tier-2 sub-project, separate spec) — the
  after-tax RMD excess reinvested to taxable simply grows at the taxable pool's return with no basis
  tracking yet, exactly as today's taxable pool works.
- Per-account RMD (the model has one aggregate traditional pool; IRS permits IRA aggregation, so an
  aggregate RMD is correct).
- Qualified Charitable Distributions, inherited-IRA rules, still-working 401(k) exceptions.

---

## Per-year algorithm (each retired year, age ≥ RMD start age, MultiPool only)

1. `rmd = priorYearEndTraditional ÷ ULTdivisor(age)`, capped at the current traditional balance.
   `priorYearEndTraditional` = the traditional pool balance at the start of the year, BEFORE this
   year's growth (= prior Dec 31, per IRS). RMD start age = `RmdCalculator.rmdStartAge(birthYear)`
   (73 if born < 1960, else 75); divisor = `RmdCalculator.distributionPeriod(age)`.
2. The normal spending withdrawal draws `tradForSpending` from traditional per the withdrawal order
   (unchanged).
3. `extra = max(0, rmd − tradForSpending)` — the forced excess. Withdraw `extra` additionally from
   traditional.
4. **Tax:** the full forced distribution is ordinary income. `extra` is added to the year's ordinary
   taxable income so its tax flows through the normal path — deterministic: incremental via
   `FederalTaxCalculator`; MC: `extra × marginalRate[y]` — correctly affecting bracket position and
   Social Security taxability.
5. **Reinvest:** `taxable += extra − taxOnExtra`. Net: traditional shrinks by the full RMD, taxable
   grows by the after-tax remainder, and next year's RMD (on the smaller base) shrinks correctly.

Everything is real-terms; `rmd = balance ÷ divisor` needs no nominal conversion.

## Ordering (both engines)

The RMD must be computed BEFORE Roth conversions each year (IRS: no conversion until the RMD is
satisfied, and RMD income consumes conversion bracket headroom). So each year, after growth:
- compute `rmd`;
- feed it into the conversion's bracket-space calc — the existing `rmdAmount` parameter in
  `WithdrawalOrderStrategy.DynamicSequencingOrder` and `PoolStrategy.executeRothConversion*`
  (currently `0`) receives the real value;
- physically enforce the RMD in the withdrawal step.

## Integration points

- **`RmdCalculator`** (projection) — reused unchanged.
- **Deterministic** — `DeterministicProjectionEngine.processYear` computes `rmd` after growth and
  threads it into `processIncomeAndConversions` (bracket headroom) and
  `RetirementWithdrawalProcessor.process` (physical enforcement). `PoolStrategy.MultiPool` gains a
  "force RMD distribution" step: withdraw `extra` from traditional, compute its tax via the injected
  tax calculator, deposit `extra − taxOnExtra` to taxable. `birthYear` is already in
  `ProjectionRunContext`.
- **Monte Carlo** — `TrialSimulator.SimulationConfig` gains `int rmdStartAge` (derived once from
  `birthYear` upstream in `OptimizationContextBuilder`/`GuardrailProfileService`; `Integer`/sentinel
  when RMDs are not applicable). Each year, before `applyTrialConversion`, compute
  `rmd = pools[1]_preGrowth ÷ distributionPeriod(age)` when `age ≥ rmdStartAge`, force `extra` from
  `pools[1]`, tax at `marginalRate[y]`, reinvest `extra − taxOnExtra` to `pools[0]`. Feed the real
  `rmd` into `applyTrialConversion`'s bracket hook.
- **`SinglePool`** (all-taxable) — no traditional pool, no RMD; only `MultiPool` paths compute it.

## Testing

- **Unit (both engines):** a traditional-heavy, low-spending retired scenario at/after RMD age →
  assert traditional shrinks by the full RMD, taxable grows by `extra − taxOnExtra`, and the year's
  tax reflects the RMD ordinary income. Edge cases: age < start (no RMD), high spender
  (`tradForSpending ≥ rmd` → `extra = 0`, no change), traditional exhausted (`rmd = 0`), and RMD
  reducing Roth-conversion headroom (a conversions-plus-RMD scenario).
- **Golden regeneration:** RMDs change outputs for traditional-heavy retirees at/after RMD age.
  Regenerate the affected golden/characterization fixtures deliberately, verifying direction for each
  changed value (more tax in RMD years; traditional drains faster; taxable grows from reinvested
  excess). Fixtures whose retiree never reaches RMD age, or who always spend ≥ their RMD, must NOT
  change — if one does, investigate.
- **Full verify:** units + all 5 gates (PMD/CPD/SpotBugs/Checkstyle/JaCoCo) + app Testcontainers ITs,
  run chunked (`mvn clean install -DskipITs` then `mvn verify -pl wealthview-app`).

## Module / convention notes

- Changes are confined to `wealthview-projection` (both engines, `RmdCalculator` reuse) plus any
  tax-calc calls into `wealthview-core`. Records/sealed types, no wildcard imports, `BigDecimal` for
  money in the deterministic path, `double` in the MC hot loop (matching existing code). Coverage
  floors upheld (projection ≥90% line; branch floors not lowered). Commit on `main`; do not push.
