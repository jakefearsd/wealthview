# Projection Accuracy Audit v2 — Post-Realism State (2026-07-11)

Two independent deep audits (Fable) of the projection system as it stands on `main` after the
realism-v2 effort (Phases 1–3, RMDs, capital gains): one on the **deterministic engine + tax
model**, one on the **Monte Carlo + guardrail optimizer**. This document is the unified synthesis:
verdict, deduplicated gap set tiered by accuracy impact, and a refinement roadmap.

## Verdict on the completed implementation

The realism-v2 machinery is sound. Confirmed well-modeled: coherent real-terms frame (constant-real
IRS-indexed brackets, correctly deflated unindexed thresholds); correct Politis-Romano stationary
block bootstrap with cross-pool correlation preserved (shared index sequence); exact FIFO lot math
in both engines; correct LTCG stacking on deduction-netted ordinary income (itemized-aware);
IRS-correct RMD mechanics (prior-Dec-31 snapshot, SECURE-2.0 ages, verified Uniform Lifetime
Table); correct SS two-tier formula; sound search statistics (frozen-path CRN bisection, proper
order-statistic floor gate, adequate 5000-trial default, independent path set for conversion-arm
selection).

**But the net bias of what remains is OPTIMISTIC.** Nearly every material gap understates taxes or
overstates wealth: SS taxation ignores portfolio income, RMDs/dividend tax skipped in income-rich
years, free money on MC down-year tail paths, tax on outside income unfunded, no fees, equity tail
bounded at 1974's −36%. A user following the numbers would systematically overspend. The gaps
below are ordered to fix that.

---

## A. Outright bugs (fix regardless of any realism roadmap)

**A1. MC cash-reserve down-year pool accounting is broken (Tier 1; default config).**
`TrialSimulator.applyTrialWithdrawals` pools-branch (`TrialSimulator.java:355-378`): when the cash
reserve partially covers a down-year draw, `drawn.traditional()`/`drawn.roth()` are **never
subtracted** from the pools (spending is credited, money never leaves) — free money precisely on
the multi-down-year paths that define the failure tail. The tax deduction there is dimensionally
wrong (`withdrawalTax × min(pools[0],tax)/totalPools` ≈ tax²/portfolio — $15k tax on $1M deducts
$225). When cash fully covers, the draw is deducted **twice** (pools + cash). `forceRmdExcess`
treats the phantom traditional draw as satisfying the RMD (`:308`). Non-pool branch is correct and
test-pinned; pools branch has no test. Sustainable-spending overstatement plausibly 5–15% for
traditional-heavy retirees. **Fix:** mirror the non-pool three-pool scaling; deduct nothing extra
when cash covers; route residual tax through `PoolTaxCascade`; add the missing pools-branch test.
Effort M (logic S + re-baselining).

**A2. Deterministic zero-portfolio-need years skip the RMD force-out AND dividend taxation
(Tier 1).** `MultiPool.executeWithdrawals` early-returns when `totalNeed <= 0`
(`PoolStrategy.java:567-570`); RMD forcing (`:599-605`) and dividend/LTCG taxation (`:636`) sit
below it. A pension/rental-rich retiree (surplus branch → portfolioNeed 0) takes **no RMD at all**
and pays no dividend tax — traditional compounds untaxed exactly for the profiles where RMDs
matter most. **Fix:** hoist RMD-forcing + dividend taxation out of the early return. Effort S.

**A3. Legacy `expected_return = 0.07` rows silently produce a zero-dispersion Monte Carlo
(Tier 1 for legacy data).** V069 dropped NOT NULL but never backfilled; every pre-V069 projection
account still carries the old `DEFAULT 0.07` and takes the fixed-return path
(`ProjectionInputBuilder.java:175` → `PortfolioReturnResolver.fixed`). All-legacy scenarios get an
MC with zero return variance — the confidence gate is vacuous ("95% confidence" = deterministic
max spending). **Fix:** one-shot migration nulling `expected_return` where it equals the historical
default; response warning when >X% of portfolio is override-based. Effort S.

**A4. Tax on outside income is not a funded outflow (Tier 1, both engines, mirrored forms).**
- MC: tax on pension/SS/rental income is charged only in surplus years, capped at the surplus
  (`TrialSimulator.java:171-176`; `MarginalRateCalculator` measures only tax *above* the base) —
  a $60k-pension retiree spending $100k pays $0 tax on the pension. Sustainable spending
  overstated ~4–8% of spending for pension-heavy users. Fix: charge base-income tax as a
  first-class outflow every year. Effort S/M.
- Deterministic: surplus-branch tax is netted against the surplus with a floor at zero
  (`RetirementWithdrawalProcessor.java:104-112`) — the unfunded remainder vanishes (balance
  identity silently breaks). Fix: route remainder through `deductFromPools`. Effort S.

**A5. Phase-3 integration gaps (small, ours, immediate).**
- The new scenario `dividend_yield` param **never reaches the MC** — hardcoded 1.8% at
  `OptimizationContextBuilder.java:133`. The new UI knob affects only the deterministic engine.
  Effort S.
- The guardrail staleness/seed `scenarioSignature` (`GuardrailProfileService.java:269-289`) omits
  **allocation, cost basis, dividend yield, and income sources** — editing the new allocation UI
  changes MC inputs without staleness or a seed change; `accounts` is also an unordered JPA bag
  (no `@OrderBy`) so cross-run seed reproducibility is order-fragile. Effort S.

## B. Tier-1 systematic realism (certain direction, large compounding effect)

**B1. Investment fees / expense ratios (both audits; the best realism-per-effort fix).** No fee
concept exists anywhere; CMA returns are gross index returns. At 0.3–0.8% blended cost, 30-year
sustainable spending is overstated ~4–10%, uniformly. Fix: per-account (or global, default ~0.25%)
`fee_rate` subtracted from each year's real return in both engines. Effort S.

**B2. Social Security taxation ignores portfolio income (deterministic), and the MC assumes the
opposite.** Provisional income = static `other_income` only (`PoolStrategy.java:788-790` →
`IncomeSourceProcessor.java:124-125`): traditional withdrawals, RMDs, conversions, gains never
drag SS into taxation — understates federal tax ~$6–11k/yr for the common SS+traditional profile
and deletes the "tax torpedo" from conversion economics. Meanwhile the MC income base treats SS as
**100% taxable** (`IncomeProjector.java:64-68`) — the opposite bias — and MFJ couples' SS is
computed per-source rather than combined (`IncomeSourceProcessor.java:112-125`). Fix: converge SS
taxable on actual ordinary income (two-pass; the pattern exists in
`ConversionSimulator.convergeConversionAmount`), aggregate spousal benefits, and align the MC.
Effort M.

## C. Tier-2 (moderate / situational; ordered by impact-per-effort)

1. **Bond interest taxed as qualified dividends** — the uniform `dividendYield` drag applies
   LTCG rates to the whole taxable pool (`PoolStrategy.java:538-560,662`); the bond sleeve's
   ordinary interest (~4–5% nominal) is mistaxed and under-yielded. Split the drag by allocation:
   bond-weighted share ordinary annually, equity share qualified. Effort M.
2. **No gross-up when taxes are paid from the traditional pool** (`PoolStrategy.java:817-835`) —
   a traditional draw to pay tax is itself taxable (~tax/(1−rate)); matters once taxable depletes
   (standard late-retirement state). Effort M.
3. **State tax fidelity** (`StateTaxCalculator`, `CombinedTaxCalculator.java:37`,
   `StateTaxCalculatorFactory.java:43-48`): `taxesCapitalGainsAsOrdinaryIncome` declared but never
   consumed (CA taxes gains up to 13.3%); SS not exempted from the state base though CA/AZ/OR all
   exempt it; unsupported states silently $0 with no warning. Effort S (warn) + M (fidelity).
4. **ConversionSimulator frame mismatch + RMD evaporation** (`ConversionSimulator.java:194-207,
   420-422`): grows pools at default **10% nominal** against constant-real brackets → overstated
   future RMD pressure; gross RMD proceeds vanish (debited, never credited) → inflated
   lifetime-tax-without-conversions. Both bias **pro-conversion**; DS-mode schedules are used
   verbatim. Effort M.
5. **Per-year marginal-rate precompute vs tax convexity** (`MarginalRateCalculator.java:29-39`,
   `LtcgRateCalculator.java:46-57`): the $50k-chord ordinary rate misses conversion/RMD stacking
   and bracket crossings for large draws; the LTCG floor probe omits traditional draws entirely
   (0%→15% flips missed exactly when gains fund spending). Withdrawal need per year is
   deterministic, so exact per-year tax on the expected draw is cheap. Effort S/M.
6. **Unaffordable floors are silently clamped** (`SustainabilitySearch.java:97-101`) — the
   headline successProbability then measures a *reduced* floor, not the user's. Report a
   `floor_reduced` flag + true-floor success rate. Effort S.
7. **Fixed-nominal income deflation clock** (`IncomeYearMath.java:47-59`) — anchored at
   retirement start, not base year: a 0%-COLA pension keeps full real value through accumulation
   (up to ~45% cumulative overstatement at a 15-year boundary), inconsistent with the threshold
   deflators (which correctly use years-from-base). Effort S/M (goldens move).
8. **Accumulation-phase basis credit without tax** (`PoolStrategy.java:419-422,547,553`):
   dividends during accumulation are untaxed (by design) yet reinvested lots enter at cost —
   strictly better than reality (~27bp/yr on taxable equity + bond interest). Effort M.
9. **Guardrails are not simulated rules** (`TrialSimulator.java:127`;
   `SpendingCorridorCalculator`): trials run a fixed schedule; the displayed corridor is cosmetic
   and never validated in-simulation. Simulate a simple ratchet/cut rule; report both
   no-adaptation and with-rules success. Effort L.
10. **Equity tail bounded at 1974 (−36%)** — 1972–2025 window (54 rows) excludes 1929–33; no
    resampled annual real equity return can be worse than history's best-worst year. Optional
    stress overlay or synthetic tail blocks. Effort M.
11. **SinglePool path is entirely untaxed** (`PoolStrategy.java:246-254,394-396`) — all-taxable
    scenarios get zero tax of any kind (even income-source tax), filing status hardcoded SINGLE.
    Small population; silent. Route all-taxable scenarios through MultiPool or warn. Effort M.

## D. Tier-3 catalog (minor / edge / data updates)

- **2025 tax-parameter staleness (OBBBA):** standard deduction seeded $15,000/$30,000 vs post-OBBBA
  $15,750/$31,500 (persists forever as the constant-real value); SALT cap $10k vs $40k through
  2029 (`CombinedTaxCalculator.java:11`). Conservative direction; S.
- Age-65+ additional standard deduction missing (~$700/yr overstated tax MFJ 65+); S.
- No 10% pre-59½ penalty on early traditional draws (only dynamic sequencing avoids them); no
  Roth 5-year/ordering rules; S/M.
- Conversion executes before the year's RMD is satisfied (IRS requires RMD first)
  (`DeterministicProjectionEngine.java:305-308`); S.
- RMDs gated on `retired` — working-past-73 IRA holders skip them; S.
- NIIT base omits net rental income (`PoolStrategy.java:666`); rental $25k-exception phaseout uses
  static otherIncome as MAGI; S each.
- IRMAA: warning-only, proxy threshold ~$12k high, no 2-year lookback, no surcharge dollars —
  biases conversion optimization upward; M for real dollars.
- Rental mortgage interest/principal frozen at today's amortization split; no payoff year
  (`ProjectionInputBuilder.java:242-246`); M.
- DTO identity nits: `withdrawal_from_traditional` includes RMD excess while `withdrawals`
  excludes it; SE-tax years break `federal+state == tax_liability`
  (`RetirementTaxAnnotator.java:54-67`); S.
- In-sample reporting: reported successProbability comes from the same 5000 paths the searches
  optimized against (optimistic ≲1 SE ≈ 0.3–0.4pp); rerun final schedule on a fresh seed and
  report both; S. Conversion-arm search at 500 trials (SE≈1.3pp winner's curse, contained); S.
- No request validation on `trialCount`/`confidenceLevel`; fan-chart "not an actual trajectory"
  tooltip; `SplittableRandom` upgrade; cash-refill negative-pool underflow edge; post-depletion
  income untaxed; UI SS inflation-rate default mismatch (SS erodes ~0.5%/yr real unless user
  matches scenario inflation); Roth-vs-traditional contribution comparison lacks the wage-tax
  deduction (UI caveat); 2025 us_stock seed is a partial-year snapshot (re-derive + recommit the
  source CSV); intl series gross-vs-net ambiguity (~0.5pp); bond series is 10-yr Treasury only
  (document).

## Prior known-items verification

| Item | Verdict |
|---|---|
| Itemizer LTCG floor uses standard deduction | **Already FIXED** in current code (`PoolStrategy.java:681-689` prefers itemized) |
| Fees absent | Confirmed — promoted to **B1** |
| SinglePool untaxed | Confirmed, worse than recorded (income tax also vanishes) — **C11** |
| Zero-spend dividend untaxed | Confirmed, worse (also skips RMD) — **A2** |
| MC cash-reserve down-year understates depletion | Confirmed, much worse (bookkeeping bug) — **A1** |
| Override accounts zero dispersion | Confirmed, worse (legacy 0.07 backfill gap) — **A3** |
| 1972–2025 tail window | Confirmed — **C10** |
| Non-pool basis scope | Confirmed but moot (branch only fires when all-taxable) — closed |
| IRMAA warning-only | Confirmed — Tier 3 (Tier 2 for conversion-optimizer trust) |
| 2025 us_stock partial-year | Confirmed (−9bp on geomean) — Tier 3 |

## Recommended roadmap

- **Wave 1 — bugs + integration (all S/M):** A1 MC cash-reserve accounting; A2 RMD/dividend
  early-return; A3 legacy-override backfill migration; A4 fund outside-income tax (both engines);
  A5 dividend_yield→MC + staleness/seed signature (+ `@OrderBy`). Fixes the corrupted tail paths
  and the vacuous-confidence scenarios; largest correctness gain.
- **Wave 2 — systematic realism (S–M):** B1 fees; B2 SS provisional-income convergence (+ MC
  alignment + MFJ aggregation); C3 state warnings; D OBBBA data updates + age-65 deduction.
- **Wave 3 — structural (M–L):** C1 bond-interest split; C2 tax gross-up; C4 conversion-engine
  frame; C5 exact per-year tax; C6 floor-clamp flag; C7 deflation clock; C10 tail stress overlay.
- **Wave 4 — product-level:** C9 simulated guardrail rules; IRMAA dollars; C11 SinglePool; the
  Tier-3 catalog opportunistically.
