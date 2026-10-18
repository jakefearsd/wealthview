# Bond Tent — Design

**Date:** 2026-10-18
**Status:** Draft. Builder check done; awaiting owner approval.
**Roadmap:** Item B5 (Glidepath / bond tent) of `2026-10-04-tactical-retirement-planning-roadmap.md`, Phase 2. This
design replaces B5's "allocation as a function of age" sketch. It pulls in the parts of B2 (historical replays) and B6
(bucket upgrade) that a tent needs.

---

## 1. Intent

A **bond tent** protects a retiree from sequence-of-returns risk:

- In the years before and just after retirement, part of the portfolio is moved into bonds.
- When stocks are down, spending comes out of those bonds, so stocks are never sold low.
- When markets do well, spending comes from stocks. The bonds stay partly or fully intact.

The owner wants WealthView to answer two questions:

1. **How big must my tent be?** The user picks how many years of spending it should cover. The app works out the
   dollar size and the percentage of the portfolio, using the yields of the bond funds the tent is built from.
   Owner's rules of thumb:
   - About 10% of the portfolio for 2.5 years.
   - About 20% for 5–7 years.
   - Those should *fall out of* the math, not be hard-coded.
2. **How does my tent hold up in a real crash, and with which bond mix?** The user replays their plan through historical
   crashes and slides the crash earlier or later relative to their retirement date. They compare no tent against up
   to three bond mixes. Bonds don't always help: in 2022 they fell with stocks. The comparison must show that, and
   must show the cost of a tent in good markets.

**Direction (owner):** users *mock up* what they want to test — allocations, mixes, sizes — and run simulations on it.
WealthView will keep moving toward giving users more what-if simulation power. This design should leave room for that
(more scenarios, more levers) rather than hard-wiring a single answer.

## 2. Goals

- A per-scenario bond tent the user configures:
  - **Coverage:** years of spending the tent covers.
  - **Build-up start:** how many years before retirement the tent starts being built.
  - **Bond mixes:** up to three, each across four maturities: ultrashort, short, intermediate and long.
- **Sizing:** the dollar amount and the percentage of the projected portfolio at retirement needed to fully fund the
  chosen coverage, given the mix's yields.
- **Tent behaviour:** modeled in every simulation and replay, using the rules in §4.3.
- **Monte Carlo trials start today, not at retirement, for every scenario.** A crash can then hit while the tent is
  still being built.
- **Historical crash replays:**
  - Named crashes: 1973–74, 2000–02, 2007–09 and 2022.
  - Plus any start year.
  - Shiftable from 3 years before to 3 years after retirement.
- **A new "Bond tent" results tab** on the scenario's projection page, laid out per the approved mockup in §6.
- **The spending optimizer (guardrail profiles) certifies spending with the tent in place.**

## 3. Non-goals (v1)

- Using the four bond maturities **outside** the tent:
  - The rest of the portfolio keeps today's single "bonds" class.
  - Account allocation editors don't show the new maturities.
  - Existing holdings are not reclassified.
  - Consequence: the app can't yet say "your VGIT position already covers part of your tent."
- Fetching today's yields automatically. They are typed in, with defaults (§4.2).
- Synthetic shocks such as "−30% in year one" or a "lost decade". That's roadmap item B3, later.
- Rolling-period analysis (every start year, with a success count) and B1 sequence-risk diagnostics. Examples: success
  by first-decade-return quintile, failure-age histogram.
- TIPS, corporate bonds, individual bond ladders and specific funds. The app models maturity *classes*; SGOV, VGSH, VGIT
  and TLT are labels that make those classes recognisable.
- An age-based glide path (allocation as a function of age, without the spend-from-bonds-when-down behaviour).
- Mobile UI.

## 4. What gets built

### 4.1 Bond maturity data

Today `asset_class_returns` (seeded by `R__seed_asset_class_returns.sql`) holds real annual returns for 1928–2025 for
four classes:

- `us_stock`
- `intl_stock`
- `bond`: the 10-year Treasury, from Damodaran.
- `cash`: the 3-month T-bill.

The tent needs four maturities:

| Tent maturity | Example fund | Data |
| --- | --- | --- |
| Ultrashort | SGOV | **Reuse** the existing `cash` (3-month T-bill) series. |
| Short | VGSH (1–3 yr) | **New** series. |
| Intermediate | VGIT (3–10 yr) | **New** series. |
| Long | TLT (20+ yr) | **New** series. |

Requirements:

- **Same treatment as the existing data:** real (CPI-deflated) annual total returns, using the same CPI series and
  method as the existing seed.
- **Documented sources:** each new series gets a header comment in the seed, like the existing ones. Constant-maturity
  Treasury yields (FRED) converted to total returns is the expected route. The builder picks and documents the method.
- **Coverage:** the default 1972–2025 window is required.
  - FRED constant-maturity yields start in 1953, so 1928–1952 needs another credible source; NBER Macrohistory
    long-bond yields are one candidate.
  - Never fill gaps with invented numbers.
  - **My call if no credible pre-1954 source is found:**
    - The `include_depression_years` option is disabled while a tent is configured, and the UI says why.
    - Replays fall back to long-run averages for any year a maturity series lacks.
    - The "any start year" picker only offers years with full data.
- **Sanity checks:** the series must reproduce the well-known shapes:
  - Long Treasuries rallied in 2008.
  - All maturities fell in 2022, long ones the most.
  - Short maturities barely moved in either year.
- **Builder's call:** whether the new classes become `AssetClass` values or a tent-only type. Either way, nothing
  outside the tent may change behaviour because of them.

### 4.2 Tent configuration (per scenario)

Stored with the scenario; the builder picks `params_json` or a new column/migration.

| Field | Meaning |
| --- | --- |
| Coverage | Years of retirement withdrawals the tent should fund. 1–10, in half-year steps. Half-years matter: "2.5 years" is a real choice. |
| Build-up start | Years before retirement at which the tent starts being built. 0 means it is built at retirement. |
| Mixes (1–3) | Each mix has a name and a share for each of the four maturities. Shares are 0–100% and sum to 100%. |
| Plan mix | Which mix the main projection and the spending optimizer use. **My call:** the first mix is the plan mix; the others are comparison-only. |
| Today's yields | One nominal yield per maturity, editable. |

Notes on today's yields:

- They are typed in and prefilled with defaults.
- **My call:** the defaults ship as a seeded or constant set, refreshed by hand at release time. Live fetching is a
  later feature.

Notes on scenarios without a tent:

- The deterministic projection is unchanged for a scenario with no tent.
- Its Monte Carlo results still move, because trials now start today for every scenario (§4.4).

### 4.3 Tent rules

These rules apply in every engine and every replay:

1. **Sizing (target size).** Take the planned *portfolio withdrawals* for the first `coverage` years of retirement:
   spending, minus income (Social Security, pensions, rent), plus the tax on what is withdrawn. These come from the
   deterministic projection. The target is the smallest amount that pays all of them, given **that mix's** expected
   real yield.
   - **My call: each mix is sized by its own yields.** Each comparison column answers "this mix, sized to cover N
     years".
   - **My call: no feedback loop.** Sizing reads the withdrawals from one deterministic pass and does not iterate,
     even though the tent itself slightly changes taxes and balances.
   - **My call: in the optimizer, coverage is the fixed input, not dollars.** For each candidate spending level the
     search tests, the tent is re-sized with the same rule, from that candidate's withdrawals. A certified guardrail
     profile stores the tent target it was certified with.
   - The yearly spending and income arrays the Monte Carlo already has are enough for this. No deterministic run
     inside the search.
   - The nominal yields from §4.2 are converted to real using the scenario's inflation assumption.
   - A partial final year (for example 2.5 years) counts at its fraction.
   - The target is stored and shown as real dollars and as a percentage of the projected portfolio at retirement.
   - In simulations the target is held in **real dollars**, not as a percentage. A trial that reaches retirement with
     less money has a tent that is a larger share of that smaller portfolio. Coverage is about years of spending,
     not a ratio.
2. **Build-up.** From the build-up start to retirement, the tent grows in a straight line from $0 to the target,
   in real dollars.
   - Build-up start 0 means the tent is funded in full at retirement, whatever the market is doing. This matches how
     today's cash reserve is seeded.
   - **My call:** the line is the tent's *balance* (market value), not cumulative purchases. New contributions go into
     the tent first. Growth assets are sold to make up the rest **only in years when stocks are at or above their
     previous high** (the same test as rule 4).
   - In a down year during build-up, only contributions go in. A crash mid-build-up never forces stock sales, and
     the tent may enter retirement under target. Bond losses during build-up are topped up under the same rule.
   - As with today's annual rebalancing, moves inside accounts are untaxed. The builder documents any
     taxable-account gain this simplification ignores.
   - **My calls on timing edge cases:**
     - **Retirement date already past (or today):** the tent is in place today at full target. This is a mocked-up
       starting point. Coverage counts from today.
     - **Build-up start earlier than today:** the ramp starts today from $0 and reaches the target at retirement, so
       it is compressed.
     - **Replays:** crash offsets that would place the crash before today are disabled.
3. **Inside the tent:**
   - Each maturity sleeve earns its own historical return: in simulations, the bootstrapped year; in replays, the
     actual year.
   - Sleeves are **not rebalanced** against each other.
   - Draws come **from the shortest maturity first**: ultrashort, then short, then intermediate, then long.
4. **When to draw from the tent.** A retirement year draws from the tent when **stocks are below their previous high**.
   - **My call on what "stocks" means:** the growth portfolio's cumulative real return index — the non-tent part,
     using its own allocation — compared with its running peak.
   - Withdrawals never count as a decline. Only market performance does.
   - Otherwise the year draws from the growth portfolio.
   - If the tent can't cover the whole draw, the rest comes from the growth portfolio.
5. **No refill.** A spent tent is not rebuilt.
6. **Leftover stays.** Whatever remains after the covered years stays in bonds, in its maturities, for the rest of the
   plan. It is not shifted back into stocks. It is still drawn under rule 4 in later down years.
7. **Tax treatment.** A tent draw changes *which asset is sold*, never *which account type is drawn* or how the
   withdrawal is taxed. The tent is held in the accounts the plan draws from during the covered years: usually the
   brokerage account for an early retiree, often an IRA later.
   - Bond interest earned in a taxable account is taxed as ordinary income, through the existing taxable-yield split.
   - Exactly how the tent is distributed across tax pools is the builder's call, within this principle.
8. **Replaces the cash reserve.** Owner: a cash reserve is just a simple, degenerate form of a bond tent, with the
   money held in a money market fund. A user who wants a cash reserve configures a tent whose mix is 100%
   ultrashort.
   - The optimizer's stand-alone cash-reserve setting (`cash_reserve_years` / `cash_return_rate` on guardrail
     profiles) is retired from the UI and the engine.
   - **Existing reserves become equivalent tents (owner).**
     - Every scenario whose guardrail profile has `cash_reserve_years > 0` gets a tent with:
       - coverage = those years
       - one mix, 100% ultrashort
       - build-up start 0, so it is in place at retirement
     - The conversion is a data migration. The old columns stay in the database (never deleted), and the engine
       stops reading them.
     - **Expected differences from today's reserve**, to be documented in the CHANGELOG:
       - **No refill.** The old reserve topped itself up in up years; a tent is not refilled (rule 5).
       - **Different trigger.** A tent is drawn when stocks are below their previous high, not in any negative year.
         This also fixes roadmap defect D8.
       - **Historical returns.** It earns historical T-bill returns instead of a fixed `cash_return_rate`.
       - **Deterministic projection.** The reserve now appears there too, as cash that is held. It used to exist only
         in the optimizer.
   - **My call:** new scenarios start with no tent, and the user opts in. The old default of a 2-year reserve is not
     carried forward to new scenarios.

### 4.4 Engines

The roadmap rule is that every engine-level feature names the engine(s) it lands in.

- **Monte Carlo** (`TrialSimulator` / `MonteCarloSpendingOptimizer` and friends):
  - Tent rules §4.3, tracked per trial.
  - Trials start at the base year (today) **for every scenario**, with or without a tent (owner decision).
    Pre-retirement contributions and growth run through bootstrapped market returns.
  - This replaces `PreRetirementAccumulation`'s smooth expected path.
  - **My call on pre-retirement trial years:** they model contributions, market growth, fees and tent build-up. They
    do *not* add pre-retirement income tax, Roth conversions or RMDs on a still-working owner. That keeps today's
    `PreRetirementAccumulation` scope, and the gap is documented, not widened.
  - Longer trials for young users are a known performance cost. It is checked under the performance constraint
    (§5).
  - Every scenario's Monte Carlo results move, including success rates and certified spending. Existing guardrail
    profiles become stale through the signature change and are re-optimized under the new model.
  - The new maturity series join the shared block-bootstrap index sequence, so stock/bond correlation within a year
    is preserved.
- **Deterministic** (`DeterministicProjectionEngine` / `PoolStrategy`):
  - Models build-up, sleeves and rules §4.3.
  - It runs on constant average returns, so stocks never fall below a previous high, and the tent is built and
    then held, never drawn.
  - That is honest: the main projection shows the tent's *cost*, and the replays show its *benefit*. The tab's copy
    should say so.
- **Historical replays** run the deterministic engine on a **per-year historical return path** instead of constant
  averages: roadmap B2's "optional per-year return path". That gives replays the full model: income sources, rentals,
  RMDs, conversions, household and taxes.
  - **Alignment:** the crash's first year (1973, 2000, 2008 or 2022, or the user's chosen start year) is placed at
    retirement + offset, with offset from −3 to +3.
  - **Years before it** use the preceding historical years.
  - **Years after the data ends** (after 2025) continue at long-run average returns. The chart marks where history
    runs out. **My call.**
  - **Years before the data starts** fall back the same way.
- **Fixed-spending Monte Carlo evaluation (new).** Today, success probability is only computed inside the guardrail
  optimizer. The tent tab needs the success rate of the scenario's *current* spending plan, held fixed, for no tent
  and for each mix.
  - This is a new evaluation path that reuses the trial simulator.
  - **My call on a guardrail-profile plan:** when the active spending plan is a guardrail profile, its yearly
    spending is used as the fixed plan.
- **Optimizer:** guardrail optimization runs with the plan mix's tent in place. The tent configuration joins the
  scenario signature in `GuardrailProfileService`, so the seed and cache key change and profiles go stale when the
  tent changes.
- **Not tent-aware in v1: the Roth conversion optimizer's internal `ConversionSimulator`. My call.** Its schedules
  ignore that a tent held in an IRA grows more slowly, which slightly overstates future RMDs. This is documented as a
  known gap and joins the Phase 1c carry-over list.
- **Spending plan required. My call.** The tent and the tab need an active spending plan: a tier profile or a
  guardrail profile. With "None" (a withdrawal-rate strategy) the tent section explains that a spending plan is
  needed, and the tab is hidden.

### 4.5 Sizing display

This appears in the scenario form, under "Bond tent". Approved mockup: Part 1 of
`.superpowers/brainstorm/*/content/tent-setup-and-results.html`.

- Coverage picker, build-up start, mix chips (up to 3, plus "Add mix").
- Per maturity: example fund, editable today's yield, share of the selected mix.
- **Result box:**
  - "Tent size needed: $X · Y% of your projected $Z at retirement".
  - One line explaining why it differs from the rule of thumb, for example "all five years come before Social
    Security starts at 67".
  - The per-year withdrawals it covers.
- A read-only line restating the rules in §4.3.

## 5. Constraints

- **Engine conventions:** real terms (today's dollars) throughout; `BigDecimal` in the deterministic engine, `double`
  in Monte Carlo — the existing money-type split.
- **Data immutability:** never edit a released migration. Seed data changes go in the repeatable seed.
- **Tests:**
  - TDD per CLAUDE.md, with both engines covered.
  - Deterministic goldens stay byte-identical for scenarios without a tent.
  - Monte Carlo-dependent goldens and pins move, because trials now start today. Every delta is explained, not just
    re-recorded.
  - Add a **bond-tent golden** and a **2007–09 replay golden**.
- **Quality gates:** all must pass (PMD, CPD, SpotBugs, Checkstyle, JaCoCo floors).
- **Performance:** the tent tab runs up to 4 fixed-spending Monte Carlo evaluations (no tent plus 3 mixes) and cheap
  deterministic replays. It must stay within today's optimize latency budget, checked against the Micrometer timers.
  The builder picks the trial count.
- **Disclaimer:** the existing tax and model disclaimer extends to the tent tab. Yields are user-entered assumptions.

## 6. Results tab (approved layout)

Approved mockup: `results-b-v2.html` in the same brainstorm folder. The tab is a new "Bond tent" tab on the scenario's
projection page and has two parts.

1. **Simulation summary strip.** One column for no tent, then one per mix. Rows:
   - Success rate.
   - Years the tent actually covered, as typical (median) / worst 10%.
   - Typical balance at 90.
2. **Crash replay.**
   - Crash picker: 1973–74, 2000–02, 2007–09, 2022, or any start year.
   - Crash-timing control: from 3 years before to 3 years after retirement.
   - One balance chart with a line for no tent and one per mix. Retirement and the crash window are marked.
   - A table under the chart with one column per choice. Rows:
     - Lowest balance.
     - Stocks sold while down.
     - Years covered against the planned number.
     - Tent left over (at a fixed checkpoint such as 70; the checkpoint is the builder's call).
     - Balance at 90.

The tab must make both sides visible: the protection in crashes, and the cost when markets are good.

### 6.1 Explaining a cash reserve as a tent

Owner: the UI needs text explaining that a cash buffer is the simplest form of bond tent. The draft copy below is
mine. The owner is open to changing it.

- **Where the cash-reserve control used to be (optimizer page):**
  > Cash reserve has moved. A cash reserve is the simplest kind of bond tent: a few years of spending held in a money
  > market fund. Set it up under **Bond tent** in your scenario.
- **One-time notice on a converted scenario:**
  > Your 2-year cash reserve is now a 2-year bond tent, held entirely in ultrashort Treasuries (the same thing as a
  > money market fund). It works the same way with one difference: once spent, it isn't refilled, so results may
  > shift slightly from before.
- **Hint in the tent section when a mix is 100% ultrashort:**
  > 100% ultrashort is a classic cash reserve. Moving part of it into short or intermediate bonds usually earns more,
  > at the cost of some price swings.

### 6.2 Metric definitions (my calls)

- **Years covered** is measured at the end of the covered window. It has two parts:
  - **Drawn:** for each year the tent was drawn, the draw divided by that year's planned withdrawal, summed.
  - **Unused:** the tent left at the end of the window, divided by the average planned withdrawal of the covered
    years.

  An intact tent in a good market therefore scores near its planned coverage, not zero. Bond gains push the score
  up (2008, long bonds); bond losses push it down (2022). That is the risk the number exists to show.
- **Worst 10%** is the 10th percentile of years covered across trials.
- **Rule-of-thumb baseline** in the sizing line is `coverage × 4%` of the projected portfolio at retirement. The
  explanation names the largest reason the sized target differs, chosen from:
  - income starting or ending inside the window (Social Security, pension, rent)
  - tax on withdrawals
  - the mix's yields

## 7. Left to the builder

- Data method for the new maturity series. Source choices are documented; gaps are raised, never invented.
- `AssetClass` values vs a tent-only type.
- Storage location for the tent configuration.
- How the tent is spread across tax pools, within §4.3 rule 7.
- Trial count for the tab's evaluations.
- The tent-left-over checkpoint.
- Chart library details, following the existing frontend.
- API shape for the tent tab, following existing conventions: snake_case JSON, records, and controllers with no logic.

## 8. Owner decisions

- **Trial start:** Monte Carlo trials start today for **every** scenario, not only those with a tent (2026-10-18).
- **Cash reserve:** a cash reserve is a degenerate tent held in a money market fund. Existing reserves convert to
  equivalent 100%-ultrashort tents, and the UI explains the framing (2026-10-18). See §4.3 rule 8 and §6.1.
