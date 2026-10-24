# Bond Tent Plan 4 — Monte Carlo Tent, Optimizer Certification and Retiring the Cash Reserve — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Monte Carlo trials and the spending optimizer model a configured bond tent. A certified guardrail profile records the tent size it was certified with. The optimizer's stand-alone cash reserve is retired, and every existing reserve becomes an equivalent 100%-ultrashort tent.

**Architecture:**
- **Retire the cash reserve first** (Task 1). It is a scalar outside the pools in `TrialSimulator`. Removing it shrinks the hot loop before the tent goes in, and moves no pinned number, because every pinned fixture runs with a zero reserve.
- **The Monte Carlo tent mirrors plan 3's deterministic model, in `double`.**
  - `TrialTent` is the per-trial household reservation inside the pools, the double-land twin of `TentReservation`.
  - Its sleeves earn their maturity's bootstrapped return. The maturity rows come from each trial's existing block-bootstrap index sequence, so the tent adds no `rng` draw.
  - **Trigger:** a running peak on the growth portfolio's real-return index decides when the tent is drawn.
- **Build-up runs per candidate.** The tent target depends on the candidate spending the search is testing (spec §4.3 rule 1), so it can't be baked into the shared paths.
  - `PortfolioPathGenerator` records each trial's pool totals before and after growth in every pre-retirement year.
  - `TentBuildUp` replays the build-up line over those totals for each candidate target. The result is the trial's tent at retirement, plus the change to its start balances.
- **Sizing per pass.** `TrialPassRunner` sizes the tent once per pass from that pass's schedule (`TrialTentSizer`).
- **Optimizer, persistence and conversion.**
  - The optimizer threads the tent through every pass, and reports the certified target.
  - The service passes the scenario's tent in, hashes it into the scenario signature, and persists the target (V084).
  - V085 converts every reserve into a tent and marks its profile stale.

**Tech Stack:** Java 25 / Spring Boot 4.1 (Jackson 3), JUnit 5 + AssertJ + Mockito, Testcontainers, Flyway; React 19 / TypeScript / Vitest.

**Spec:** `docs/superpowers/specs/2026-10-18-bond-tent-design.md`. This plan covers:
- §4.3 rules 1–8 in the Monte Carlo engine;
- §4.4 "Monte Carlo" and "Optimizer";
- the "Not tent-aware in v1" gap;
- §6.1's first two notices;
- §8's cash-reserve decision.

**Where this plan sits.** Plans 1–3 have landed: trials start today, the bond maturity data, and settings plus sizing plus the deterministic engine. Plan 5 adds crash replays, the fixed-spending Monte Carlo evaluation and the Bond tent results tab.

Plan 3's design and rulings are binding here, so read its "Builder's calls" first: `docs/superpowers/plans/2026-10-23-bond-tent-plan3-config-sizing-deterministic.md`. Its deterministic classes are the reference for this plan's Monte Carlo twins: `TentReservation`, `TentPools`, `BondTentPlanner` and `BondTentSizer`, all in `backend/wealthview-projection/src/main/java/com/wealthview/projection/`.

### Builder's calls this plan makes

1. **Same tent, other engine.** `TrialTent` follows `TentReservation` exactly:
   - placement in `WithdrawalOrder.drawSequence()`;
   - each pool holds at most its balance;
   - the household migration in `clampAll`: excess is removed shortest maturity first, re-placed into other pools' free room in placement order, longest maturity first, and whatever no pool can hold is spent;
   - sleeves grow at their real return net of the scenario fee;
   - a taxable pool's sleeves pay ordinary interest at the typed nominal yields in retirement years.
2. **The trigger.**
   - The growth index starts at 1.0 today. Each year it is multiplied by `1 + g`, where `g = Σ_p (V_p − T_p) r_p / Σ_p (V_p − T_p)`: the pool returns weighted by each pool's growth part, using balances before growth. `g = 0` when no growth part is left.
   - "Below the previous high" means the index is below its running peak, and the peak includes every value so far.
   - **In retirement,** the test runs after the year's growth.
   - **During build-up,** the top-up at the start of a year reads the index as last year ended.
   - Withdrawals never move the index.
3. **What a trigger year draws.** The tent pays the year's net household outflow first, shortest maturity first, and within a maturity from pools in placement order.
   - The net outflow is everything that left the pools after growth, net of credits: the spending draw, the taxes and the penalty. It equals the expected total after growth minus the total at year end.
   - The withdrawal split, its tax and the accounts debited are untouched (spec rule 7). Only the asset sold changes.
   - In a year at or above the peak, the tent is spent only by `clampAll`, once no pool can hold it.
4. **Build-up is replayed per candidate.**
   - `TentBuildUp` replays the line over each trial's pool totals before and after growth, which `PortfolioPathGenerator` records when a tent exists.
   - Retirement-year index `y` maps to `yearFromToday = yearsBeforeRetirement + y` in `TentPaths` and `TrialTent`.
   - A year below the peak adds at most the year's contributions: `contributionCap = Σ max(0, account contribution)` over today's accounts.
   - The difference between bond and stock returns during build-up changes the trial's start balances. Each pool's owner slices scale proportionally.
5. **Monte Carlo sizing**, once per pass from that pass's schedule.
   - Withdrawal `k` = `max(0, floor_k + discretionary_k − income_k + surplusTax_k + conversionTax_k + withdrawalTax_k)`.
   - `withdrawalTax_k` comes from an expected-path walk, which `TrialTentSizer` defines. It mirrors plan 3's ruling M3: the whole tax bill counts, including conversion tax. If the owner reverses M3, drop `conversionTax_k` here and in `BondTentPlanner.size`.
   - The stochastic-mortality pass sizes from the joint arrays, with no survivor splice.
6. **Left tent-agnostic, documented:**
   - the no-withdrawal `portfolioPaths` that essential-floor verification and the corridors read;
   - the expected-path scalars;
   - the median start balance in the adaptation baseline.

   All of them see pool totals, which include the tent. They ignore only the tent's different returns.
7. **Market window.** A configured tent forces `matrix(false)`, the 1972–2025 window, as the deterministic engine does. `BondMaturityReturnsProvider.alignedTo` is called only when a tent is configured.
8. **Signature.** Only fields that change optimizer results are appended: coverage, build-up start, the plan mix's shares and the yields. They are appended only when a tent exists, so a scenario without a tent keeps its hash and seed. Mix names, comparison mixes and the conversion marker are excluded.
9. **Certified target.**
   - It is the tent target of the final, survivor-scaled recommended schedule, from the headline pass.
   - It is returned as `bond_tent_target`, scale 2, and stored in a new nullable column, `guardrail_spending_profiles.bond_tent_target` (V084). It is `null` without a tent.
10. **Retiring the cash reserve.**
    - It is removed from the engine, the request and response DTOs, the service defaults and the entity mapping. The two columns stay.
    - V085 sets `cash_reserve_years`' default to 0, so new rows stop claiming a 2-year reserve.
    - Old clients that still send the fields are ignored, because `fail-on-unknown-properties: false` is global.
11. **The conversion (V085).**
    - **Which scenarios:** every scenario whose guardrail profile has `cash_reserve_years > 0`, that has an active spending plan (`spending_profile_id` or `guardrail_profile_id` set), and that has no `bond_tent` yet.
    - **The tent it writes:**
      - coverage = `LEAST(cash_reserve_years, 10)`;
      - build-up 0;
      - one mix named "Cash reserve", 100% ultrashort;
      - the default yields;
      - `converted_from_cash_reserve: true`.
    - **Also:**
      - its profile is marked stale;
      - `cash_return_rate` is not carried over, because the tent earns historical T-bill returns;
      - `include_depression_years` is left as stored. The engines force the default window while a tent exists, and the scenario form clears the flag on its next save.
    - A scenario without an active plan is skipped: a tent needs one.
12. **The one-time notice.** It shows while `converted_from_cash_reserve` is present, on the optimizer page and in the scenario form's tent section. The form drops the marker on submit, so the first save retires the notice.

## Global Constraints

**Engine and data rules**
- Monte Carlo stays in `double` and in real terms.
- **The tent adds no `rng` draw.** Maturity rows come from each trial's existing bootstrap index sequence.
- **No tent means byte-identical Monte Carlo.**
  - With `bondTent == null`, every Monte Carlo output and every pinned value is unchanged. This covers `MonteCarloSpendingOptimizerCharacterizationTest`, `StochasticMortalityGoldenTest`, `GuardrailHouseholdSimulatedScheduleTest`, `PortfolioPathGeneratorTest` and `GuardrailAdaptiveGateIntegrationTest`.
  - The only edit allowed in those files is Task 1 dropping the two retired cash-reserve arguments, `0, 0` / `0, BigDecimal.ZERO`, from positional constructor calls.
  - A scenario without a tent keeps its scenario hash and optimizer seed.
- **No deterministic-engine file changes.** The six deterministic goldens and the bond-tent golden pass untouched.
- Every tent code path is inert when `TrialTent` is `null` or empty: same values, no allocation in the year loop. `TrialSimulator.simulateTrial` gains no new PMD suppression; extract helpers instead.
- **Exact error message** when a tent is configured but no maturity data is wired: `A bond tent is configured but bond return assumptions are not wired`, the same text as the deterministic engine.
- **Retired columns.** After Task 1, no code reads `cash_reserve_years` or `cash_return_rate` except the V085 SQL. The columns are never dropped.
- **Converted-tent yields** are `DEFAULT_TENT_YIELDS` from `frontend/src/components/scenario/bondTentDefaults.ts`: ultrashort 0.0394, short 0.0465, intermediate 0.048, long 0.0539.
- **JSON** is snake_case. New keys: `bond_tent_target` on guardrail profiles, and `converted_from_cash_reserve` inside `bond_tent`.

**Code conventions**
- Records for DTOs and value types.
- `@Nullable` from `org.springframework.lang`.
- No wildcard imports, AssertJ only.
- Test names follow `method_state_expected`.
- 120-character lines.
- A PMD suppression needs an adjacent justifying comment. Never suppress CPD.
- Hot-loop types follow `TrialPools`' allocation discipline: one object per trial, mutated in place, scalar parameters, no per-year arrays.

**Build gates**
- Run per module with `-am`: `cd backend && mvn -q -T1 -pl <module> -am verify -DskipITs`. A full-reactor `mvn verify` gets OOM-killed.
- App integration tests: `cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true`.
- Frontend: `npm run test:coverage`, `npm run typecheck` and `npm run lint` in `frontend/`.
- Coverage floors are never lowered.

**Commits**
- Commit on `main`. Never push.
- Conventional Commits, with a body for feat/fix/refactor/db.
- The `Co-Authored-By:` trailer names the model that wrote the commit.

## Review Focus

1. **A scenario without a tent.** Its Monte Carlo results, optimizer seed and scenario hash are unchanged. Pinned by:
   - the untouched Monte Carlo pins;
   - Task 3's `generate_withMaturities_leavesEveryExistingOutputIdentical`;
   - Task 6's `computeScenarioHash_noTent_matchesPinnedValue`.
2. **A crash during the build-up years.** No stocks are sold below the high, only contributions go in, and the tent may enter retirement under target. Pinned by Task 3's `toRetirement_crashDuringBuildUp_topsUpOnlyFromContributions`.
3. **A converted cash-reserve scenario.** It carries a complete tent that validates and sizes, its profile is stale, and the first save of the form drops the notice marker. Pinned by:
   - Task 7's `convert_reserveScenario_writesCompleteValidTent`;
   - Task 8's `submit omits converted_from_cash_reserve`.
4. **Retirement this year or already past, or a build-up start of 0.** The tent is fully funded at retirement whatever the market did. Pinned by:
   - Task 3's `toRetirement_buildUpZero_fundsFullTargetAtRetirementInADownMarket` and `fundedAtStart_firstPoolTooSmall_spillsToNextPool`;
   - Task 4's `configFor_retiredAlready_fundsFullTargetInYearZero`.
5. **A trial whose growth assets run out, or a tent bigger than the portfolio.** Everything is reserved, no pool goes negative, withdrawals spend the tent, and the essential-floor test counts those dollars. Pinned by:
   - Task 2's `topUp_levelAboveTotalBalance_reservesEverything` and `clampAll_noRoomAnywhere_spendsTheRest`;
   - Task 4's `simulateTrial_growthAssetsGone_spendsTentAndFundsTheFloor`.

---

### Task 1: Retire the cash reserve

**Files** (paths under `backend/`):
- Modify, projection main (`wealthview-projection/src/main/java/com/wealthview/projection/`):
  - `TrialSimulator.java`
  - `TrialPools.java`
  - `TrialConfigFactory.java`
  - `PortfolioSetup.java`
  - `SustainabilitySearch.java`
  - `OptimizationContextBuilder.java`
  - `GuardrailResponseBuilder.java`
  - `StochasticMortalityEvaluator.java`
  - `MonteCarloSpendingOptimizer.java`
- Modify, core (`wealthview-core/src/main/java/com/wealthview/core/projection/`):
  - `dto/GuardrailOptimizationInput.java`
  - `dto/GuardrailOptimizationRequest.java`
  - `dto/GuardrailProfileResponse.java`
  - `GuardrailProfileService.java`
- Modify, persistence: `wealthview-persistence/src/main/java/com/wealthview/persistence/entity/GuardrailSpendingProfileEntity.java`. Remove the two fields and their accessors.
- Test, every file that names the reserve:
  - projection: `TrialSimulatorReturnTest`, `MonteCarloSpendingOptimizerTest`, `TrialSimulatorConfigBuilderTest`, `TrialSimulatorStochasticTest`, `TrialSimulatorHouseholdTest`, `GuardrailRulesTaxDynamicsMonotonicityTest`, `PreRetirementAccumulationTest`, `testutil/GuardrailOptimizationInputBuilder`;
  - core: `GuardrailProfileServiceTest`, `GuardrailOptimizationRequestTest`, `GuardrailOptimizationInputTest`, `GuardrailProfileResponseSerializationTest`;
  - api: `GuardrailControllerTest`;
  - the positional constructor calls in `MonteCarloSpendingOptimizerCharacterizationTest` and `StochasticMortalityGoldenTest`.

**Interfaces:**
- Produces:
  - `TrialSimulator.applyTrialWithdrawals(TrialPools tp, PoolWithdrawal drawn, double withdrawalTax, boolean hasPools, OrdinaryTaxTable table, double base)` returns `WithdrawalOutcome(double realizedGain, double traditionalDrawn)`.
  - These are gone:
    - from `TrialSimulator`: `seedCashReserve`, `CASH_REPLENISHMENT_RATE` and the `cashBalance`/`portfolioReturn` locals;
    - from `TrialPools`: `debitTaxableWithTraditionalSpillover`;
    - the `cashReserveYears`/`cashReturnRate` components, builder methods and accessors on `SimulationConfig`, `TrialConfigFactory`, `PortfolioSetup`, `SearchContext`, `GuardrailOptimizationInput`, `GuardrailOptimizationRequest`, `GuardrailProfileResponse` and the entity;
    - `GuardrailProfileService.DEFAULT_CASH_RESERVE_YEARS` and `DEFAULT_CASH_RETURN_RATE`.
  - Every back-compat constructor drops the same two positional parameters.
- The essential-floor resource is `view.income() + drawn.total()`.

- [ ] **Step 1: Write the failing tests**

```java
// GuardrailControllerTest (MockMvc, service mocked)
@Test void optimize_requestWithRetiredCashReserveFields_returns200()
    // body includes "cash_reserve_years": 3, "cash_return_rate": 0.02 -> 200; the service receives a request with no such fields
// GuardrailProfileResponseSerializationTest
@Test void serialize_profile_hasNoCashReserveKeys()  // JSON has no "cash_reserve_years" and no "cash_return_rate"
// GuardrailProfileServiceTest
@Test void reoptimize_storedProfile_rebuildsRequestWithoutCashReserve()  // compiles only once the builder methods are gone
```

Delete the tests that exist only to pin the retired behavior:
- the cash-reserve `applyTrialWithdrawals_*` cases in `TrialSimulatorReturnTest`;
- the "hasPools + cashReserveYears > 0" cases near `MonteCarloSpendingOptimizerTest:2162–2260`;
- the default-reserve cases in `GuardrailProfileServiceTest`.

Keep and adapt the no-reserve `applyTrialWithdrawals` cases to the new signature. Drop `withCashReserveYears` and `.cashReturnRate(BigDecimal.ZERO)` from the test builder.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-api -am test-compile`
Expected: compilation FAILS, because the tests reference the new `applyTrialWithdrawals` shape and the old accessors are still there.

- [ ] **Step 3: Remove the reserve from the engine, DTOs, service and entity**

`reoptimize` stops copying the two fields. The entity stops mapping the columns, so inserts take the database default; V085 changes that default to 0 later.

- [ ] **Step 4: Run the gates**

Run:
```
cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs
cd backend && mvn -q -T1 -pl wealthview-api -am verify -DskipITs
cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true
```
Expected: BUILD SUCCESS for all three.
- No pinned Monte Carlo value moves. If one does, stop: the removal changed a no-reserve path.
- `grep -rln "cashReserve\|cashReturn\|cash_reserve\|cash_return" backend/*/src/main` prints only `V040__add_cash_buffer_to_guardrail_profiles.sql`.

- [ ] **Step 5: Commit**

Subject: `feat(projection): retire the stand-alone cash reserve`. The body says:
- why the reserve goes (spec §4.3 rule 8);
- that no-reserve paths are unchanged;
- that the columns stay;
- that V085 converts existing reserves.

Footer: `BREAKING CHANGE: the optimizer API no longer returns cash_reserve_years or cash_return_rate, and ignores them on requests.`

---

### Task 2: `TrialTent`, the per-trial household tent

**Files:**
- Create: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialTent.java`
- Create: `.../projection/TrialTentPlan.java`. Only its record shape is needed here; Task 3 adds the factory and the line.
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/TrialTentTest.java`

**Interfaces:**
- Consumes: `BondMaturity` and `PoolType` from core.
- Produces:
  - `record TrialTentPlan(double coverageYears, double sizingRealYield, double[] mixShares, double[] nominalYields, List<PoolType> placementOrder, int yearsBeforeRetirement, int buildUpStartIndex, double contributionCap, MaturityReturnMatrix maturities)`:
    - package-private;
    - the arrays are indexed by `BondMaturity.ordinal()`, shortest first.
  - `final class TrialTent`, package-private and mutable, one per trial:
    - `TrialTent(TrialTentPlan plan, double[] sleeveReturns)`. The `sleeveReturns` layout is `[yearFromToday * 4 + maturity.ordinal()]`, real and net of fee.
    - `int yearsBeforeRetirement()`
    - `double total()`, `double totalIn(PoolType pool)`, `double byMaturity(BondMaturity m)`
    - `boolean isEmpty()`: true when `total() <= 0`.
    - `void topUp(double level, double taxable, double traditional, double roth, double cap)`:
      - reserves `min(cap, max(0, level − total()))` more;
      - fills pools in `placementOrder` up to each pool's free balance, which is its balance minus `totalIn(pool)`;
      - splits each pool's addition by `mixShares`.
    - `double grownBalance(PoolType pool, double balance, double poolReturn, int yearFromToday)` returns `(balance − totalIn(pool))(1 + poolReturn) + Σ_m sleeve_{pool,m} (1 + r_{yearFromToday,m})`. No mutation.
    - `void growSleeves(int yearFromToday)`
    - `double interestIn(PoolType pool)` returns `Σ_m sleeve_{pool,m} × nominalYields[m]`.
    - `double growthReturn(double taxable, double traditional, double roth, double taxableReturn, double traditionalReturn, double rothReturn)` follows Builder's call 2.
    - `void advanceIndex(double growthReturn)`, `boolean isBelowPeak()`. The index starts at 1.0 with peak 1.0.
    - `void spend(double amount)` removes `min(amount, total())`, shortest maturity first, and within a maturity from pools in placement order.
    - `void clampAll(double taxable, double traditional, double roth)` follows `TentReservation.clampAll`.

- [ ] **Step 1: Write the failing tests**

Fixture: placement taxable, traditional, Roth; shares .2/.3/.5/0; yields .04/.045/.05/.055; sleeve returns per year .01/.02/.03/.04.

```java
@Test void topUp_reservesInPlacementOrderSplitByShares()       // taxable 300k, trad 500k, level 200k -> taxable 40k/60k/100k/0
@Test void topUp_firstPoolTooSmall_spillsToNextPool()          // taxable 50k -> totalIn(TAXABLE) 50k, TRADITIONAL 150k
@Test void topUp_cap_limitsTheIncrease()                       // level 200k, cap 30k -> total 30k
@Test void topUp_levelAboveTotalBalance_reservesEverything()   // balances sum 120k, level 200k -> total 120k
@Test void grownBalance_blendsGrowthPartAndSleeves()           // traditional 500k, 100k reserved, poolReturn .05, all sleeves at .01 -> 521000.0
@Test void growSleeves_eachAtItsOwnReturn()                    // 20k/30k/50k -> 20200/30600/51500
@Test void interestIn_isSleeveTimesNominalYield()              // 20k×.04 + 30k×.045 + 50k×.05 = 4650.0
@Test void growthReturn_weightsGrowthPartsOnly()               // taxable 300k (100k reserved), trad 200k, roth 0; .10/.00/.50 -> 0.05
@Test void isBelowPeak_onlyUntilTheIndexRegainsItsHigh()
    // advance +.1 -> false; -.2 -> true (0.88); +.1 -> true (0.968); +.2 -> false (1.1616)
@Test void spend_shortestMaturityFirst()                       // 20k/30k/50k, spend 35k -> 0/15k/50k
@Test void spend_withinAMaturity_followsPlacementOrder()       // taxable ultrashort 10k, trad ultrashort 10k, spend 15k -> 0 and 5k
@Test void clampAll_poolBelowItsReservation_movesExcessToFreeRoom()
    // taxable 100k reserved 20k/30k/50k/0, taxable balance falls to 30k, trad 500k free
    // -> taxable keeps intermediate 30k; traditional gets 20k/30k/20k/0; total stays 100k
@Test void clampAll_noRoomAnywhere_spendsTheRest()             // same tent, taxable 60k, others 0 -> total 60k, taxable short 10k + intermediate 50k
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement `TrialTentPlan` (record only) and `TrialTent`**

Hold the sleeves in one flat `double[12]`, indexed `pool.ordinal() * 4 + maturity.ordinal()`. No method allocates.

- [ ] **Step 4: Run the gate**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

Subject: `feat(projection): add a per-trial household bond tent for Monte Carlo`

---

### Task 3: Tent paths, the build-up replay and Monte Carlo sizing

**Files:**
- Create in `backend/wealthview-projection/src/main/java/com/wealthview/projection/`: `TentPaths.java`, `TentBuildUp.java`, `TrialTentSizer.java`.
- Modify:
  - `TrialTentPlan.java` (add `of`, `buildUpSpan`, `lineLevel`)
  - `PortfolioPathGenerator.java`
  - `PortfolioReturnPaths.java`
  - `TrialStartBalances.java`
- Test: `TentBuildUpTest.java`, `TrialTentSizerTest.java` and `TrialTentPlanTest.java` (new), plus `PortfolioPathGeneratorTest.java` and `TrialStartBalancesTest.java` (extend). All are in projection.

**Interfaces:**
- Consumes: Task 2's `TrialTent` and `TrialTentPlan`; plan 3's `BondTentSizer.mixRealYield/target` and `BondTentConfig`; `TrialSimulator.splitWithdrawal`; `OrdinaryTaxTable`.
- Produces:
  - `TrialTentPlan`:
    - `static TrialTentPlan of(BondTentConfig config, WithdrawalOrder order, double inflationRate, int yearsBeforeRetirement, double contributionCap, MaturityReturnMatrix maturities)`:
      - `buildUpStartIndex = max(0, yearsBeforeRetirement − config.buildUpStartYears())`;
      - `sizingRealYield = BondTentSizer.mixRealYield(config.yields(), config.planMix().shares(), inflationRate)`;
      - `placementOrder = order.drawSequence()`;
      - null shares and yields read as 0.
    - `int buildUpSpan()` returns `yearsBeforeRetirement − buildUpStartIndex`.
    - `double lineLevel(int yearFromToday, double target)` follows plan 3's line:
      - 0 before the start;
      - `target` when the span is 0 or `yearFromToday ≥ yearsBeforeRetirement`;
      - otherwise `target × (yearFromToday − start) / span`.
  - `record TentPaths(double[][] sleeveReturns, @Nullable double[][] poolsBeforeGrowth, @Nullable double[][] poolsAfterGrowth)`:
    - package-private;
    - layouts: `sleeveReturns[t][yearFromToday * 4 + maturity]` over `pre + years` years, net of fee; `pools*[t][yearFromToday * 3 + pool.ordinal()]` over the pre-retirement years only (null when there are none);
    - accessors `double[] sleeveReturnsOf(int t)`, `double sleeveReturn(int t, int yearFromToday, BondMaturity m)`, `double poolBeforeGrowth(int t, int yearFromToday, PoolType p)` and `double poolAfterGrowth(int t, int yearFromToday, PoolType p)`.
  - `PortfolioPathGenerator.generate(int trialCount, int preRetirementYears, int years, PoolReturnModel model, RealReturnMatrix matrix, Random rng, double feeRate, @Nullable MaturityReturnMatrix maturities)`:
    - with `maturities` non-null, it also builds `TentPaths`;
    - the 7-argument overload delegates with `null`;
    - "before growth" is the pool's account balances plus this year's contributions; "after growth" is the same accounts after `accumulate`'s growth step.
  - `PortfolioReturnPaths` gains a 6th component, `@Nullable TentPaths tentPaths`. The 5-argument constructor passes `null`.
  - `TrialStartBalances adjustedBy(double taxable, double traditional, double roth)`:
    - adds each amount to its pool, floored at 0;
    - scales the pool's owner slices proportionally;
    - a pool whose slices sum to 0 takes the amount on its primary slice (taxable: joint).
  - `final class TentBuildUp`, package-private:
    - `record Start(TrialTent tent, double taxableAdjustment, double traditionalAdjustment, double rothAdjustment)`
    - `static Start toRetirement(TrialTentPlan plan, TentPaths paths, int t, double target, TrialStartBalances noTentStart)`
    - `static Start fundedAtStart(TrialTentPlan plan, double[] sleeveReturns, double target, double taxable, double traditional, double roth)`: an ungated top-up to `target`, with no adjustments.
  - `final class TrialTentSizer`, package-private:
    - `record Inputs(@Nullable OrdinaryTaxTable[] tables, @Nullable double[] baseIncome, @Nullable double[] conversionByYear, @Nullable double[] conversionTaxByYear, @Nullable double[] dsCeiling, double taxable, double traditional, double roth, WithdrawalOrder order, int retirementAge, int earlyAccessAge)`
    - `static double[] withdrawals(TrialTentPlan plan, Inputs in, double[] floors, double[] discretionary, double[] income, double[] surplusTax)`
    - `static double target(TrialTentPlan plan, Inputs in, double[] floors, double[] discretionary, double[] income, double[] surplusTax)` returns `BondTentSizer.target(withdrawals(...), plan.coverageYears(), plan.sizingRealYield())`.

**`TentBuildUp.toRetirement` algorithm** (`D_p` starts at 0; `pre = plan.yearsBeforeRetirement()`):
```
for y in 0 .. pre-1:
    V_p = poolBeforeGrowth(t, y, p) + D_p                       // after contributions
    if y >= buildUpStartIndex:
        clampAll(V); cap = isBelowPeak() ? contributionCap : +infinity
        topUp(lineLevel(y, target), V, cap)
    r_p = before > 0 ? after / before - 1 : 0                   // per pool, from paths
    g = growthReturn(V, r)                                      // before growing anything
    D_p = grownBalance(p, V_p, r_p, y) - poolAfterGrowth(t, y, p)
    growSleeves(y); advanceIndex(g)
at retirement: V_p = noTentStart pool + D_p; clampAll(V)
    cap = (buildUpSpan() == 0 || !isBelowPeak()) ? +infinity : 0
    topUp(target, V, cap)
```

**`TrialTentSizer.withdrawals` algorithm** (`K = min(ceil(coverageYears), floors.length)`; the running expected balances start at `in.taxable/traditional/roth`):
```
for k < K:
    spending = floors[k] + discretionary[k];  need = max(0, spending - income[k])
    conv = conversionByYear == null ? 0 : min(conversionByYear[k], traditional)
    convTax = conv <= 0 ? 0 : tables != null ? tables[k].incrementalTax(baseIncome[k], conv) : conversionTaxByYear[k]
    split = TrialSimulator.splitWithdrawal(taxable, traditional, roth, need, order,
                conversionByYear != null && retirementAge + k < earlyAccessAge,
                dsCeiling == null ? 0 : dsCeiling[k], income[k], conv, 0)
    wTax = tables != null && split.traditional() > 0 ? tables[k].incrementalTax(baseIncome[k] + conv, split.traditional()) : 0
    w[k] = max(0, spending - income[k] + surplusTax[k] + convTax + wTax)
    taxable -= split.taxable(); traditional -= conv + split.traditional(); roth += conv - split.roth()   // each floored at 0
```
The walk ignores growth and RMDs: it is a short-horizon tax estimate, and the code says so.

- [ ] **Step 1: Write the failing tests**

```java
// PortfolioPathGeneratorTest
@Test void generate_withMaturities_leavesEveryExistingOutputIdentical()
    // equal seeds (new Random(7L) each), pre 3, years 10, with and without maturities:
    // the three return arrays, portfolioPaths and startBalances are equal; tentPaths non-null only with maturities
@Test void generate_withMaturities_sleeveReturnsAreTheTrialsMatrixRowsLessFee()
    // one-row matrix and maturity matrix, fee .005 -> every sleeveReturn(t, y, m) == row[m] - .005
@Test void generate_preRetirement_recordsPoolTotalsBeforeAndAfterGrowth()
    // one taxable account 100k, contribution 10k, US stock +10%, fee 0, pre 2:
    // before/after = 110000/121000 then 131000/144100
@Test void generate_withoutMaturities_hasNoTentPaths()
// TrialStartBalancesTest
@Test void adjustedBy_scalesOwnerSlicesProportionally()
    // taxable 100k (joint 60k, primary 40k), trad 200k (spouse 50k); +10k, -20k, 0
    // -> taxable 110k (66k/44k), trad 180k (spouse 45k)
@Test void adjustedBy_emptyPool_putsTheAmountOnThePrimarySlice()   // roth 0, +5k -> roth 5k, rothSpouse 0
// TrialTentPlanTest
@Test void of_balancedMix_resolvesSharesYieldsPlacementAndRealYield()
    // TRADITIONAL_FIRST -> [TRADITIONAL, TAXABLE, ROTH]; inflation .025 -> sizingRealYield 0.0203219512 within 1e-9
@Test void lineLevel_threeYearBuildUp_isAStraightLine()            // pre 3, build-up 3, 90k -> 0, 30k, 60k, 90k
@Test void lineLevel_buildUpBeforeToday_compressesTheRamp()        // pre 4, build-up 10 -> start 0; year 1 -> 20k of 80k
@Test void lineLevel_buildUpZero_isTheFullTargetAtRetirement()     // pre 3, build-up 0 -> lineLevel(3) == target, span 0
// TentBuildUpTest — one taxable pool, sleeves all 0%, target 90k, pre 3, build-up 3 unless stated
@Test void toRetirement_upMarkets_followsTheLineToTarget()
    // +10% x3, no contributions -> tent 90000, taxable adjustment -9300
@Test void toRetirement_crashDuringBuildUp_topsUpOnlyFromContributions()
    // -20%, +5%, +5%; contributions 10k (cap 10k); before 1,010,000/818,000/868,900, after 808,000/858,900/912,345
    // -> tent 20000 (under the 90000 target), taxable adjustment -1525
@Test void toRetirement_buildUpZero_fundsFullTargetAtRetirementInADownMarket()   // -20% x3, build-up 0 -> tent 90000, adjustment 0
@Test void toRetirement_bondsHoldUpInACrash_raisesTheStartBalance()
    // +10%, -30%, 0% -> tent 30000, taxable adjustment +9000
@Test void toRetirement_buildUpBeforeToday_compressesTheRamp()     // pre 4, build-up 10, target 80k, +10% x4 -> tent 80000, adjustment -12820
@Test void fundedAtStart_firstPoolTooSmall_spillsToNextPool()      // target 200k, taxable 50k, trad 500k -> 50k taxable, 150k traditional
// TrialTentSizerTest — coverage 5, sizingRealYield given directly
@Test void target_noTaxTables_isPresentValueOfTheNeed()            // floors 40k x5, r .015 -> 191305.7989 within 1e-3
@Test void target_twoAndAHalfYears_countsTheHalfYear()             // coverage 2.5 -> 97361.6768 within 1e-3
@Test void target_incomeAboveSpending_isZero()                     // income 50k
@Test void target_baseIncomeTaxJoinsTheWithdrawal()                // income 30k, surplusTax 5k, r 0 -> 75000.0
@Test void target_traditionalFirst_addsOrdinaryTaxOnTheDraw()      // flat(0.20), trad 1M, r 0 -> 240000.0
@Test void target_taxableFirstFromTaxable_addsNoOrdinaryTax()      // flat(0.20), taxable 1M, r 0 -> 200000.0
@Test void target_beforeEarlyAccessWithConversions_drawsTaxableAndPricesTheConversion()
    // TRADITIONAL_FIRST, conversions 10k/yr, flat(0.20), age 55, early access 60, taxable 1M, trad 1M -> 210000.0
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

`generate` records the pool totals inside `accumulate`, and reads the sleeve rows from the same `full`/`indexSequence` arrays. It adds no `rng` call and builds nothing extra when `maturities` is null.

- [ ] **Step 4: Run the gate**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS, with `PortfolioPathGeneratorTest`'s existing pins unedited.

- [ ] **Step 5: Commit**

Subject: `feat(projection): build a Monte Carlo bond tent along each trial's own path`. The body covers:
- the zero-extra-draw paths;
- the per-candidate replay;
- the contributions-only rule below the high;
- the expected-path tax estimate.

---

### Task 4: The trial loop holds, draws and settles the tent

**Files:**
- Modify in `backend/wealthview-projection/src/main/java/com/wealthview/projection/`:
  - `TrialSimulator.java` (`SimulationConfig` and the year loop)
  - `TrialPools.java`
  - `TrialConfigFactory.java`
  - `TrialPassRunner.java`
- Test: `TrialSimulatorTentTest.java` (new), and `TrialPoolsTest.java`, `TrialConfigFactoryTest.java`, `TrialPassRunnerTest.java` and `TrialSimulatorConfigBuilderTest.java` (extend or create). All are in projection.

**Interfaces:**
- Consumes: Tasks 2–3.
- Produces:
  - `SimulationConfig` gains `@Nullable TrialTent tent`, last. `Builder.tent(@Nullable TrialTent)` defaults to `null`. It is a fresh object per trial, owned and mutated by that trial.
  - `TrialPools.growTaxable(double taxableReturn, double dividendYield, double interestYield, double taxableEquityShare, double reserved, double sleeveInterest)`:
    - The pool grows to exactly `before × (1 + taxableReturn)`.
    - `dividend = (before − reserved) × taxableEquityShare × dividendYield`.
    - `interest = (before − reserved) × (1 − taxableEquityShare) × interestYield + sleeveInterest`.
    - The lots grow at `taxableReturn − (dividend + interest) / before`. The residual distribution is reinvested at cost and split dividend-to-interest in the same ratio.
    - With `reserved == 0 && sleeveInterest == 0` it delegates to the existing 4-argument method.
  - `TrialConfigFactory`:
    - `Builder.tent(@Nullable TrialTentPlan plan, @Nullable TentPaths paths)`.
    - `double tentTargetFor(double[] floors, double[] discretionary, double[] income, double[] surplusTax)` returns 0 without a plan, else `TrialTentSizer.target` with `Inputs` from the factory's tables, base income, conversions, DS ceilings, pool setup balances, order and ages.
    - `configFor(int t, @Nullable GuardrailAdaptation adaptation, boolean trackYearBalances, @Nullable HouseholdSim householdOverride, double tentTarget)`:
      - With a plan and per-trial start balances, it runs `TentBuildUp.toRetirement`. The adjusted `TrialStartBalances` then replaces `startBalances[t]` everywhere `configFor` uses it, the household included.
      - With a plan and no start balances, it uses `fundedAtStart` on the resolved opening balances.
      - The 4-argument `configFor` delegates with `0`.
  - `TrialPassRunner.run` computes `configFactory.tentTargetFor(floors, discretionary, income, surplusTax)` once per call and passes it to every trial.
  - `TrialSimulator`, when `config.tent()` is non-null and not empty, for retirement year `y`, with `i = tent.yearsBeforeRetirement() + y`:
    1. Each pool's effective return is `tent.grownBalance(p, V_p, r_p, i) / V_p − 1`, or `r_p` when `V_p == 0`. Taxable grows through the 6-argument `growTaxable` with `tent.totalIn(TAXABLE)` and `tent.interestIn(TAXABLE)`. The interest is taxed by the existing `applyInterestTax`.
    2. Then `tent.growSleeves(i)` and `tent.advanceIndex(tent.growthReturn(...))`, with balances and returns from before growth.
    3. After `tp.clampNonNegative()`:
       - if `tent.isBelowPeak()`, `tent.spend(max(0, expectedAfterGrowth − tp.poolTotal()))`;
       - then `tent.clampAll(tp.taxable(), tp.traditionalTotal(), tp.rothTotal())`.

       Here `expectedAfterGrowth = Σ_p V_p (1 + effective_p)`.

    Everything else in the year (the withdrawal split, taxes, RMDs, conversions and the floor test) is unchanged.

- [ ] **Step 1: Write the failing tests**

`TrialSimulatorTentTest` fixture:
- taxable only, no tax tables, dividend and interest yields 0;
- floor 40k, no discretionary or income;
- opening taxable 1,000,000;
- a 100%-ultrashort tent of 80,000 funded with `fundedAtStart`;
- sleeve returns 0.

```java
@Test void simulateTrial_crashInFirstYear_spendsTentBeforeStocks()
    // returns -30%, +10% x4 -> final 810480.40 (no tent: 780666.00), within 1e-6
@Test void simulateTrial_risingMarket_neverDrawsTheTentAndCostsGrowth()
    // +5% x5 -> final 1033153.7875 (no tent: 1055256.3125); tent total stays 80000
@Test void simulateTrial_growthAssetsGone_spendsTentAndFundsTheFloor()
    // opening 100k, +5% x2, tracked -> yearBalances [61000, 21000], success true; no pool negative
@Test void simulateTrial_retiredTaxableTent_taxesSleeveInterestAsOrdinary()
    // 1 year, +5%, floor 0, flat(0.25) tables, ultrashort yield .04 -> final 1045200 (yield 0: 1046000)
@Test void simulateTrial_emptyTent_isIdenticalToNoTent()          // every TrialResult field equal to the .tent(null) run
// TrialPoolsTest
@Test void growTaxable_withReservation_splitsYieldOverUnreservedPartAndAddsSleeveInterest()
    // 1M at cost, reserved 100k, return .05, dividend .02, interest .04, equity .6, sleeve interest 3000
    // -> pool 1050000, dividend 10800, interest 17400
@Test void growTaxable_noReservation_matchesTheExistingSplit()
// TrialConfigFactoryTest
@Test void configFor_tentWithPreRetirementYears_carriesTheBuildUpIntoStartBalances()
    // TentBuildUpTest's "bonds hold up" paths -> initTaxable 779000, tent total 30000
@Test void configFor_retiredAlready_fundsFullTargetInYearZero()   // no start balances, target 200k, taxable 50k, trad 500k
@Test void tentTargetFor_noPlan_isZero()
// TrialPassRunnerTest (Mockito mock of TrialSimulator capturing configs)
@Test void run_tentPlan_givesEveryTrialTheSchedulesTarget()       // each captured tent total == tentTargetFor(...) for a pool large enough
// TrialSimulatorConfigBuilderTest
@Test void builder_default_hasNoTent()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

Put the tent's growth step and year-end settlement in small private helpers next to `applyGrowthAndInterestTax`, so `simulateTrial` stays inside its existing PMD budget.

- [ ] **Step 4: Run the gate**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS, with every Monte Carlo pin unedited.

- [ ] **Step 5: Commit**

Subject: `feat(projection): hold and draw the bond tent in Monte Carlo trials`. The body states:
- the trigger;
- that a trigger year's net outflow is paid from the tent first;
- that the withdrawal split and its tax are unchanged (spec rule 7);
- the no-refill rule.

---

### Task 5: The optimizer certifies spending with the tent

**Files:**
- Modify in `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/`: `GuardrailOptimizationInput.java`, `GuardrailProfileResponse.java`.
- Modify in `backend/wealthview-projection/src/main/java/com/wealthview/projection/`:
  - `MonteCarloSpendingOptimizer.java`
  - `OptimizationContextBuilder.java`
  - `SimulationParameters.java`
  - `SustainabilitySearch.java`
  - `JointConversionSearch.java`
  - `GuardrailResponseBuilder.java`
  - `StochasticMortalityEvaluator.java`
- Test:
  - `MonteCarloSpendingOptimizerTentTest.java` and `MonteCarloBondTentCharacterizationTest.java` (new);
  - `SustainabilitySearchTest.java` (extend);
  - `testutil/ProjectionTestFixtures.java` and `testutil/GuardrailOptimizationInputBuilder.java` (extend).

  All are in projection.

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces:
  - `GuardrailOptimizationInput` gains `@Nullable BondTentConfig bondTent`, last. `Builder.bondTent(...)` defaults to null; `withAccounts` carries it; back-compat constructors pass null. Today's canonical constructor stays as a back-compat constructor, so the positional calls in the two pinned tests compile unedited.
  - `GuardrailProfileResponse` gains `@Nullable BigDecimal bondTentTarget`, last. Today's canonical constructor becomes a back-compat one passing `null`. On the wire it is `bond_tent_target`.
  - `SimulationParameters` gains `@Nullable TrialTentPlan tentPlan` and `@Nullable TentPaths tentPaths`, last. `emptyHorizon` passes nulls.
  - `OptimizationContextBuilder.build(GuardrailOptimizationInput input, List<? extends ProjectionAccountInput> todayAccounts, RealReturnMatrix matrix, @Nullable MaturityReturnMatrix maturities)`:
    - With `input.bondTent() != null` it requires `maturities`, or throws `IllegalStateException` with the Global Constraints message.
    - It builds `TrialTentPlan.of(...)` with `contributionCap` from `returnModel.allAccounts()`.
    - It passes `maturities` to `generate`.
    - The 3-argument `build` delegates with null.
  - `MonteCarloSpendingOptimizer`:
    - The `@Autowired` constructor adds a 5th parameter, `@Nullable BondMaturityReturnsProvider`.
    - It gains a test constructor `(FederalTaxCalculator, RealReturnMatrix, MaturityReturnMatrix)`.
    - `matrix(...)` receives `includeDepressionYears && bondTent == null`.
    - The maturity matrix is the preset or `provider.alignedTo(matrix)`, resolved only when a tent is configured. This holds in both `optimizeInternal` and `evaluateStochasticMortality`.
  - Every `TrialConfigFactory` built for the run gets `.tent(plan, paths)`:
    - in `SustainabilitySearch.buildConfigFactory`, with `ctx.returnPaths().tentPaths()`;
    - in `GuardrailResponseBuilder` and `StochasticMortalityEvaluator`, with `ctx.sim()`'s.
  - `MonteCarloSpendingOptimizer.searchContextFor` puts `ctx.sim().tentPaths()` into its `PortfolioReturnPaths`.
  - `JointConversionSearch.jointSearch` passes the plan's maturities to `generate`.
  - `GuardrailResponseBuilder.build` sets `bondTentTarget = tentTargetFor(adjustedFloors, discretionaryByYear, income, surplusTax)`, scale 2 HALF_UP, when the plan is non-null.
  - Test fixtures:
    - `ProjectionTestFixtures.TEST_MATURITY_MATRIX` has years 1–8, matching `TEST_CMA_MATRIX` row by row:
      - ultrashort = that row's cash column;
      - short = cash + 0.005;
      - intermediate = bond × 0.6;
      - long = bond × 1.5.
    - `GuardrailOptimizationInputBuilder.withBondTent(BondTentConfig)`.

- [ ] **Step 1: Write the failing tests**

```java
// MonteCarloSpendingOptimizerTentTest — test constructor with TEST_CMA_MATRIX + TEST_MATURITY_MATRIX
@Test void optimize_noTent_hasNoTentTarget()
@Test void optimize_withTent_certifiesTheTargetOfTheRecommendedSchedule()
    // taxable-only, no tax calculator, no income, retirement in the base year, coverage 5, Balanced mix:
    // bondTentTarget == Σ_{k<5} recommended_k / (1 + r)^(k+1) within 1.00 (recommended is rounded),
    // where recommended_k = yearlySpending[k].recommended() and r = mixRealYield(yields, shares, inflation)
@Test void optimize_withTentAndPreRetirementYears_changesTheRecommendation()   // base year 3 before retirement: with vs without differ
@Test void optimize_tentAndDepressionYearsFlag_usesTheDefaultWindow()
    // production constructor, mocked providers: matrix(false) called, matrix(true) never
@Test void optimize_tentWithoutMaturityProvider_throws()        // message per Global Constraints
@Test void optimize_noTent_neverAlignsMaturities()             // mocked BondMaturityReturnsProvider: alignedTo never called
// SustainabilitySearchTest
@Test void isSustainable_withTent_isMonotoneInDiscretionary()
    // discretionary 0..200k in 10k steps on the tent fixture: at most one sustainable -> unsustainable transition
// MonteCarloBondTentCharacterizationTest — SEED 20261024L, 1000 trials, base year 2027, retirement 2030,
// taxable 600k + traditional 400k (all US stock), floor 40k, coverage 5, build-up 3, Balanced mix, default yields;
// tax calculator null, as in MonteCarloSpendingOptimizerCharacterizationTest
@Test void optimize_bondTentFixture_matchesPinnedValues()
    // pins bondTentTarget, successProbability, medianFinalBalance and year-0 recommended spending, recorded once
@Test void optimize_bondTentFixture_sameSeedIsDeterministic()
```

For the characterization pins: run once, then check by hand that `bondTentTarget` matches `TrialTentSizer.target` for the final schedule before pinning. Record the values in the test with a comment saying how each was produced.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement the DTO components, the wiring and the response target**

- [ ] **Step 4: Run the gates and measure**

Run:
```
cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs
cd backend && mvn -q -T1 -pl wealthview-api -am verify -DskipITs
```
Expected: BUILD SUCCESS for both, with every Monte Carlo pin unedited.

Measure: time `optimize` on the characterization fixture with and without the tent, three runs each, and report both medians. A tent run more than 1.25× slower is a finding to report, not a gate.

- [ ] **Step 5: Commit**

Subject: `feat(projection): certify guardrail spending with the bond tent in place`. The body covers:
- per-pass sizing;
- the window rule;
- that the certified target comes from the final schedule;
- the tent-agnostic pieces from Builder's call 6;
- the measured timings.

---

### Task 6: Scenario signature, service wiring and the stored target

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V084__add_bond_tent_target_to_guardrail_profiles.sql`
- Modify:
  - `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/GuardrailSpendingProfileEntity.java`
  - `backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java`
  - `.../dto/GuardrailProfileResponse.java` (`from(entity)`)
- Test: `GuardrailProfileServiceTest.java` and `ScenarioCrudServiceTest.java` in core, extended; the guardrail profile repository's `@DataJpaTest` in persistence, extended.

**Interfaces:**
- Consumes: Task 5's `GuardrailOptimizationInput.bondTent` and `GuardrailProfileResponse.bondTentTarget`; plan 3's `ScenarioParams.bondTent()`.
- Produces:
  - **V084:** `ALTER TABLE guardrail_spending_profiles ADD COLUMN IF NOT EXISTS bond_tent_target numeric(19,4);`, nullable, with a header comment.
  - **Entity:** `@Column(name = "bond_tent_target", precision = 19, scale = 4) BigDecimal bondTentTarget`, with a getter and setter.
  - **`GuardrailProfileService`:**
    - `buildOptimizationInput` sets `.bondTent(params.bondTent())`.
    - `populateGuardrailEntity` stores `optimizerResult.bondTentTarget()`.
    - `scenarioSignature` appends this when `hashParams.bondTent() != null`:
      ```
      |tent:<coverage>:<buildUp>:<u>,<s>,<i>,<l>:<yu>,<ys>,<yi>,<yl>
      ```
      - The shares come from the plan mix.
      - Every number is formatted with `stripTrailingZeros().toPlainString()`, and null reads as `0`.
      - It is appended after the income sources.
  - **`GuardrailProfileResponse.from(entity)`** maps `bondTentTarget`.

- [ ] **Step 1: Write the failing tests**

```java
// GuardrailProfileServiceTest
@Test void computeScenarioHash_noTent_matchesPinnedValue()
    // a fixed no-tent scenario fixture; record the hash from the CURRENT code before editing, then pin it
@Test void computeScenarioHash_tentCoverageChange_changesHash()      // 5 -> 6
@Test void computeScenarioHash_comparisonMixEdit_keepsHash()         // second mix's shares change; plan mix unchanged
@Test void computeScenarioHash_mixRename_keepsHash()
@Test void computeScenarioHash_trailingZeros_keepHash()              // coverage 5 vs 5.0
@Test void optimize_scenarioWithTent_passesTentToTheOptimizer()     // ArgumentCaptor on SpendingOptimizer
@Test void optimize_persistsTheCertifiedTentTarget()
@Test void getGuardrailProfile_storedTarget_isMappedToTheResponse()
// ScenarioCrudServiceTest
@Test void updateScenario_tentChanged_marksProfileStale()
@Test void updateScenario_noTentAndNothingChanged_keepsProfileFresh()
// persistence repository @DataJpaTest
@Test void save_bondTentTarget_roundTrips()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test-compile`
Expected: compilation FAILS. (The pinned-hash test is written first against the current code and passes. It guards the next step.)

- [ ] **Step 3: Implement the migration, the entity field and the service changes**

- [ ] **Step 4: Run the gates**

Run:
```
cd backend && mvn -q -T1 -pl wealthview-persistence -am verify
cd backend && mvn -q -T1 -pl wealthview-api -am verify -DskipITs
cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true
```
Expected: BUILD SUCCESS for all three.

- [ ] **Step 5: Commit**

Commit V084 and the entity field first, with subject `db(persistence): store the bond tent a guardrail profile was certified with`. Then commit the service, with subject `feat(core): hash the bond tent into the scenario signature and pass it to the optimizer`.

---

### Task 7: Convert existing cash reserves into bond tents

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V085__convert_cash_reserves_to_bond_tents.sql`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/BondTentConfig.java`
- Test:
  - `backend/wealthview-app/src/test/java/com/wealthview/app/it/projection/CashReserveConversionIT.java`
  - `BondTentConfigTest.java` and `GuardrailProfileServiceTest.java` in core, extended.

**Interfaces:**
- Consumes: Task 6's signature; plan 3's `ScenarioParams` and `ScenarioParamsValidator.validateBondTent`.
- Produces:
  - `BondTentConfig` gains `@Nullable Boolean convertedFromCashReserve`, last, written as `converted_from_cash_reserve`. A 4-argument back-compat constructor passes `null`. The validator, both engines and the signature ignore it.
  - **V085, idempotent, with a header comment.** One statement converts and marks stale: a CTE that `UPDATE`s `projection_scenarios ... RETURNING id`, followed by an `UPDATE guardrail_spending_profiles SET is_stale = true WHERE scenario_id IN (...)`.
    - Which scenarios convert: those per Builder's call 11.
    - What it writes: `params_json = COALESCE(params_json, '{}'::jsonb) || jsonb_build_object('bond_tent', ...)`, plus `updated_at = now()`.
    - The tent object:
      ```json
      {"coverage_years": LEAST(cash_reserve_years, 10), "build_up_start_years": 0,
       "mixes": [{"name": "Cash reserve", "shares": {"ultrashort": 1, "short": 0, "intermediate": 0, "long": 0}}],
       "yields": {"ultrashort": 0.0394, "short": 0.0465, "intermediate": 0.048, "long": 0.0539},
       "converted_from_cash_reserve": true}
      ```
    - Then `ALTER TABLE guardrail_spending_profiles ALTER COLUMN cash_reserve_years SET DEFAULT 0;`.

- [ ] **Step 1: Write the failing tests**

`CashReserveConversionIT`:
- It extends the app's IT base class.
- It inserts fixtures through the repositories. `cash_reserve_years` is no longer mapped, so it sets that column with `JdbcTemplate`.
- It runs the V085 script from the classpath with `ScriptUtils.executeSqlScript`.
- It reads `params_json` back with `JdbcTemplate`.

```java
@Test void convert_reserveScenario_writesCompleteValidTent()
    // profile reserve 2, active guardrail plan -> ScenarioParams.parseOrEmpty(...).bondTent():
    // coverage 2, build-up 0, one mix "Cash reserve" 100% ultrashort, the four yields, marker true;
    // ScenarioParamsValidator.validateBondTent(config, null) does not throw; the profile is stale
@Test void convert_reserveAboveTen_capsCoverageAtTen()        // reserve 12 -> coverage 10
@Test void convert_zeroReserve_leavesScenarioUntouched()
@Test void convert_existingTent_isNotOverwritten()
@Test void convert_noActivePlan_leavesScenarioUntouched()
@Test void convert_otherParams_arePreserved()                  // e.g. birth_year and include_depression_years survive unchanged
@Test void convert_runTwice_changesNothingTheSecondTime()      // params_json and is_stale identical after a second run
// BondTentConfigTest
@Test void toJson_convertedMarker_writesSnakeCaseKey()
@Test void toJson_noMarker_omitsTheKey()
// GuardrailProfileServiceTest
@Test void computeScenarioHash_convertedMarker_keepsHash()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true`
Expected: FAIL, because the V085 script does not exist.

- [ ] **Step 3: Write V085 and add the `BondTentConfig` component**

- [ ] **Step 4: Run the gates**

Run:
```
cd backend && mvn -q -T1 -pl wealthview-persistence -am verify
cd backend && mvn -q -T1 -pl wealthview-api -am verify -DskipITs
cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true
```
Expected: BUILD SUCCESS for all three. Flyway applies V084 and V085 on a fresh database.

- [ ] **Step 5: Commit**

Subject: `db(persistence): convert existing cash reserves into 100% ultrashort bond tents`. The body lists:
- the conversion rules (Builder's call 11);
- what is not carried over;
- why the flag stays as stored;
- the default change.

---

### Task 8: Frontend and docs

**Files:**
- Modify:
  - `frontend/src/types/projection.ts`
  - `frontend/src/utils/optimizerConfig.ts` and `optimizerConfig.test.ts`
  - `frontend/src/pages/SpendingOptimizerPage.tsx` and `.test.tsx`
  - `frontend/src/pages/SpendingProfilesPage.tsx` and `.test.tsx`
  - `frontend/src/components/scenario/ScenarioTentSection.tsx` and `.test.tsx`
  - `frontend/src/components/ScenarioForm.tsx` and `.test.tsx`
  - `frontend/src/testutil/builders.ts`
- Docs: `docs/user-guide/spending-and-income.md`, `docs/user-guide/retirement-projections.md`, `docs/MCDetails.md`, `CHANGELOG.md`.

**Interfaces:**
- Consumes: `bond_tent_target` (Task 5/6) and `bond_tent.converted_from_cash_reserve` (Task 7).
- Produces:
  - Types:
    - `GuardrailProfileResponse` loses `cash_reserve_years`/`cash_return_rate` and gains `bond_tent_target: number | null`.
    - `GuardrailOptimizationRequest` loses both cash fields.
    - `BondTentConfig` gains `converted_from_cash_reserve?: boolean`.
  - `OptimizerConfig` loses `cashReserveYears`/`cashReturnRatePct`.

**Behavior and copy (exact strings).** `{N}` is `coverage_years` as a plain number ("2", "2.5"); `{money}` is the existing whole-currency formatter.
- **Optimizer page, Advanced Settings.** The Cash Reserve and Cash Rate fields are gone. In their place, show:
  > Cash reserve has moved. A cash reserve is the simplest kind of bond tent: a few years of spending held in a money market fund. Set it up under **Bond tent** in your scenario.

  "your scenario" links to `/projections/{id}`.
- **Converted notice.** When the scenario's `bond_tent.converted_from_cash_reserve` is true, show this instead of the moved note, and also at the top of the scenario form's Bond tent section:
  > Your {N}-year cash reserve is now a {N}-year bond tent, held entirely in ultrashort Treasuries (the same thing as a money market fund). It works the same way with one difference: once spent, it isn't refilled, so results may shift slightly from before.
- **Tent line.** When the scenario has a tent, the optimizer page shows, next to the run settings: "Includes your scenario's bond tent: {N} years of spending, {plan mix name} mix."
- **Results.** When `bond_tent_target` is non-null, the results show: "Certified with a {money} bond tent."
- **Spending profiles page.** The "Cash Buffer" tile becomes "Bond Tent", with value `{money(bond_tent_target)}`, or "None" when null.
- **Scenario form submit.** It sends `bond_tent` without `converted_from_cash_reserve`.

**Docs.**
- `spending-and-income.md`:
  - remove the Cash Reserve and Cash Rate rows;
  - add the moved note;
  - Cash Buffer becomes Bond Tent in the tile list.
- `retirement-projections.md`: replace the plan-3 sentence "The Monte Carlo simulation and the spending optimizer do not yet take a tent into account…" with:
  - how the simulation builds the tent on each market path (contributions only below the high);
  - how it draws the tent;
  - that the optimizer re-sizes it for each spending level and records the certified size, which can differ a little from the form's size because the optimizer estimates withdrawal tax;
  - the cash-reserve conversion;
  - the known gap: the Roth conversion optimizer ignores a tent held in an IRA.
- `MCDetails.md`: the cash-reserve line at :66 becomes the tent, and add a short "Bond tent" section covering the paths, trigger, settlement and sizing.
- `CHANGELOG.md` [Unreleased]:
  - **Added:** Monte Carlo and the optimizer model the tent.
  - **Changed:**
    - the optimizer's cash reserve has been replaced by the bond tent; existing reserves convert, with the four expected differences from spec §4.3 rule 8, including the end of the refill-on-any-up-year behavior (roadmap D8);
    - converted profiles are marked out of date;
    - the known gap.
  - **Upgrade notes:** V084 and V085 apply on start.
  - Remove the plan-3 "ignore a configured tent until a later release" clause.

- [ ] **Step 1: Write the failing tests** (Vitest + React Testing Library)

`SpendingOptimizerPage.test.tsx`:
- `shows the cash-reserve moved note instead of the reserve controls`: no "Cash Reserve" or "Cash Rate" label.
- `shows the converted notice for a converted scenario`
- `shows the tent line when the scenario has a tent`
- `sends no cash reserve fields in the optimize request`
- `shows the certified tent size in the results`

`SpendingProfilesPage.test.tsx`:
- `shows the certified bond tent tile`
- `shows None without a tent`

`optimizerConfig.test.ts`:
- Rewrite the cash-reserve cases: the config and the request carry no cash fields.

`ScenarioTentSection.test.tsx`:
- `shows the converted notice when the tent came from a cash reserve`

`ScenarioForm.test.tsx`:
- `submit omits converted_from_cash_reserve`: load `params_json` with the marker, submit, and assert the payload's `bond_tent` lacks the key and keeps every other field.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npm run test -- SpendingOptimizerPage SpendingProfilesPage optimizerConfig ScenarioTentSection ScenarioForm`
Expected: FAIL.

- [ ] **Step 3: Implement the UI changes and the docs**

- [ ] **Step 4: Run the gates**

Run: `cd frontend && npm run test:coverage && npm run typecheck && npm run lint`
Expected: all pass, with coverage floors held.

Also run `npm run typecheck:all` from the repo root. Mobile must not reference the removed fields.

- [ ] **Step 5: Commit**

Commit the UI first, with subject `feat(frontend): replace the optimizer cash reserve with the bond tent`. Then commit the docs, with subject `docs: document the Monte Carlo bond tent and the cash-reserve conversion`.
