# Bond Tent Plan 5 — Crash Replays, the Fixed-Spending Simulation and the Bond Tent Tab — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A scenario with a spending plan and a bond tent gets a "Bond tent" tab on its projection page. The tab shows the Monte Carlo success of the current spending plan with no tent and with each mix, and replays the plan through real historical crashes that the user can slide around retirement.

**Architecture:**
- **Replays run the deterministic engine on a per-year historical return path** (spec §4.4, roadmap B2).
  - A core `CrashReplayPaths` component maps each calendar year of the projection to a history year, or to long-run averages outside 1954–2025.
  - `PoolStrategy` gains a per-year return seam. A per-pass `CrashReplayer` sets each year's returns, tracks the growth index's running peak, and draws the tent in retirement years below the high.
  - Sizing still comes from the constant-return pass one, so the tent cannot "know" the crash. Each mix is sized by its own yields and replayed on its own pass.
- **The fixed-spending Monte Carlo evaluation reuses the optimizer's context and trial loop.**
  - The context is built once, from the scenario's own tent, so every column runs on the same market paths and the same seed.
  - Then one `TrialPassRunner` pass runs for no tent and one per mix. The spending schedule is the plan's own, held fixed.
  - Per-trial tent counters measure the years the tent covered.
- **One core service** (`BondTentAnalysisService`) serves two GET endpoints. The frontend tab follows the approved layout "B".

**Tech Stack:** Java 25 / Spring Boot 4.1 (Jackson 3), JUnit 5 + AssertJ + Mockito, Testcontainers; React 19 / TypeScript / Vitest / Recharts.

**Spec:** `docs/superpowers/specs/2026-10-18-bond-tent-design.md`. This plan covers:
- §4.4 "Historical replays" and "Fixed-spending Monte Carlo evaluation";
- §4.3 rules 2–6 in a replay;
- §5's performance, golden and disclaimer constraints;
- §6 and §6.2.

**Where this plan sits.** Plans 1–4 have landed:
- plan 1: trials start today;
- plan 2: bond maturity data;
- plan 3: settings, sizing and the deterministic engine;
- plan 4: the Monte Carlo tent, optimizer certification and the cash-reserve conversion.

Plans 3 and 4 are binding here, so read their "Builder's calls" first:
- `docs/superpowers/plans/2026-10-23-bond-tent-plan3-config-sizing-deterministic.md`
- `docs/superpowers/plans/2026-10-24-bond-tent-plan4-monte-carlo-optimizer-cash-reserve.md`

The classes this plan extends live in `backend/wealthview-projection/src/main/java/com/wealthview/projection/`:
- deterministic: `BondTentPlanner`, `TentPools`, `TentReservation`, `PoolStrategy`;
- Monte Carlo: `TrialTent`, `TrialTentPlan`, `TrialTentSizer`, `TrialConfigFactory`, `TrialPassRunner`.

### Builder's calls this plan makes

1. **History window.**
   - **Calendar mapping:** a replay maps calendar year `Y` to history year `h = crashFirstYear + (Y − crashCalendarYear)`.
   - **Historical years:** `h` is historical only when both the asset-class returns and the bond maturity returns have a row for it. Today that is 1954–2025. Asset rows before 1972 come from `matrix(true)`; the replay ignores the scenario's `include_depression_years` flag, because history is history.
   - **Every other year** uses long-run averages: `CapitalMarketAssumptionsProvider.geometricMeans(false)` and `BondTentAssumptions.realReturns()`. Those are the deterministic engine's own constants, so a replay outside the history matches the main projection.
   - **The "any start year" picker** offers exactly 1954–2025.
2. **Named crashes.** Each one has a first year, a last year and a label:
   - `oil_shock_1973`: 1973–1974, "1973–74";
   - `dot_com_2000`: 2000–2002, "2000–02";
   - `financial_crisis_2008`: 2008–2009, "2007–09";
   - `inflation_2022`: 2022–2022, "2022".

   The spec places 2007–09's first year at 2008. A user-chosen start year is a one-year window.
3. **Alignment anchor.**
   - The crash's first year lands at `anchor + offset`, where `anchor = max(retirementYear, currentYear)` and `offset` is −3 to +3.
   - A scenario that is already retired is anchored at today. Plan 3 already treats today as its retirement for coverage.
   - An offset is available only when `anchor + offset ≥ currentYear`, so a crash is never placed before today (spec rule 2).
4. **Per-year returns in a replay.**
   - Each pool's return for the year is `PoolStrategy.poolReturnsFor(accounts, thatYear'sAssetReturns, inflation, fee)`: the same allocation blend, fee and empty-pool fallback as `create`, fed with one year's returns instead of the geometric means.
   - Each sleeve earns its maturity's return for the year, minus the fee. Ultrashort is the year's cash return.
   - **An account with a fixed expected-return override keeps that return in a replay.** It has no allocation to replay. This is documented in the tab's copy and the user guide.
   - `getWeightedReturn()`, which withdrawal-rate strategies read, stays the expected return. The tab requires a spending plan anyway.
5. **The trigger in a replay** mirrors plan 4's Builder's call 2, in `BigDecimal`:
   - The growth index starts at 1 in the first projected year. Each year it is multiplied by `1 + g`, where `g = Σ_p (V_p − T_p) r_p / Σ_p (V_p − T_p)` is computed after the year's top-up and before growth. `g = 0` when no growth part is left.
   - "Below the previous high" means the index is below its running peak.
   - **The draw test runs after the year's growth.** The build-up top-up at the start of a year reads the index as last year ended.
   - **Build-up below the high** (spec rule 2): the top-up adds at most `max(0, contributions)` of that year. When the build-up span is 0, the line ignores the gate, so the tent is funded in full at retirement.
6. **What a retired year below the high draws.**
   - The net outflow is `max(0, totalAfterGrowth − totalAtYearEnd)`.
   - Part of that outflow may already have come out of the tent, when a debit went past a pool's growth part (plan 3, Builder's call 7). That part is `tentAfterGrowth − tentNow`. The tent spends only the rest: `max(0, outflow − alreadyDrawn)`, shortest maturity first, and within a maturity from pools in placement order.
   - Rows record:
     - `tentDrawn = tentAfterGrowth − tentAtYearEnd`;
     - `stocksSoldWhileDown = max(0, outflow − tentDrawn)` in a retired year below the high, and 0 otherwise.
   - Pre-retirement years never draw the tent.
7. **Replay passes.**
   - Pass one is tent-free, on constant returns, and sizes every mix (`BondTentPlanner.size`).
   - Then one replay pass runs with no tent, followed by one per mix, in config order. Each mix uses its own target, its own shares and the config's build-up line.
   - The no-tent pass also tracks the growth index, so its "stocks sold while down" is comparable.
   - `runDetailed` is untouched: the main projection never runs a `CrashReplayer`.
8. **Metric definitions** (spec §6.2, shared by both engines through `TentCoverage`):
   - **Window and planned withdrawals.** The covered window is the first `K = min(ceil(coverage), retiredYears)` retired years. The planned withdrawals are the full-year withdrawals the sizer covered: `BondTentSizing.coveredYears()` in the deterministic engine, `TrialTentSizer.withdrawals` in Monte Carlo.
   - **Years covered** `= Σ_{k<K} drawn_k / planned_k + left / mean(planned)`, where `left` is the tent at the end of window year `K − 1`, or at the last year reached if the horizon ends first. A planned withdrawal of 0 contributes 0.
   - **Tent left over** is the tent at the end of the year the primary turns 70, or at the end of the window when that comes later. It is `null` when the horizon ends first.
   - **Balance at 90** is the end balance of the year the primary turns 89, which is the balance entering 90. It is `null` when the horizon ends first. Monte Carlo reads trial year index `89 − retirementAge`.
   - **Lowest balance** is the lowest end balance over the retired years, or over all years when none is retired.
   - **Typical / worst 10%** are the 50th and 10th percentiles across trials.
9. **The fixed-spending simulation.**
   - **The schedule.** Year `y`'s spending is `plan.resolveYear(retirementYear + y, retirementAge + y, y + 1, inflation, 0).totalSpending()`.
     - A tier plan is scaled by the survivor factor from the first-death index, as the deterministic engine does.
     - A guardrail plan is already survivor-scaled, so it is not scaled again.
   - **Success** is paying the planned spending in full every year. To get that, the whole schedule is passed as `floors` and `discretionary` is all zeros.
     - This differs on purpose from the optimizer's essential-floor metric. The plan is held fixed, so all of it is the requirement.
     - The tab's copy says so.
   - **Roth conversions.** A guardrail plan's stored conversion schedule is mapped by calendar year to `conversionByYear`, with `conversionTaxByYear` all 0. The ordinary tables price conversions whenever tax is modeled.
     - A tier plan runs without conversions: Monte Carlo has never modeled the deterministic Roth settings.
     - This known gap is documented.
   - **One context, shared paths.**
     - The context is built once, with the scenario's own tent. That forces `matrix(false)` and records `TentPaths`.
     - Every column then runs on the same paths and the same seed. The no-tent column passes `.tent(null, null)`. Mix `i` passes `TrialTentPlan.withMix(mix_i, yields, inflation)`.
   - **`essentialFloor`** on the input is set to year 0's planned spending, because the context's expected-path tax estimate reads it.
   - **The evaluator's `floors` are its own schedule.** It does not use the context's `adjustedFloors`.
   - **Trial count:** 5000, the optimizer default (`GuardrailProfileService.DEFAULT_TRIAL_COUNT`). It must pass the performance gate in Task 5.
10. **API.** Both endpoints are GETs with no side effects:
    - `GET /api/v1/projections/{id}/bond-tent/simulation`
    - `GET /api/v1/projections/{id}/bond-tent/replay?crash=<key>&offset=<n>` or `?start_year=<y>&offset=<n>`

    They return core records directly, as the sizing endpoint does. Both need a saved scenario with an active spending plan and a `bond_tent`.
11. **The tab.**
    - **When it shows:** whenever the scenario has an active spending plan, as with the scenario form's tent section. A plan without a tent shows an empty state that points to the form.
    - **Loading:** the simulation loads once when the tab opens. The replay reloads on every crash or timing change.
    - **Default timing:** `0`, or the first available offset when `0` would place the crash before today.

## Global Constraints

**Engine and data rules**
- Real terms throughout. `BigDecimal` in the deterministic engine and its DTOs; `double` in Monte Carlo and in the pure `TentCoverage` helper, converted at the boundary with scale 2 for money, 2 for years and 4 for rates.
- **Nothing outside a replay or the tab changes.**
  - Leave these unedited, and keep them passing:
    - the six deterministic goldens and `bond-tent-early-retiree`;
    - `DeterministicProjectionEngineCharacterizationTest`, `EngineInvariantsTest` and `PoolStrategyCharacterizationTest`;
    - every Monte Carlo pin. These include `MonteCarloSpendingOptimizerCharacterizationTest`, `MonteCarloBondTentCharacterizationTest`, `StochasticMortalityGoldenTest`, `GuardrailHouseholdSimulatedScheduleTest` and `PortfolioPathGeneratorTest`.
  - Every new seam is inert when there is no `CrashReplayer` or no tent coverage tracking.
- **Exact error messages** (`IllegalArgumentException` → 400 envelope):
  - `A bond tent analysis needs a spending plan and a bond tent`
  - `Choose a crash or a start year`
  - `Unknown crash: <key>`
  - `Start year must be between <first> and <last>`
  - `Crash timing must be between 3 years before and 3 years after retirement`
  - `That timing would place the crash before today`
- **History-ends rule:** a replay year outside the history carries `history_year: null`. A replay never throws for a lack of history.
- **JSON** is snake_case. New response keys are listed in each task's Interfaces.

**Code conventions**
- Records for DTOs and value types.
- `@Nullable` from `org.springframework.lang`.
- No wildcard imports, AssertJ only.
- Test names follow `method_state_expected`.
- 120-character lines.
- A PMD suppression needs an adjacent justifying comment. Never suppress CPD. If CPD flags matrix resolution shared with `MonteCarloSpendingOptimizer`, extract a package-private helper both use.
- `DeterministicProjectionEngine.processYear` and `TrialSimulator.simulateTrial` gain no PMD suppression; put new logic in static, null-inert helpers, as `BondTentPlanner` does.

**Build gates**
- Run per module with `-am`: `cd backend && mvn -q -T1 -pl <module> -am verify -DskipITs`. A full-reactor `mvn verify` gets OOM-killed.
- App integration tests: `cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true`.
- Frontend: `npm run test:coverage`, `npm run typecheck` and `npm run lint` in `frontend/`, plus `npm run typecheck:all` from the repo root.
- Coverage floors are never lowered.

**Commits**
- Commit on `main`. Never push.
- Conventional Commits, with a body for feat/fix/refactor/db.
- The `Co-Authored-By:` trailer names the model that wrote the commit.

## Review Focus

1. **A scenario that is already retired, or a timing that would put the crash before today.** Only offsets on or after today are offered, and a request for an earlier one is a 400, not a replay that starts mid-crash. Pinned by:
   - Task 6's `replay_retiredAlready_offersOnlyTodayOnward` and `replay_offsetBeforeToday_throws`;
   - Task 7's `disables timings before today`.
2. **A replay that runs past 2025 or begins before 1954**, for example a 1973 crash placed at retirement for someone retiring in 2027, which reaches back to 1969. Those years use long-run averages, carry `history_year: null`, and the chart marks where history ends. Pinned by:
   - Task 1's `build_yearsOutsideHistory_useLongRunAverages`;
   - Task 7's `marks where history ends`.
3. **A scenario whose spending plan is "None", or with a plan but no tent.** The endpoints answer 400 with the exact message. The tab is hidden without a plan, and shows the set-up prompt without a tent. Pinned by:
   - Task 6's `simulate_noTent_throws` and `replay_noSpendingPlan_throws`;
   - Task 7's `hides the Bond tent tab without a spending plan` and `prompts to set up a tent when none is configured`.
4. **An account with a fixed expected-return override.** In a replay it keeps its fixed return rather than failing or silently replaying as 100% US stocks. Pinned by Task 3's `replayBondTent_overrideAccount_keepsItsFixedReturn`.
5. **A horizon that ends before 70 or 90**, for example an end age of 72 or a household truncated at the second death. The checkpoint and balance-at-90 figures are `null` and render "—"; nothing throws or reads past the last year. Pinned by:
   - Task 3's `replayBondTent_horizonEndsBefore90_hasNoBalanceAt90`;
   - Task 5's `simulate_horizonEndsBefore90_hasNoBalanceAt90`;
   - Task 7's `shows a dash for figures the horizon never reaches`.

---

### Task 1: Historical return paths for replays

**Files:**
- Create in `backend/wealthview-core/src/main/java/com/wealthview/core/projection/`:
  - `CrashReplayPaths.java`
  - `dto/YearReturns.java`, `dto/HistoricalReturnPath.java`, `dto/HistoricalCrash.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/CrashReplayPathsTest.java` and `dto/HistoricalCrashTest.java`.

**Interfaces:**
- Consumes:
  - `CapitalMarketAssumptionsProvider.matrix(true)` and `geometricMeans(false)`;
  - `BondMaturityReturnsProvider.alignedTo`, `firstYear()` and `lastYear()`;
  - `BondTentAssumptions.realReturns()`.
- Produces:
  - `public record YearReturns(@Nullable Integer historyYear, Map<AssetClass, Double> assets, Map<BondMaturity, Double> maturities)`. `historyYear` is null for a long-run-average year.
  - `public record HistoricalReturnPath(Map<Integer, YearReturns> byCalendarYear, YearReturns longRun)`:
    - `public YearReturns forYear(int calendarYear)` returns the stored year or `longRun`.
  - `public enum HistoricalCrash`, with constants `OIL_SHOCK_1973`, `DOT_COM_2000`, `FINANCIAL_CRISIS_2008` and `INFLATION_2022` (values per Builder's call 2):
    - `String key()`, `int firstYear()`, `int lastYear()`, `String label()`;
    - `static Optional<HistoricalCrash> fromKey(String key)`.
  - `@Component public class CrashReplayPaths(CapitalMarketAssumptionsProvider, BondMaturityReturnsProvider, BondTentAssumptions)`:
    - `public int firstYear()` and `public int lastYear()`: the full-data range, the overlap of the `matrix(true)` years and the maturity series.
    - `public HistoricalReturnPath build(int crashFirstYear, int crashCalendarYear, int fromCalendarYear, int toCalendarYear)`:
      - one entry per calendar year in `[from, to]` whose history year is in range;
      - `longRun` from the geometric means and `realReturns()`.
    - It slices `matrix(true)` to `[firstYear, lastYear]` before `alignedTo`, because the full matrix has no maturity rows before 1954. The slice is cached after the first build.

- [ ] **Step 1: Write the failing tests**

`CrashReplayPathsTest` mocks the three providers:
- `matrix(true)`: years 1950–1956, classes in `AssetClass.values()` order, `us_stock` = `(year − 1950) / 100`, cash 0.001 × (year − 1950);
- maturity `firstYear` 1954, `lastYear` 1956;
- `alignedTo` answers only for a matrix whose years are exactly 1954–1956; any other matrix throws, as the real provider does;
- `geometricMeans(false)`: `us_stock` 0.05;
- `realReturns()`: every maturity 0.01.

```java
@Test void firstAndLastYear_areTheOverlapOfBothSeries()            // 1954 and 1956
@Test void build_mapsCalendarYearsOntoHistory()
    // build(1955, 2030, 2029, 2031): 2029 -> history 1954 (us_stock .04), 2030 -> 1955, 2031 -> 1956
@Test void build_yearsOutsideHistory_useLongRunAverages()
    // build(1955, 2030, 2026, 2034): 2026..2028 and 2032..2034 -> forYear(y).historyYear() null,
    // us_stock .05 and every maturity .01; no exception
@Test void build_ultrashortIsTheYearsCashReturn()                   // forYear(2030).maturities() ULTRASHORT == 0.005
@Test void build_neverAlignsTheUnslicedMatrix()                     // verify alignedTo is called only with years 1954..1956
// HistoricalCrashTest
@Test void fromKey_financialCrisis_startsIn2008AndIsLabelled2007to09()
@Test void fromKey_unknown_isEmpty()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test-compile`
Expected: compilation FAILS, because the types do not exist.

- [ ] **Step 3: Implement the three records, the enum and `CrashReplayPaths`**

- [ ] **Step 4: Run the gate**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am verify -DskipITs`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

Subject: `feat(core): map a crash replay's calendar years onto historical returns`. The body covers the 1954–2025 window, the long-run fallback and why the depression-years flag does not apply.

---

### Task 2: Per-year returns and drawing the tent in the deterministic pools

**Files:**
- Modify in `backend/wealthview-projection/src/main/java/com/wealthview/projection/`: `PoolStrategy.java` (the interface, `create` and `MultiPool` wiring only), `TentPools.java`, `TentReservation.java`.
- Test: `TentReservationTest.java`, `TentPoolsTest.java` and `PoolStrategyTentTest.java`. Extend them if they exist, or create them beside the existing pool tests. All are in projection.

**Interfaces:**
- Consumes: plan 3's `TentReservation`, `TentPools`, `PoolStrategy.TentSetup`.
- Produces:
  - `record PoolStrategy.PoolReturns(BigDecimal taxable, BigDecimal traditional, BigDecimal roth, BigDecimal household)`.
  - `static PoolReturns PoolStrategy.poolReturnsFor(List<ProjectionAccountInput> accounts, Map<AssetClass, Double> assetReturns, BigDecimal inflationRate, BigDecimal feeRate)`. It uses the existing `computeWeightedReturn` and `poolWeightedReturn`. `create` is rewritten to call it, so nothing it builds changes.
  - New `PoolStrategy` methods:
    - `void useYearReturns(PoolReturns returns, Map<BondMaturity, BigDecimal> sleeveReturns)`. Later growth uses these pool returns, and the tent's sleeves use `sleeveReturns`, which are already net of fee. `getWeightedReturn()` is unchanged.
    - `BigDecimal growthPartReturn()` follows Builder's call 5's `g`, from current balances, reservations and pool returns, at scale `SCALE + 4`. It is 0 when no growth part is left.
    - `BigDecimal spendTent(BigDecimal amount)` spends `min(amount, tentTotal())`, shortest maturity first and within a maturity in placement order, and returns what it spent. It is a no-op returning 0 without a tent.
    - `void topUpTent(BigDecimal target, @Nullable BigDecimal cap)` reserves at most `cap` more, with `null` meaning uncapped. The existing `topUpTent(BigDecimal)` delegates with `null`.
  - `TentReservation.spend(BigDecimal amount, List<PoolType> placementOrder)` returns the amount spent.
  - `TentPools`:
    - `useSleeveReturns(Map<BondMaturity, BigDecimal>)` replaces the setup's `realReturns` and does nothing without a setup.
    - `growthPartReturn(BigDecimal taxableReturn, BigDecimal traditionalReturn, BigDecimal rothReturn)`
    - `spend(BigDecimal)`
    - `topUp(BigDecimal target, @Nullable BigDecimal cap)`
  - `MultiPool`'s three pool-return fields become non-final; only `useYearReturns` reassigns them.

- [ ] **Step 1: Write the failing tests**

```java
// TentReservationTest — placement taxable, traditional, roth
@Test void spend_shortestMaturityFirst()                  // taxable 20k/30k/50k/0, spend 35k -> 0/15k/50k/0, returns 35000
@Test void spend_withinAMaturity_followsPlacementOrder()  // taxable ultrashort 10k, traditional ultrashort 10k, spend 15k -> 0 and 5k
@Test void spend_moreThanTheTent_spendsEverything()       // tent 100k, spend 150k -> returns 100000, total 0
// TentPoolsTest
@Test void topUp_capped_reservesAtMostTheCap()            // empty tent, target 90k, cap 10k -> total 10000
@Test void topUp_nullCap_reachesTheTarget()
@Test void growthPartReturn_weightsUnreservedBalances()   // taxable 300k (100k reserved) at .10, traditional 200k at 0 -> 0.05
@Test void growthPartReturn_noGrowthPartLeft_isZero()     // everything reserved
// PoolStrategyTentTest — PoolFixtures, one ALL_US taxable account 1,000,000, CMA us_stock .05, fee 0, yields 0
@Test void poolReturnsFor_sameInputs_matchesCreate()      // household() == create(...).getWeightedReturn()
@Test void useYearReturns_growsPoolsAtTheYearsReturns()   // poolReturnsFor({us_stock: -0.30}), applyGrowth(false) -> total 700000.00
@Test void useYearReturns_sleevesEarnTheYearsMaturityReturns()
    // tent 200k all LONG, year us_stock -0.30, LONG +0.20 -> 800k x 0.7 + 200k x 1.2 = 800000.00
@Test void useYearReturns_leavesWeightedReturnAsExpected()   // getWeightedReturn() unchanged after the call
@Test void spendTent_noTent_returnsZero()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

Keep the tent arithmetic in `TentReservation` and `TentPools`. `PoolStrategy` gets only the record, the static factory, the interface methods and one-line `MultiPool` delegations.

- [ ] **Step 4: Run the gate**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS. The goldens, `PoolStrategyCharacterizationTest` and `EngineInvariantsTest` pass unedited.

- [ ] **Step 5: Commit**

Subject: `feat(projection): let the pools grow at one year's returns and spend the tent on demand`

---

### Task 3: Deterministic crash replays and the 2007–09 golden

**Files:**
- Create in core (`backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/`): `BondTentReplayYear.java`, `BondTentReplayOutcome.java`.
- Create in projection (`.../wealthview/projection/`): `CrashReplayer.java`, `TentCoverage.java`, `ReplayOutcomes.java`.
- Modify:
  - `.../core/projection/ProjectionEngine.java`
  - `.../projection/DeterministicProjectionEngine.java`
  - `.../projection/BondTentPlanner.java`
- Test:
  - `DeterministicProjectionEngineReplayTest.java`, `TentCoverageTest.java` and `BondTentReplayGoldenFileTest.java` (new), in projection;
  - `testutil/SeedReturns.java` (new) and `testutil/GoldenScenarios.java` (extend);
  - golden fixtures in `backend/wealthview-projection/src/test/resources/golden/`: `bond-tent-gfc-replay-input.json` and `bond-tent-gfc-replay.json`.

**Interfaces:**
- Consumes: Task 1's `HistoricalReturnPath` and `YearReturns`; Task 2's pool methods; plan 3's `BondTentPlanner.size`, `BuildUp` and `TentRun`.
- Produces:
  - `public record BondTentReplayYear(int year, int age, BigDecimal balance, BigDecimal tentBalance, BigDecimal tentDrawn, BigDecimal stocksSoldWhileDown, boolean stocksBelowHigh, @Nullable Integer historyYear)`
  - `public record BondTentReplayOutcome(String name, @Nullable BigDecimal tentTarget, BigDecimal lowestBalance, BigDecimal stocksSoldWhileDown, @Nullable BigDecimal yearsCovered, @Nullable BigDecimal tentLeftAtCheckpoint, @Nullable BigDecimal balanceAt90, List<BondTentReplayYear> years)`. The no-tent outcome is named `No tent` and has null tent fields.
  - `ProjectionEngine`:
    - `default List<BondTentReplayOutcome> replayBondTent(ProjectionInput input, HistoricalReturnPath path)` throws `UnsupportedOperationException`.
    - The deterministic engine overrides it, with `@Observed(name = "wealthview.projection.bond_tent_replay", contextualName = "bond-tent-replay", lowCardinalityKeyValues = {"component", "projection"})`.
    - It throws `IllegalArgumentException("A bond tent analysis needs a spending plan and a bond tent")` when `BondTentPlanner.activeConfig` is empty.
    - It throws the existing `IllegalStateException` when `bondTentAssumptions` is null.
    - It returns the no-tent outcome first, then one outcome per mix, in config order (Builder's call 7).
  - `final class TentCoverage`, package-private and pure `double`:
    - `static double drawnShare(double drawn, double planned)` returns 0 when `planned <= 0`.
    - `static double unusedYears(double left, double[] planned)` returns 0 when the mean is 0.
  - `final class CrashReplayer`, package-private, one per replay pass, `BigDecimal`:
    - `CrashReplayer(HistoricalReturnPath path, List<ProjectionAccountInput> accounts, BigDecimal inflationRate, BigDecimal feeRate)`
    - `boolean isBelowPeak()`
    - These static helpers are each a no-op when the replayer is null:
      - `beforeGrowth(@Nullable CrashReplayer r, PoolStrategy pool, int year)` calls `useYearReturns` for the year and remembers `growthPartReturn()`;
      - `afterGrowth(@Nullable CrashReplayer r, PoolStrategy pool)` advances the index and remembers the pool total and the tent total;
      - `settle(@Nullable CrashReplayer r, PoolStrategy pool, int year, int age, boolean retired)` follows Builder's call 6 and appends a row.
    - `List<BondTentReplayYear> years()`
  - `BondTentPlanner`:
    - `topUp(PoolStrategy pool, @Nullable TentRun tent, int year, @Nullable CrashReplayer replay, BigDecimal contributions)` caps per Builder's call 5. With `replay == null` it behaves exactly as today. The old signature is removed and its caller passes `null`.
    - `TentRun runFor(Sized sized, BondTentConfig config, int mixIndex, BuildUp buildUp, Map<BondMaturity, Double> realReturns, BigDecimal feeRate, WithdrawalOrder order)` builds mix `i`'s shares with `sized.sizing().mixes().get(i).target()`. `twoPass` uses it with index 0.
  - `ReplayOutcomes.of(String name, @Nullable BigDecimal target, List<BondTentReplayYear> rows, @Nullable BondTentSizing sizing, int birthYear)` computes the metrics in Builder's call 8. The checkpoint age is `TENT_CHECKPOINT_AGE = 70`.
  - `ProjectionRunContext` gains `@Nullable CrashReplayer replay`. `processYear` calls these hooks in this order: contributions, `topUp`, `beforeGrowth`, `applyGrowth`, `afterGrowth`, the existing body, `floorAtZero`, `settle`.

- [ ] **Step 1: Write the failing tests**

`DeterministicProjectionEngineReplayTest` fixture:
- `new DeterministicProjectionEngine(null, null, null, null, null, null, null, assumptions)`, with `assumptions` mocked to return 0 for every maturity;
- `referenceYear` 2026, retirement 2026-01-01, birth year 1961, end age 72;
- one taxable account of 1,000,000, `ALL_US`, no override;
- tier spending: essential 40,000, discretionary 0;
- params: `fee_rate` 0, `dividend_yield` 0, `interest_yield` 0;
- a tent with coverage 2, build-up 0, one mix "Cash" that is 100% ultrashort, and an ultrashort yield of 0.025 at inflation 0.025, so the target is 80,000;
- the path: 2026 `us_stock` −0.30, 2027 +0.10; `longRun` `us_stock` 0.05; cash and every maturity 0 throughout;
- no tax calculators, so no year pays tax.

```java
@Test void replayBondTent_crashAtRetirement_tentPaysTheFirstTwoYears()
    // "No tent" balances 660000.00, 686000.00, 680300.00; stocksSoldWhileDown 40000.00 in each of those years
    // "Cash": tentTarget 80000.00; balances 684000.00, 708400.00, 703820.00; tentDrawn 40000.00, 40000.00, 0.00;
    // stocksSoldWhileDown 0.00, 0.00, 40000.00; yearsCovered 2.00
@Test void replayBondTent_outcomes_noTentThenEachMixInOrder()      // three mixes -> names "No tent", mix 1..3
@Test void replayBondTent_eachMix_isSizedByItsOwnYields()          // each outcome's tentTarget == sizeBondTent(...)'s target for that mix
@Test void replayBondTent_recoveryAboveTheHigh_neverDrawsTheTent() // path +.10 every year -> tentDrawn 0 everywhere
@Test void replayBondTent_crashDuringBuildUp_topsUpOnlyFromContributions()
    // retirement 2029, build-up 3, contribution 10k, same path: the line is 0 in 2026, then asks for 26,666.67 and
    // 53,333.33, but the index stays below its high -> 2027 row tentBalance 10000.00, 2028 row 20000.00
@Test void replayBondTent_noRefill_spentTentStaysSpent()           // after the tent empties, tentBalance stays 0 in up years
@Test void replayBondTent_horizonEndsBefore90_hasNoBalanceAt90()   // end age 72 -> balanceAt90 null; tentLeftAtCheckpoint at 2031 == 0.00
@Test void replayBondTent_overrideAccount_keepsItsFixedReturn()    // override 0.05, inflation 0 in this test: every year grows 5%
@Test void replayBondTent_historyYear_isCarriedOntoEachRow()       // 2026 -> path's historyYear; longRun years -> null
@Test void replayBondTent_noTent_throws()                          // message per Global Constraints
@Test void runDetailed_isUnchangedByTheReplaySeam()                // fixture's runDetailed equals a run on a fresh engine instance
// TentCoverageTest
@Test void drawnShare_zeroPlanned_isZero()
@Test void unusedYears_isLeftOverMeanPlanned()                     // left 60k, planned [30k, 30k] -> 2.0
```

`BondTentReplayGoldenFileTest`:
- **Harness:** the same as `BondTentGoldenFileTest`.
- **Input:** the early-retiree input, with each account given `"allocation": {"us_stock": 0.6, "bond": 0.4}` and no `expectedReturn`. Extend `GoldenScenarios.parseAccounts` to accept that key; files without it parse exactly as before.
- **Path:** built by the real `CrashReplayPaths` over repositories that `testutil/SeedReturns` stubs from the rows of `db/migration/R__seed_asset_class_returns.sql` and `R__seed_bond_maturity_returns.sql`, read from the classpath. The crash is `FINANCIAL_CRISIS_2008` at offset 0, which maps 2029 to 2008.

```java
@Test void bondTentGfcReplay_matchesGolden()   // pins the List<BondTentReplayOutcome> JSON
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

- First the DTOs and the `ProjectionEngine` default.
- Then `TentCoverage`, `CrashReplayer`, `ReplayOutcomes` and the `BondTentPlanner` changes.
- Last, the engine:
  - `replayBondTent` runs pass one with the existing `runPass`.
  - The replay passes go through a `runPass` overload that takes the replayer.
- Generate the golden once with `-Dupdate.golden=true` and inspect it by hand before committing. Check that:
  - "No tent" sells stocks while down in 2029–2030;
  - the mix draws its tent in those years and never refills it;
  - rows after 2046 carry `history_year: null`.

- [ ] **Step 4: Run the gate**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS, with every existing golden and pin unedited.

- [ ] **Step 5: Commit**

Subject: `feat(projection): replay a scenario through a historical crash with and without its bond tent`. The body covers:
- sizing on constant returns;
- the per-year return seam;
- the trigger and the draw;
- the override-account rule;
- the new golden.

---

### Task 4: Per-trial tent coverage in Monte Carlo

**Files:**
- Modify in `backend/wealthview-projection/src/main/java/com/wealthview/projection/`:
  - `TrialTent.java`
  - `TrialSimulator.java` (`TrialResult`, `settleTent`)
  - `TrialTentSizer.java`
  - `TrialConfigFactory.java`
  - `TrialPassRunner.java`
- Test: `TrialTentTest.java`, `TrialSimulatorTentTest.java`, `TrialConfigFactoryTest.java` and `TrialPassRunnerTest.java` (extend), in projection.

**Interfaces:**
- Consumes: Task 3's `TentCoverage`.
- Produces:
  - `TrialTent`:
    - `void trackCoverage(double[] plannedWithdrawals)` holds the shared array without copying it.
    - `void recordCoverageYear(int retiredYear, double totalBefore)` sets `drawn = totalBefore − total()`. For `retiredYear < planned.length`, it adds `TentCoverage.drawnShare(drawn, planned[retiredYear])` and sets `left = total()`.
    - `double yearsCovered()` returns `drawnSum + TentCoverage.unusedYears(left, planned)`, or `NaN` when coverage is not tracked.
    - No new allocation in the year loop.
  - `TrialSimulator.TrialResult` gains `double tentYearsCovered`, last. It is `NaN` without a tent; with a tent it is `config.tent().yearsCovered()`. A 5-argument back-compat constructor passes `NaN`.
  - `settleTent(tent, tp, expectedAfterGrowth, int y)` captures `total()` before spending and calls `recordCoverageYear(y, before)` after `clampAll`.
  - `TrialTentSizer.Sizing(double target, double[] withdrawals)`, with `static Sizing sizing(...)`. It takes the same parameters as `target`, which now delegates to it.
  - `TrialConfigFactory`:
    - `TrialTentSizer.Sizing tentSizingFor(double[] floors, double[] discretionary, double[] income, double[] surplusTax)` returns `Sizing(0, new double[0])` without a plan. `tentTargetFor` delegates to it.
    - `configFor(int t, adaptation, trackYearBalances, householdOverride, TrialTentSizer.Sizing tentSizing)` builds the tent as today, then calls `trackCoverage(tentSizing.withdrawals())` on it. The `double tentTarget` overload delegates with `new Sizing(target, new double[0])`.
  - `TrialPassRunner`:
    - `run` computes `tentSizingFor` once and passes it to every trial.
    - `PassResult` gains `double[] tentYearsCovered`, last, with a back-compat constructor.

- [ ] **Step 1: Write the failing tests**

Use plan 4's `TrialSimulatorTentTest` fixture: an 80,000 ultrashort tent, floor 40k, sleeve returns 0. Coverage tracking uses planned `[40000, 40000]`.

```java
// TrialSimulatorTentTest
@Test void simulateTrial_crashInFirstYear_tentCoversTwoYears()      // -30%, +10% x4 -> tentYearsCovered 2.0 within 1e-9
@Test void simulateTrial_risingMarket_unusedTentCountsAsCoverage()  // +5% x5 -> 80000 / 40000 = 2.0
@Test void simulateTrial_noTent_tentYearsCoveredIsNaN()
// TrialTentTest
@Test void yearsCovered_partialDrawThenWindowEnds()                 // draw 20k in year 0, 30k left after year 1 -> 0.5 + 0.75 = 1.25
@Test void yearsCovered_notTracked_isNaN()
// TrialConfigFactoryTest
@Test void tentSizingFor_matchesTentTargetFor()                     // same target; withdrawals length == ceil(coverage)
// TrialPassRunnerTest
@Test void run_tentPlan_collectsEachTrialsYearsCovered()           // PassResult.tentYearsCovered()[t] == the trial's result
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run the gate**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS, with every Monte Carlo pin unedited.

- [ ] **Step 5: Commit**

Subject: `feat(projection): count the years each Monte Carlo trial's tent covered`

---

### Task 5: The fixed-spending Monte Carlo simulation

**Files:**
- Create in core (`backend/wealthview-core/src/main/java/com/wealthview/core/projection/`): `BondTentSimulator.java`, `dto/BondTentSimulationColumn.java`, `dto/BondTentSimulation.java`.
- Create in projection (`.../wealthview/projection/`): `FixedSpendingSchedule.java` and `FixedSpendingTentSimulator.java`.
- Modify:
  - `.../core/projection/dto/GuardrailOptimizationInput.java`: add `withEssentialFloor(BigDecimal)`, beside `withAccounts`.
  - `.../projection/TrialTentPlan.java`
  - `.../projection/GuardrailResponseBuilder.java`
- Test: `FixedSpendingScheduleTest.java`, `FixedSpendingTentSimulatorTest.java` and `TrialTentPlanTest.java` (extend), in projection.

**Interfaces:**
- Consumes: Task 4's coverage counters and `PassResult.tentYearsCovered`; plan 4's `OptimizationContextBuilder.build(input, accounts, matrix, maturities)`, `PreRetirementAccumulation.seedAtRetirement` and `HouseholdMcResolver.scaleFromTransition`.
- Produces:
  - `public record BondTentSimulationColumn(String name, @Nullable BigDecimal tentTarget, BigDecimal successRate, @Nullable BigDecimal yearsCoveredMedian, @Nullable BigDecimal yearsCoveredWorst10, @Nullable BigDecimal balanceAt90Median)`
  - `public record BondTentSimulation(int trialCount, BigDecimal plannedYears, List<BondTentSimulationColumn> columns)`. The no-tent column comes first, named `No tent`.
  - `@FunctionalInterface public interface BondTentSimulator { BondTentSimulation simulate(GuardrailOptimizationInput input, ProjectionInput projectionInput); }`
  - `TrialTentPlan withMix(BondTentMix mix, BondMaturityValues yields, double inflationRate)`: the same plan with that mix's shares and real yield.
  - `GuardrailResponseBuilder.configFactoryBuilder(OptimizationSetup ctx, PoolSimSetup poolSetup, double[] conversionByYear, double[] conversionTaxByYear)` is package-private and returns the builder without calling `build()`. The private `buildConfigFactory` calls it.
  - `record FixedSpendingSchedule(double[] spending, @Nullable double[] conversionByYear)`, package-private:
    - `static FixedSpendingSchedule of(SpendingPlan plan, int retirementYear, int retirementAge, int years, BigDecimal inflationRate, @Nullable TrialSimulator.HouseholdSim household, double survivorFactor)`, per Builder's call 9.
  - `@Component public class FixedSpendingTentSimulator implements BondTentSimulator`:
    - **Constructors:**
      - `@Autowired (@Nullable FederalTaxCalculator, CapitalMarketAssumptionsProvider, @Nullable CapitalGainsTaxCalculator, BondMaturityReturnsProvider)`;
      - a test constructor `(@Nullable FederalTaxCalculator, RealReturnMatrix, MaturityReturnMatrix)`.
    - **Annotation:** `simulate` carries `@Observed(name = "wealthview.mc.bond_tent_simulation", contextualName = "monte-carlo-bond-tent-simulation", lowCardinalityKeyValues = {"component", "projection"})`.
    - **The plan** comes from `projectionInput.guardrailSpending()`, or else `ScenarioParamsParser.parseTierBasedPlan(projectionInput.spendingProfile())`.
    - **Errors:** it throws the Global Constraints "needs a spending plan and a bond tent" message when there is no plan or `input.bondTent() == null`.
    - **Flow, per Builder's call 9:**
      - matrix `false`, maturities aligned, `seedAtRetirement`;
      - `withEssentialFloor(spending[0])`, then `contextBuilder.build`;
      - one `TrialPassRunner.run` per column, each with its own factory from `configFactoryBuilder(...).tent(planOrNull, pathsOrNull).build()`, `trackYearBalances = true`, and `income` and `surplusTax` from the context;
      - the metrics per Builder's call 8.
    - **Rounding:** `successRate` at scale 4; years at scale 2; money at scale 2 HALF_UP.

- [ ] **Step 1: Write the failing tests**

```java
// FixedSpendingScheduleTest
@Test void of_tierPlan_resolvesEachRetirementYearByAge()
    // tiers 60k to age 74, 45k from 75; retirement age 65, 12 years -> 60k x10, 45k x2
@Test void of_tierPlan_scalesFromTheFirstDeath()           // household transition index 3, factor .75 -> years 3+ x .75
@Test void of_guardrailPlan_readsRecommendedByYearUnscaled()   // same household -> no scaling
@Test void of_guardrailPlan_mapsItsConversionScheduleByYear()  // {2031: 20000} with retirement 2030 -> conversionByYear[1] == 20000
@Test void of_tierPlan_hasNoConversions()                  // conversionByYear null
// TrialTentPlanTest
@Test void withMix_usesThatMixsSharesAndRealYield()
// FixedSpendingTentSimulatorTest — test constructor, tax calculator null, an all-zero RealReturnMatrix (years 1..4)
// and matching all-zero maturities; base year = retirement year; taxable 1,000,000; tier spending 30,000;
// retirement age 65, end age 90; fee, dividend and interest yields 0; tent coverage 2, build-up 0,
// mix "Cash" 100% ultrashort, ultrashort yield 0.025 at inflation 0.025
@Test void simulate_flatMarkets_tentIsHeldAndCoversItsPlannedYears()
    // "No tent": successRate 1.0000, balanceAt90Median 250000.00, years-covered fields null
    // "Cash": tentTarget 60000.00, successRate 1.0000, yearsCoveredMedian 2.00, yearsCoveredWorst10 2.00, balanceAt90Median 250000.00
@Test void simulate_spendingAboveThePortfolio_failsEveryTrial()   // spending 50,000 discretionary-only -> successRate 0.0000
@Test void simulate_twoIdenticalMixes_reportIdenticalColumns()    // TEST_CMA_MATRIX + TEST_MATURITY_MATRIX: columns 1 and 2 equal
@Test void simulate_columns_noTentThenEachMixInOrder()
@Test void simulate_sameSeed_isDeterministic()
@Test void simulate_horizonEndsBefore90_hasNoBalanceAt90()        // end age 85 -> balanceAt90Median null in every column
@Test void simulate_noTent_throws()
@Test void simulate_bondTentFixture_matchesPinnedValues()
    // plan 4's MonteCarloBondTentCharacterizationTest fixture with tier spending 40,000: pin each column's successRate,
    // yearsCoveredMedian and balanceAt90Median, recorded once; comment how each was produced
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run the gates and the performance check**

Run:
```
cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs
cd backend && mvn -q -T1 -pl wealthview-api -am verify -DskipITs
```
Expected: BUILD SUCCESS for both. Every Monte Carlo pin is unedited.

**Performance gate** (spec §5):
- On `MonteCarloBondTentCharacterizationTest`'s fixture at 5000 trials, time `simulate` with three mixes, and time `MonteCarloSpendingOptimizer.optimize` on the same fixture. Run each warm, three times, and report both medians.
- `simulate` must not be slower than `optimize`.
- If it is, stop and report the numbers. Do not change the trial count without the owner.

- [ ] **Step 5: Commit**

Subject: `feat(projection): simulate the current spending plan with no tent and with each mix`. The body covers:
- shared paths;
- the full-spending success definition;
- the conversion rule;
- the measured timings.

---

### Task 6: Service and endpoints

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/BondTentAnalysisService.java`, `dto/BondTentReplay.java`.
- Modify:
  - `.../core/projection/GuardrailProfileService.java`
  - `backend/wealthview-api/src/main/java/com/wealthview/api/controller/ProjectionController.java`
- Test:
  - `BondTentAnalysisServiceTest.java` and `GuardrailProfileServiceTest.java` (extend), in core;
  - `ProjectionControllerTest.java` (extend), in api;
  - `backend/wealthview-app/src/test/java/com/wealthview/app/it/BondTentAnalysisIT.java`. Follow `BondTentSizingIT`'s base class and helpers.

**Interfaces:**
- Consumes: Tasks 1, 3 and 5.
- Produces:
  - `public record BondTentReplay(String crashLabel, int crashFirstYear, int crashLastYear, int crashCalendarYear, int offset, int retirementYear, BigDecimal plannedYears, int checkpointAge, List<Integer> availableOffsets, int firstStartYear, int lastStartYear, List<BondTentReplayOutcome> outcomes)`:
    - `crashLabel` is the crash's label, or the start year as text;
    - `retirementYear` is the anchor (Builder's call 3);
    - `checkpointAge` is 70.
  - `GuardrailProfileService`:
    - `public GuardrailOptimizationInput evaluationInput(ProjectionScenarioEntity scenario, ProjectionInput projectionInput)` returns what `optimize` builds for `GuardrailOptimizationRequest.builder().build()`: same seed, accounts, tent and defaults, with trial count `DEFAULT_TRIAL_COUNT`.
    - Extract the defaults resolution in `optimize` into a private helper both methods use. `optimize`'s behavior is unchanged.
  - `@Service public class BondTentAnalysisService(ProjectionScenarioRepository, ProjectionInputBuilder, ProjectionEngine, CrashReplayPaths, BondTentSimulator, GuardrailProfileService)`:
    - `@Transactional(readOnly = true) public BondTentSimulation simulate(UUID tenantId, UUID scenarioId)`
    - `@Transactional(readOnly = true) public BondTentReplay replay(UUID tenantId, UUID scenarioId, @Nullable String crash, @Nullable Integer startYear, int offset)`
    - **Checks:**
      - the scenario is the tenant's (`EntityNotFoundException`);
      - it has an active plan (`getSpendingProfile() != null || getGuardrailProfile() != null`, as `ProjectionService.sizeBondTent` checks);
      - its params have a `bond_tent`;
      - then the replay checks, in the Global Constraints order.
    - **Years:**
      - `currentYear` is the input's `referenceYear()`, or this year;
      - `retirementYear` is the retirement date's year, or `currentYear + 30`, as the engine resolves it;
      - the path spans `currentYear` to `birthYear + endAge`.
  - `ProjectionController`:
    - `GET /{id}/bond-tent/simulation` returns 200 `BondTentSimulation`.
    - `GET /{id}/bond-tent/replay` takes `@RequestParam(required = false) String crash`, `@RequestParam(name = "start_year", required = false) Integer startYear` and `@RequestParam(defaultValue = "0") int offset`, and returns 200 `BondTentReplay`.
    - Both are tenant-scoped by the principal.

- [ ] **Step 1: Write the failing tests**

```java
// BondTentAnalysisServiceTest — mocks for every collaborator
@Test void replay_namedCrash_anchorsTheFirstYearAtRetirementPlusOffset()
    // retirement 2030, crash financial_crisis_2008, offset -2 -> paths.build(2008, 2028, currentYear, endYear)
@Test void replay_startYear_isAOneYearWindow()                 // start_year 1990 -> crashFirstYear == crashLastYear == 1990
@Test void replay_retiredAlready_offersOnlyTodayOnward()       // retirement 2020, current 2026 -> availableOffsets [0, 1, 2, 3]
@Test void replay_offsetBeforeToday_throws()
@Test void replay_neitherOrBoth_throws()
@Test void replay_unknownCrash_throws()
@Test void replay_startYearOutOfRange_throws()                 // 1953 -> "Start year must be between 1954 and 2025"
@Test void replay_offsetOutOfRange_throws()                    // 4
@Test void replay_noSpendingPlan_throws()
@Test void simulate_noTent_throws()
@Test void simulate_passesTheEvaluationInputToTheSimulator()
@Test void replay_otherTenantsScenario_throwsNotFound()
// GuardrailProfileServiceTest
@Test void evaluationInput_matchesWhatOptimizeBuildsForAnEmptyRequest()   // captured optimizer input: seed, accounts, tent, trial count
// ProjectionControllerTest
@Test void bondTentSimulation_returns200WithSnakeCaseColumns()  // $.columns[0].success_rate, $.planned_years
@Test void bondTentReplay_crash_returns200()                    // $.outcomes[0].years[0].stocks_sold_while_down, $.available_offsets
@Test void bondTentReplay_startYearParam_isPassedThrough()      // ?start_year=1990&offset=1
@Test void bondTentReplay_invalid_returns400()                  // service throws IllegalArgumentException
@Test void bondTentSimulation_unknownScenario_returns404()
// BondTentAnalysisIT (Testcontainers, real HTTP, seeded history)
@Test void replay_financialCrisis_noTentSellsStocksWhileDownAndTheTentDoesNot()
    // scenario with a tier profile, an ALL_US taxable account and a 5-year tent; offset 0:
    // outcome "No tent" stocks_sold_while_down > 0; the mix's is lower; the mix's years_covered > 0
@Test void simulate_scenarioWithTent_returnsNoTentAndEachMix()
@Test void replay_otherTenant_returns404()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-api -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run the gates**

Run:
```
cd backend && mvn -q -T1 -pl wealthview-api -am verify -DskipITs
cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true
```
Expected: BUILD SUCCESS for both.

- [ ] **Step 5: Commit**

Subject: `feat(api): serve bond tent crash replays and the fixed-spending simulation`

---

### Task 7: The Bond tent tab

**Files:**
- Create in `frontend/src/components/bondTent/`, each with a `.test.tsx`:
  - `BondTentTab.tsx`
  - `TentSimulationStrip.tsx`
  - `CrashReplayChart.tsx`
  - `CrashReplayTable.tsx`
- Modify:
  - `frontend/src/types/projection.ts`
  - `frontend/src/api/projections.ts`
  - `frontend/src/components/SegmentedControl.tsx` and its test
  - `frontend/src/pages/ProjectionDetailPage.tsx` and its test
  - `frontend/src/testutil/builders.ts`

**Interfaces:**
- Consumes: Task 6's endpoints and records, in snake_case.
- Produces:
  - **Types:**
    - `BondTentReplayYear`, `BondTentReplayOutcome`, `BondTentReplay`, `BondTentSimulationColumn`, `BondTentSimulation`;
    - `type CrashKey = 'oil_shock_1973' | 'dot_com_2000' | 'financial_crisis_2008' | 'inflation_2022'`;
    - `CRASHES: { key: CrashKey; label: string }[]`, in that order, labelled "1973–74", "2000–02", "2007–09" and "2022".
  - **API functions:**
    - `getBondTentSimulation(scenarioId: string): Promise<BondTentSimulation>`
    - `getBondTentReplay(scenarioId: string, choice: { crash: CrashKey } | { start_year: number }, offset: number): Promise<BondTentReplay>`
  - `SegmentedControlOption` gains `disabled?: boolean`. A disabled option renders `disabled` and never calls `onChange`.
  - `BondTentTab({ scenarioId, hasTent }: { scenarioId: string; hasTent: boolean })`.

**Behavior and copy (exact strings).**
- **Formatting.** `{money}` is the existing whole-currency formatter. `{years}` has one decimal place. `—` stands for a `null` figure.
- **Page.**
  - The "Bond tent" tab, key `bond_tent`, is added last. It shows when `scenario.spending_profile` is set or `scenario.guardrail_profile?.active`.
  - `hasTent` is whether the scenario's params carry `bond_tent`. Read it with plan 3's `parseBondTent` inside a `useMemo`. That helper throws on malformed JSON, so catch the error and treat the scenario as having no tent.
- **No tent:** "Set up a bond tent in this scenario to compare it with no tent here: edit the scenario and turn on Use a bond tent."
- **Intro**, above the strip:
  > The main projection shows what a tent costs: on average returns stocks never fall below their high, so the tent is built and held but never drawn. The replays below show what it buys in a real crash, and what it costs when markets recover quickly.
- **Strip.**
  - The heading is "Across {trial_count, with thousands separators} simulations (starting today)".
  - Columns: "No tent", then each mix name. A mix's header has a second line, "{money(tent_target)} tent".
  - Rows:
    - "Success rate", as a whole percent;
    - "Years covered, typical / worst 10%", as "{years} / {years}";
    - "Balance at 90, typical".
  - The footnote is "Success means every year's planned spending is paid in full."
- **Replay controls.**
  - The heading is "Historical crash replay".
  - A select, labelled "Crash", offers the four crashes, plus "Any start year". That last option reveals a number input labelled "Start year", bounded by `first_start_year` and `last_start_year`.
  - A `SegmentedControl`, labelled "Crash hits", has options "−3", "−2", "−1", "At retirement", "+1", "+2" and "+3". An offset missing from `available_offsets` is disabled.
- **Chart.**
  - A Recharts `LineChart` draws `balance` per outcome, one line each, with a legend of outcome names.
  - A `ReferenceLine` at `retirement_year` is labelled "Retire {year}".
  - A `ReferenceArea` runs from `crash_calendar_year` to `crash_calendar_year + crash_last_year − crash_first_year`, labelled with `crash_label`.
  - When some year after the crash has `history_year: null`, a `ReferenceLine` at the first such year is labelled "History ends".
- **Table.** One column per outcome. Rows:
  - "Lowest balance";
  - "Stocks sold while down";
  - "Years covered (planned {planned_years})";
  - "Tent left over at {checkpoint_age}";
  - "Balance at 90".
- **Disclaimer**, styled like `OptimizerResultsView`'s `tax-disclaimer`:
  > Replays use actual inflation-adjusted returns for 1954–2025 and long-run averages outside those years. Accounts with a fixed expected return keep it in every replay. Bond yields are the assumptions you entered, and taxes are estimates. This is a model, not a forecast.
- **Errors.** Use `LoadingState` and `ErrorState` per request. A replay error leaves the controls usable.

- [ ] **Step 1: Write the failing tests** (Vitest + React Testing Library; mock the api module)

`BondTentTab.test.tsx`:
- `prompts to set up a tent when none is configured`
- `loads the simulation once and the replay on each control change`
- `defaults the timing to the first available offset when at-retirement is unavailable`
- `disables timings before today`
- `asks for a start year when Any start year is chosen`: request `{ start_year }`.

`TentSimulationStrip.test.tsx`:
- `renders one column per choice with success, coverage and balance`
- `shows a dash for figures the horizon never reaches`

`CrashReplayChart.test.tsx`:
- `draws one line per outcome and marks retirement and the crash`
- `marks where history ends`

`CrashReplayTable.test.tsx`:
- `renders the five rows with the planned years and checkpoint age`
- `shows a dash for figures the horizon never reaches`

`SegmentedControl.test.tsx`:
- `a disabled option cannot be selected`

`ProjectionDetailPage.test.tsx`:
- `shows the Bond tent tab for a scenario with a spending plan`
- `hides the Bond tent tab without a spending plan`

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npm run test -- BondTentTab TentSimulationStrip CrashReplayChart CrashReplayTable SegmentedControl ProjectionDetailPage`
Expected: FAIL.

- [ ] **Step 3: Implement the types, the api functions, the control change, the four components and the page wiring**

- [ ] **Step 4: Run the gates**

Run: `cd frontend && npm run test:coverage && npm run typecheck && npm run lint`, then `npm run typecheck:all` from the repo root.
Expected: all pass, with the coverage floors held.

- [ ] **Step 5: Commit**

Subject: `feat(frontend): add the Bond tent tab with simulations and crash replays`

---

### Task 8: Docs

**Files:**
- `docs/user-guide/retirement-projections.md`: add a "Bond tent tab" section covering:
  - what the strip measures, with the full-spending success definition;
  - how a replay places a crash and why earlier timings can be disabled;
  - the five table rows and the §6.2 metric definitions in plain words;
  - long-run averages outside 1954–2025;
  - the fixed-return override rule;
  - the two known gaps: tier plans run the simulation without Roth conversions, and the Roth conversion optimizer ignores a tent held in an IRA.
- `docs/MCDetails.md`: add a "Fixed-spending evaluation" section covering:
  - the shared context and paths;
  - the schedule and success;
  - the years-covered counters;
  - the trial count and the measured timing from Task 5.
- `docs/reference/api-reference.md`: add the two endpoints, their parameters, the error messages and the response fields.
- `CHANGELOG.md` [Unreleased]:
  - **Added:** the Bond tent tab, crash replays and the fixed-spending simulation.
  - **Known gaps:** the two from the user-guide section.

- [ ] **Step 1: Write the docs**

- [ ] **Step 2: Check every number and string against the code**

Check:
- the endpoint paths;
- the crash keys and labels;
- the 1954–2025 window;
- the checkpoint age of 70;
- the trial count;
- the copy in Task 7.

- [ ] **Step 3: Commit**

Subject: `docs: document the bond tent tab, crash replays and the fixed-spending simulation`
