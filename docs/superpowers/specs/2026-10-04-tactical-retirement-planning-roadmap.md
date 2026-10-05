# Tactical Retirement Planning — Roadmap

**Date:** 2026-10-04
**Status:** Owner-reviewed 2026-10-05. Decisions recorded in §8. Each phase gets its own detailed `-design.md` spec.
**Scope:** Move WealthView from *"here is your projection"* to *"here is what to do this year, and why."* There are three pillars:

1. Early-retirement ("bridge years") tax planning.
2. Preparing for sequence-of-returns risk.
3. A unified, explained action plan.

**Basis:** A code-verified gap analysis from 2026-10-04 (memory: `project_tactical_gap_analysis_2026_10_04.md`).

---

## 1. Problem statement

The engines are sophisticated. They include:

- Block-bootstrap Monte Carlo (MC).
- Per-lot capital gains.
- RMDs.
- Household and survivor modeling.
- A joint spending/conversion optimizer.

But the product answers *"will I be OK?"* and not *"what do I do?"*. Two areas the owner cares about are weak:

- **Bridge years.** This is the stretch between retirement and age 59½, Social Security (SS), or Medicare, typically funded from a brokerage account. The Roth conversion optimizer is built around shrinking the traditional balance before RMDs. It gives **misleading answers** for a brokerage-funded early retiree:
  - It ignores capital-gains tax on brokerage withdrawals.
  - It can veto conversions that cost $0.
  - It knows nothing about ACA subsidies.
- **Sequence risk.** The MC *measures* it implicitly but never *isolates* it, never *stress-tests* it, and offers few *levers* against it:
  - Allocation is static, with no glidepath.
  - The cash bucket is crude.
  - There are no dollar guardrail triggers.

Today the actionable pieces are scattered:

- Spending → `NearTermSpendingGuide`.
- Conversions → `ConversionScheduleTable`.
- Per-account withdrawals → Data Table columns.

Nothing combines them, and nothing explains them in the user's own numbers.

## 2. Verified defects to fix first (they block trust in any recommendation)

| #   | Defect                                                                                                                                                                                                                                                                                                                   | Location                                                                                 |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------- |
| D1  | The conversion simulator treats pre-60 brokerage withdrawals as tax-free. So it can't see conversions pushing gains out of the 0% LTCG band (MFJ 2025: the 0% LTCG ceiling ≈ $96.7k taxable, and the 12% ordinary bracket ends at $96.95k). As a result, "lifetime tax savings" is overstated for brokerage-heavy users. | `ConversionSimulator.EarlyWithdrawalStrategy` (~:262)                                    |
| D2  | The RMD target-balance cap zeroes conversions whenever projected RMDs fit the RMD-target bracket, even when the bridge years have free 0%/10% space.                                                                                                                                                                     | `RothConversionOptimizer.computeTargetTraditionalBalance`, `ConversionSimulator` (~:468) |
| D3  | The optimizer uses a single filing status for all years (it misses the widow's penalty), is federal-only, and uses a static SS taxable share (it misses the SS "torpedo").                                                                                                                                               | `ConversionSimulator`, `OptimizationContextBuilder` (~:266)                              |
| D4  | The `bracketUsed` field is always null, so the conversion table's Bracket column is blank.                                                                                                                                                                                                                               | `GuardrailResponseBuilder` (~:380)                                                       |
| D5  | When taxable lots are sold to pay tax, the realized gain is discarded and never taxed.                                                                                                                                                                                                                                   | `PoolStrategy.deductFromPools`, `TrialSimulator` (~:589, ~:1047)                         |
| D6  | The MC and optimizer forbid all Roth draws before 60. Roth contribution basis is always accessible, so plans that would survive on that basis report false failures.                                                                                                                                                     | `TrialSimulator.splitWithdrawal` (~:1161)                                                |
| D7  | The UI sends `cash_return_rate` 4% **real**, against a backend default of 1.5%. 4% real cash is optimistic.                                                                                                                                                                                                              | `frontend/src/utils/optimizerConfig.ts:59`                                               |
| D8  | Cash-bucket refill triggers on any non-negative year, including +1% right after a −30% crash.                                                                                                                                                                                                                            | `TrialSimulator` (~:1103)                                                                |

D1/D2/D3/D6 are fixed structurally by Workstream A. D4/D5/D7/D8 are small standalone fixes.

## 3. Guiding principles

1. **One tax truth.** The optimizer should size conversions with the same tax model the engines charge: ordinary + LTCG stacking + NIIT + SS taxation + state + ACA + IRMAA. Where a fast approximation is unavoidable for performance, label it in code and in the UI.
2. **Every recommendation carries its "why" in numbers.** For example: *"Convert $38,400 — fills the 12% bracket; effective marginal rate 13.8% incl. lost ACA credit; projected rate on this money at RMD age: 22%."*
3. **Think in tax space, not tax brackets.** Each year has a finite, competing set of budgets: ordinary bracket room, 0% LTCG room, ACA MAGI cliff/target, IRMAA tier headroom, and the SS torpedo zone. Conversions, gain harvesting, and traditional draws all spend from those budgets.
4. **Keep the real-terms frame** (today's dollars). All new thresholds are deflated the way `SocialSecurityTaxCalculator` and `IrmaaSurchargeCalculator` already are.
5. **Law-dependent numbers live in seeded tables**, never constants. Examples: ACA FPL and applicable-percentage schedule, Medicare base premiums. Use repeatable `R__seed_*` migrations with year fallback, like brackets and IRMAA.
6. **Build schedules deterministically, then evaluate them under MC** (the existing pattern). Market-adaptive *policies* (guardrail triggers, bucket rules) are MC-native.
7. **Add new perspectives as new views** (per owner feedback). The action plan is a new page, not a rework of `ProjectionDetailPage`.
8. **Use what WealthView uniquely has: live holdings.** A spreadsheet planner can't compare actual balances, cost basis, and YTD transactions against the plan's triggers. We can.

## 4. Workstreams

Sizes: **S** ≤ 5 tasks · **M** 5–12 · **L** 12–25 · **XL** 25+. For reference, realism-v2 was ~105 commits across 4 phases.

### Workstream A — Bridge-years tax planner

**A0. Standalone defect fixes (S).** D4, D5, D7, D8 from §2. Small and independent; can land at any time.

**A1. Tax-space calculator (M) — the foundation.**

- A per-year service in `wealthview-core` that, given a year's income picture, returns:
  - Ordinary-bracket room up to each bracket ceiling.
  - 0% / 15% LTCG band room. It must account for stacking: ordinary income consumes LTCG room first.
  - ACA: MAGI distance to the cliff (or to the next applicable-percentage step), plus the marginal premium-credit loss per $1 of MAGI.
  - IRMAA: MAGI distance to the next tier, applied with the 2-year lookback. Income at age 63 or later sets premiums at 65 or later.
  - SS torpedo: the zone where $1 of extra ordinary income makes $0.50–$0.85 of benefits taxable.
  - **Effective marginal rate**: the true cost of the next $1,000 of ordinary income and of LTCG, with all of the above included.
- Shape: a record DTO plus a pure calculator, reusing `FederalTaxCalculator`, `CapitalGainsTaxCalculator`, `SocialSecurityTaxCalculator`, `IrmaaSurchargeCalculator`, and a new ACA calculator.
- Consumers: the optimizer (A2), harvesting (A3), the explanations (C2), and the action plan (C1).

**A2. Conversion optimizer rework (L).**

- **Fix D1:** the simulator models brokerage withdrawals with lots and LTCG stacking, reusing `TaxableLots` / `LtcgTaxTable`. It does not invent a third tax model.
- **Fix D2:** replace the hard target-balance veto with a soft objective term. Always allow conversions whose effective marginal rate is at or below a floor; at minimum, use the standard-deduction 0% space.
- **Fix D3:** apply the first-death filing flip, use per-year SS taxability from A1, and add state tax via a precomputed per-year state marginal rate.
- **Per-phase decision variables** replace the single scalar fraction:
  - *Bridge* (retirement → SS/pension start), *pre-RMD* (SS on → RMD age), and *RMD* (no conversions).
  - Each phase gets a ceiling expressed as a tax-space target: "fill to top of 12%", "stay under ACA cliff", "stay under IRMAA tier 1", or a dollar cap.
  - Search: coordinate descent over a small grid (~3 phases × ~8 levels), scored deterministically. The top-K candidates are then re-scored under MC, as `JointConversionSearch` does today.
- **Objective (§8 Q2):** the user stack-ranks three goals: *maximize sustainable spending*, *minimize lifetime tax*, *maximize legacy* (after-tax terminal value, with traditional dollars valued at a configurable heir tax rate). Candidates are compared lexicographically, with a tolerance band per goal so that a near-tie on the top goal is decided by the next one. Default ranking: spending → legacy → tax.
- **Who pays the tax:** keep brokerage-first. Add an explicit option to withhold from the conversion, which is penalized before 59½, so the trade-off is visible.

**A3. Tax-gain harvesting (M).**

- A strategy available to both engines: in years with unused 0% LTCG room left after conversions, realize gains up to that room (or up to a MAGI cap) and reset basis. Implement it as sell-and-rebuy on the lot model.
- Becomes a decision variable in A2. Conversions and harvesting compete for the same room, and the optimizer chooses the split.
- Output: harvest $ per year, basis stepped up, and future LTCG avoided.

**A4. Healthcare costs, ACA, and Medicare (M–L).**

- **Pre-65:**
  - User inputs: one benchmark (SLCSP) annual premium for the household *today*, and an expected out-of-pocket amount. The engine ages that premium per member using the federal default age-rating curve (seeded table; 3:1 adult band, with the age-64 rate about 3× the age-21 rate). No per-member premium entry (§8 Q8).
  - A seeded table holds the FPL by year and household size, plus the applicable-percentage schedule. **One schedule: current law** (§8 Q1). Whether there is a cliff is a property of the table, and the table is structured so a change in law becomes a new seed row, not a code change.
  - Compute the premium tax credit from projected MAGI. The net premium becomes a spending line in both engines.
- **65+:** base Part B/D premiums become a spending line. IRMAA moves from a deterministic-only surcharge into the MC as well, and becomes a *constraint* the optimizer can respect.
- This is what makes "stay under the ACA cliff" a real, priced option in A2 rather than a warning.

**A5. Early-access rules and Roth layering (L).**

- **Roth layers per owner pool:** contribution basis (new input on `projection_accounts`, seeded from data where available), conversion vintages each with their own 5-year clock, and earnings.
  - Apply the IRS ordering rule: contributions, then conversions (FIFO), then earnings.
  - Pre-59½ Roth draws become possible and correctly priced. This fixes D6.
- **Roth conversion ladder:** emerges naturally — conversions at 50 become accessible at 55. The output shows "ladder rungs available by year".
- **Rule of 55:** a per-account flag (employer plan only) plus separation year → penalty-free traditional draws from that account.
- **72(t)/SEPP:** *undecided, see §8 Q4.* If included: amortization method, fixed payment, locked until the later of 5 years or 59½.
- **59½ precision:** replace the age-60 whole-year proxy with exact ages. Add birth month for the primary and the spouse (§8 Q3). Early-access, Medicare (65) and RMD start then resolve to the correct year (with a partial year where it matters).

**A6. Social Security claiming analysis (M).**

- Input the PIA (benefit at full retirement age) instead of a flat amount. Apply the early-claim reduction (5/9% per month for the first 36 months, then 5/12%) and delayed credits (8%/yr to 70). Optionally add the spousal benefit (up to 50% of the spouse's PIA).
- Comparison mode: claim at 62 / FRA / 70 for each spouse. Show the deterministic outcome, MC success, lifetime tax, breakeven age, and the survivor-benefit effect (survivor logic exists already).
- Interacts with A2: delaying SS lengthens the bridge, which means more conversion room.

**A7. Bridge runway metric (S).**

- "Accessible assets" = taxable + cash + accessible Roth layers + Rule-of-55 accounts.
- Show how many years they fund until each milestone (59½, SS start, pension, Medicare), and the shortfall or surplus in $.
- Displayed prominently; feeds C1.

### Workstream B — Sequence-of-returns risk

**B1. Sequence-risk diagnostics (M).** These are mostly captured per trial in `TrialPassRunner` — the data already flows through it.

- Capture per trial: first-failure year, annualized real return for the first 10 years, and minimum balance.
- Outputs:
  - **Failure-age histogram.**
  - **Success rate by quintile of first-decade return.** This is the "how much does a bad start hurt *me*" chart.
  - **Red-zone chart:** outcome sensitivity to returns in each year around retirement.
  - Perfect-withdrawal-rate distribution (optional).

**B2. Historical replays and rolling-period analysis (M).**

- The deterministic engine gets an optional per-year return path (per asset class, real), replacing the constant geometric means.
- Named replays: retire into 1929, 1966, 1973, 2000, 2008.
- **Rolling-period analysis:** every start year in the window, with success counts in the style of cFIREsim / FIRECalc.
- The data (`asset_class_returns`) already exists.

**B3. Stress shocks (S).**

- User-defined overlays: "−30% equities in year 1", "lost decade (0% real for 10 years)", "2 bad years then recovery".
- Applied to the deterministic engine via B2's path input, and to the MC as a prepended overlay. Compare against the baseline.

**B4. Dollar guardrail triggers (M–L) — the most actionable item in this workstream.**

- For each year, give portfolio balances at which the plan says to cut or raise spending: *"If your portfolio falls below $1.42M, reduce discretionary spending to $61k; above $2.10M you may raise it to $84k."* This is the style of Kitces risk-based guardrails.
- Method:
  - Years 1–5: bisection on the starting balance, at a reduced trial count, against the certified success band.
  - Later years: derive from the existing corridor and median-path ratio.
- **Rule family (§8 Q5):** the default becomes a named, explainable rule. Offer a small set of well-understood, widely accepted strategies, and only those that produce materially different outcomes. Expected starting set: risk-based guardrails (default) and Guyton-Klinger. The current ratio-to-median rule is retired, or kept internally only if it stays meaningfully distinct. The certified plan is always evaluated under the rule the user selects.

**B5. Glidepath / bond tent (L).**

- Allocation becomes a function of age.
  - Per account: allocation now, at retirement, and at end of plan, with linear interpolation between them.
  - Presets: static, declining, and **bond tent** (equity dips around retirement, then rises).
- Requires `PortfolioPathGenerator` / `PortfolioReturnResolver` to use per-year weights. It also fixes the frozen-at-initial-balance pool weighting. The deterministic engine uses per-year geometric blends.

**B6. Bucket strategy upgrade (S–M).**

- Refill only when the portfolio has recovered (e.g. it is at or above its trailing peak, or at or above the median path), not on any up year. This also fixes D8.
- The bucket can hold `BOND` as well as `CASH`, and earns the bootstrapped class return instead of a fixed rate.
- Optionally add a deterministic-engine bucket, for display.

**B7. Market-aware conversions (L) — *candidate non-goal.*** The MC conversion schedule would scale with the trial's market state: convert more after drawdowns, since the same tax buys more shares. This is research-grade; revisit after A2.

### Workstream C — Action plan and explanation

**C1. "This Year" action plan (L).**

- New endpoint `GET /api/v1/projections/{id}/action-plan` and a new page. It assembles results from the deterministic run, the active spending plan or guardrail profile, A1 tax space, and B4 triggers. It covers the current year plus a 4-year look-ahead.
- Contents:
  - **Spending:** target, essential vs discretionary, guardrail triggers (B4), and live portfolio value against those triggers.
  - **Withdrawals:** $ from each *actual linked account*. Lot guidance: "sell lots with the highest basis first", if we adopt that.
  - **Conversion:** $ amount, from which account, deadline Dec 31, and the "why" line.
  - **Gain harvest:** $ amount, from which lots.
  - **MAGI plan:** target MAGI, headroom to each cliff (ACA / IRMAA / LTCG 0%), and which year's IRMAA it sets.
  - **Taxes:** estimated federal + state, and suggested quarterly estimated payments or withholding.
  - **Bucket:** target, current, and refill or draw instruction.
  - **Milestones and deadlines:** 59½, Medicare enrollment window, IRMAA lookback year, RMD start and first-RMD deadline, SS claim decision.
- Partial-year reality: YTD income, realizations and conversions are derived from imported transactions, and the user can enter or override any figure manually; manual entries win (§8 Q6). The remaining-year recommendation is the full-year target minus YTD actuals.

**C2. Effective-marginal-rate view and explanations (M).**

- A per-year chart and table of the **true marginal rate** on ordinary income and on LTCG, from A1. It shows the ACA and torpedo humps and the IRMAA steps.
- This is the one picture that explains every conversion and harvest decision.
- Structured "why" strings attached to each recommendation, generated server-side from the numbers. No LLM required.

**C3. Compare upgrade (S–M).**

- Add these metrics: lifetime tax (all-in, including ACA/IRMAA), MC success, after-tax terminal value, total conversions, and healthcare cost. Add a delta column.
- Unsaved "variants" of one scenario: SS age, retirement age, conversion on/off, glidepath. These are cheaper than cloning scenarios.

**C4. After-tax terminal value (S).** Traditional is valued at the heir rate and taxable at basis + gain (with step-up at death). Needed by A2's objective and C3.

**C5. Live plan status on the dashboard (S–M).** A widget: actual portfolio vs this year's guardrail triggers, YTD withdrawals vs plan, and YTD conversions vs plan. Uniquely possible because WealthView has live holdings and transactions.

## 5. Phasing (each phase becomes its own detailed spec)

| Phase | Contents | Size | Why this order |
| --- | --- | --- | --- |
| **1 — Tax foundation and optimizer correctness** | A0, A1, A2 (D1/D2/D3 fixes + per-phase variables + ranked objective), A4, C4, 59½ birth-month precision (from A5) | XL | Every later recommendation is a tax recommendation. Fix the model before making it louder. |
| **2 — Sequence risk** | B1, B2, B3, B4, B5, B6 | L | Largely independent of A, and B1–B3 are cheap and visible. Moved ahead of bridge-years strategy (§8 Q7). |
| **3 — Bridge-years strategy** | A3, A5 (Roth layers, Rule of 55, SEPP if adopted), A6, A7, C2 | L–XL | Builds the bridge-years toolkit on top of correct tax space. C2 is where it becomes legible. |
| **4 — Action plan** | C1, C3, C5 | L | The integration layer. It needs A1, A2, A3 and B4 to have something to say. |

Dependency sketch: `A1 → A2 → A3`, `A1 → C2`, `A4 → A2`, `C4 → A2`, `B2 → B3`, `B1/B4 → C1`, `A1/A2/A3/B4 → C1`, `C1 → C5`.

## 6. Cross-cutting concerns

- **Migrations (estimated):**
  - ACA tables: FPL and applicable-percentage schedule (seeded).
  - Medicare base premiums (seeded).
  - `projection_accounts.roth_contribution_basis`, a `rule_of_55` flag, and the separation year.
  - Glidepath columns or a JSONB field on `projection_accounts`.
  - Income source PIA / claim-age fields.
  - Birth month for the primary and the spouse (scenario / household).
  - Seeded ACA age-rating curve.
  - Scenario healthcare inputs.
  - Guardrail profile: new optimizer config (phase targets, objective ranking, heir rate, harvesting toggle, guardrail rule family).
  - The current latest version is V080.
- **Performance:**
  - The per-phase grid × MC re-score must stay within today's optimize latency budget. Check against the existing Micrometer timers and the `docs/quality` baseline.
  - B4 bisection runs at a reduced trial count.
  - B2 rolling-period runs are ~50–90 deterministic runs and should be parallelizable.
- **Goldens:**
  - Add a **bridge-years golden**: retire at 52 on brokerage + traditional, SS at 70, ACA years, conversions.
  - Add a **historical-replay golden** (1966 start).
  - Keep the existing 6+1 goldens green, or document intentional deltas.
- **Two engines.** Every engine-level feature must state which engine(s) it lands in. Default: both. Exceptions must be explicit and surfaced in the UI (as IRMAA's MC gap is today).
- **Mobile.** The action-plan endpoint is designed to be mobile-consumable. A mobile UI is out of scope for these phases.
- **Disclaimers.** Recommendations are model outputs, not tax advice. Extend the existing tax disclaimer to the action plan.

## 7. Non-goals (for this roadmap)

- Tax-loss harvesting and wash-sale tracking.
- HSA modeling.
- QCDs and charitable bunching (candidate follow-up after Phase 4).
- Stochastic inflation.
- An asset-location optimizer.
- Specific-lot / HIFO selection for *withdrawals*. The engine stays FIFO; C1 may still *suggest* lots.
- Annuity / SPIA purchase analysis. Not planned for portfolios under $10M, and doubtful even above that.
- Filing actual returns or producing tax forms. This will never be a goal.
- B7 market-aware conversions, unless promoted.

## 8. Owner decisions (2026-10-05)

1. **ACA law:** model **one schedule — current law**, table-driven. Changes in law are near-certain, so the software must absorb them as data updates (new seed rows), not code changes.
2. **Optimizer objective:** the user **stack-ranks** maximize spending / minimize lifetime tax / maximize legacy. See A2.
3. **59½ precision:** **add birth month** so early-access ages are exact. Lands in Phase 1.
4. **72(t)/SEPP:** **still open.** Decide during the Phase 3 (bridge-years) spec.
5. **Guardrail rule family (B4):** the default is a **named, explainable rule**. Offer a small set of the best-understood, widely accepted strategies, and only where they differ materially in outcome.
6. **Current-year data:** **both.** The action plan derives YTD income, realizations and conversions from imported transactions, and the user can enter or override any figure manually (for activity outside WealthView, or to correct an import). Manual entries win over derived values.
7. **Phase order:** **sequence risk moves ahead of bridge-years strategy.** Phase 2 = sequence risk, Phase 3 = bridge-years (see §5).
8. **Healthcare inputs:** **one estimated household premium**, scaled with age by the engine via the federal default age-rating curve. No per-member entry.
