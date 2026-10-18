# Bond Tent Plan 1 — Monte Carlo Trials Start Today — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every Monte Carlo trial starts in the base year (today), so each trial reaches retirement with its own market-driven balances instead of one shared expected balance.

**Architecture:**
- `PortfolioPathGenerator.generate` gains a `preRetirementYears` prefix. Each trial draws one block-bootstrap index sequence of length `preRetirementYears + years`.
  - The prefix grows every account per trial: contributions first, then growth.
  - The rest drives the existing retirement-anchored return arrays.
- Each trial's balances at retirement are summarized as a `TrialStartBalances`, which is carried on `PortfolioReturnPaths` and `SimulationParameters`.
- `TrialConfigFactory.configFor` is the single place each trial's starting pools, spouse slices and taxable lot values are set from it.
- The deterministic expected-path seeding (`PreRetirementAccumulation.seedAtRetirement`) stays in place for every scalar that is shared across trials.
- With zero pre-retirement years, the old code path runs unchanged.

**Tech Stack:** Java 25 / Spring Boot 4.1, Maven multi-module, JUnit 5 + AssertJ, Flyway, PostgreSQL 16; React 19 / TypeScript / Vitest.

**Spec:** `docs/superpowers/specs/2026-10-18-bond-tent-design.md` (§2, §4.4 "Monte Carlo", §5 tests, §8). This is plan 1 of 4.
- Plan 2: bond maturity data.
- Plan 3: tent model, engines, optimizer and cash-reserve conversion.
- Plan 4: replays, fixed-spending evaluation and the Bond tent tab.

## Global Constraints

**Engine behavior**
- `preRetirementYears = max(0, retirementYear − baseYear)`. A retirement year at or before the base year is "retiring now".
- **Zero pre-retirement years is byte-identical to today.** `MonteCarloSpendingOptimizerCharacterizationTest` must pass with no edits. `PortfolioReturnPaths.startBalances` and `SimulationParameters.startBalances` are `null` exactly when `preRetirementYears == 0`.
- **Per-trial accumulation, per account, per pre-retirement year:**
  `balance = max(0, (balance + annualContribution) × (1 + accountReal[y] − feeRate))`
  - `accountReal` is the account's fixed override real return when override-based (no dispersion). Otherwise it is its allocation blended against the matrix row for that year's index.
  - This matches `PreRetirementAccumulation`'s order and fee treatment, plus a floor at zero.
- **Pre-retirement trial years model contributions, market growth and fees only.** No income tax, Roth conversions, RMDs or deaths before retirement (spec §4.4, "my call").
- **Taxable cost basis stays the expected scalar in every trial:** initial basis + contributions × `preRetirementYears`. Only the taxable *value* varies per trial.
- **Expected-path scalars still come from `seedAtRetirement`:**
  - `PortfolioSetup` / `TaxContext` balances and their pool gating (`simPools`, the `taxCtx` null check, `JointConversionSearch`'s `initTraditional() <= 0`).
  - `RothConversionOptimizer` / `ConversionSimulator` inputs.
  - `resolveReturnMean`.
  - `taxableEquityShare`.
  - `HouseholdMcResolver`'s identity fields and taxable *bases*.
- **Unchanged RNG streams:** the mortality stream (`seed + MORTALITY_SEED_OFFSET`) and the joint-search stream (`seed + 1`) are not touched. `StochasticMortalityGoldenTest`'s death-age distributions and `trialFraction` must not change.
- **Unchanged deterministic engine:** the six deterministic goldens stay byte-identical.

**Code conventions**
- All new MC types are package-private in `com.wealthview.projection` and use `double`.
- Records for value types. No wildcard imports. AssertJ only. Test names follow `method_state_expected`.
- PMD suppressions need an adjacent justifying comment.

**Build gates**
- Run per module, always with `-am`: `cd backend && mvn -q -T1 -pl <module> -am verify -DskipITs`.
- Full-reactor `mvn verify` gets OOM-killed in this sandbox.
- Coverage floors are never lowered.
- Frontend: `npm run test`, `npm run typecheck`, `npm run lint` in `frontend/`.

**Golden files and pins**
- MC pins move only in Task 4, which is the intentional-behavior commit.
- Its commit body lists every moved pin with before and after values.
- A failing *behavioral* invariant (monotonicity, with-rules ≥ no-rules, gate transitions) is a bug to investigate, never a pin to update.

**Commits**
- Commit directly on `main`. Never push.
- Conventional Commits, with a body for feat/fix/refactor/db.
- End each commit with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Retirement this year or already past:** results are identical to before the change. Pinned by Task 2 (zero-prefix deep equality) and Task 4 (`build_retirementBeforeBaseYear_hasNoTrialStartBalances`, plus the untouched characterization test).
2. **A brand-new $0 account funded only by contributions:** it grows in every trial and, at retirement, carries its full weight in its pool's blended return, not a fallback weight. Pinned in Task 2.
3. **Negative contributions** (planned withdrawals before retirement) on a small account in a bad trial: the account never goes below $0. Pinned in Task 2.
4. **A household with spouse-owned and owner-tagged taxable accounts:**
   - Each trial's spouse traditional/Roth slices and owned taxable lot values follow that trial; the bases do not.
   - The stochastic-mortality override path gets the same per-trial values.

   Pinned in Task 3.
5. **A taxable account worth less than its basis after a bad pre-retirement run:** the lot is seeded at a loss, and selling it realizes a negative gain rather than a phantom gain. Pinned in Task 3.

---

### Task 1: `TrialStartBalances` and pool/owner tags on `AccountReturnSource`

**Files:**
- Create: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialStartBalances.java`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolReturnModel.java`
  - `AccountReturnSource` (~:35) gains two trailing components.
  - `sourceFor` (~:81–90) fills them from `account.poolType()` / `account.ownerType()`.
- Test (create): `backend/wealthview-projection/src/test/java/com/wealthview/projection/TrialStartBalancesTest.java`

**Interfaces:**
- Consumes: `PoolType`, `LotOwner` (`com.wealthview.core.projection.dto`).
- Produces:
  - `record AccountReturnSource(double balance, double contribution, boolean overrideBased, double overrideReal, AssetAllocation allocation, PoolType poolType, LotOwner owner)`
  - `record TrialStartBalances(double taxable, double traditional, double roth, double traditionalSpouse, double rothSpouse, double taxableJoint, double taxablePrimary, double taxableSpouse)`
  - `double TrialStartBalances.total()`: `taxable + traditional + roth`, summed in that order.
  - `static TrialStartBalances TrialStartBalances.of(List<AccountReturnSource> accounts, double[] balances)`:
    - `balances[i]` belongs to `accounts.get(i)`.
    - `traditional` and `roth` sum all owners.
    - `*Spouse` sums only `LotOwner.SPOUSE`.
    - `taxableJoint` / `taxablePrimary` / `taxableSpouse` split `TAXABLE` by owner.
    - Throws `IllegalArgumentException` when the lengths differ.
  - `static double TrialStartBalances.medianTotal(TrialStartBalances[] starts)`:
    - Median of `total()` over a sorted copy; an even count averages the two middle values.
    - Throws `IllegalArgumentException` when empty.

- [ ] **Step 1: Write the failing tests**

  Build the sources through `PoolReturnModel.from(accounts, 0.0).allAccounts()`, using `HypotheticalAccountInput`'s 7-argument constructor so owners can be set.

  ```java
  @Test
  void of_mixedPoolsAndOwners_sumsEachPoolAndOwnerSlice() {
      // taxable joint 100, taxable spouse 50, traditional primary 200, traditional spouse 80, roth spouse 30
      var start = TrialStartBalances.of(sources, new double[]{100, 50, 200, 80, 30});
      assertThat(start.taxable()).isEqualTo(150);
      assertThat(start.traditional()).isEqualTo(280);
      assertThat(start.roth()).isEqualTo(30);
      assertThat(start.traditionalSpouse()).isEqualTo(80);
      assertThat(start.rothSpouse()).isEqualTo(30);
      assertThat(start.taxableJoint()).isEqualTo(100);
      assertThat(start.taxablePrimary()).isZero();
      assertThat(start.taxableSpouse()).isEqualTo(50);
      assertThat(start.total()).isEqualTo(460);
  }
  ```

  Also add:
  - `of_lengthMismatch_throwsIllegalArgument`.
  - `medianTotal_oddCount_returnsMiddleValue`: totals 300, 100, 200 → `200`.
  - `medianTotal_evenCount_averagesMiddleTwo`: totals 100, 400, 200, 300 → `250`.
  - `medianTotal_empty_throwsIllegalArgument`.

- [ ] **Step 2: Run the tests and confirm they fail**

  Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=TrialStartBalancesTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: compilation failure (`TrialStartBalances` does not exist).

- [ ] **Step 3: Implement `TrialStartBalances` and the two new `AccountReturnSource` components**

- [ ] **Step 4: Run the tests and confirm they pass**

  Same command. Expected: PASS. `PortfolioPathGeneratorTest` also still passes.

- [ ] **Step 5: Commit** — `feat(projection): per-trial retirement start balances value type`

---

### Task 2: Pre-retirement prefix in `PortfolioPathGenerator`

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PortfolioReturnPaths.java`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PortfolioPathGenerator.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/PortfolioPathGeneratorTest.java` (extend)

**Interfaces:**
- Consumes: `TrialStartBalances.of`, `AccountReturnSource` (Task 1).
- Produces:
  - `record PortfolioReturnPaths(double[][] taxableReturns, double[][] traditionalReturns, double[][] rothReturns, double[][] portfolioPaths, @Nullable TrialStartBalances[] startBalances)`, plus a 4-argument constructor that passes `startBalances = null`. Existing hand-built test fixtures keep compiling.
  - `static PortfolioReturnPaths generate(int trialCount, int preRetirementYears, int years, PoolReturnModel model, RealReturnMatrix matrix, Random rng, double feeRate)`:
    - Throws `IllegalArgumentException` when `preRetirementYears < 0`.
    - The existing 6-argument `generate` delegates with `preRetirementYears = 0`.

- [ ] **Step 1: Write the failing tests** (in `PortfolioPathGeneratorTest`, reusing `FLAT_US_MATRIX` at +10% real and `TEST_CMA_MATRIX`)

  - `generate_zeroPreRetirementYears_identicalToSixArgOverload`:
    - Call both overloads with `new Random(7L)`.
    - All four arrays are deep-equal (`isDeepEqualTo`).
    - `startBalances()` is `null`.
  - `generate_preRetirementYears_contributesBeforeGrowthEachYear`:
    - ALL_US account, $100,000 balance, $10,000/yr contribution, `pre = 2`, fee 0.
    - For every trial, `startBalances()[t].taxable()` is `isEqualTo(144_100.0, within(1e-6))`.
    - `portfolioPaths()[t][0]` equals the same value.
  - `generate_preRetirementYears_feeReducesEachYearsGrowth`: same account with fee `0.01` → `141_591.0`.
  - `generate_preRetirementYears_overrideAccount_sameStartInEveryTrial`:
    - 5% nominal override at zero inflation, $100,000, no contribution, `pre = 3`.
    - `115_762.5` in every trial, on `TEST_CMA_MATRIX`.
  - `generate_preRetirementYears_dispersedMatrix_startBalancesVaryAcrossTrials`:
    - ALL_US, `TEST_CMA_MATRIX`, 200 trials, `pre = 10`.
    - The minimum start total is strictly below the maximum.
  - `generate_preRetirementYears_zeroOpeningBalanceWithContributions_growsPerTrial`:
    - $0 balance, $5,000/yr, `FLAT_US_MATRIX`, `pre = 2`.
    - Start `taxable()` = `11_550.0`.
    - Every retirement-year `taxableReturns()[t][y]` = `0.10`.
  - `generate_preRetirementYears_negativeContribution_neverBelowZero`:
    - $1,000 balance, −$5,000/yr, `pre = 3`.
    - Start `taxable()` is `0.0` and `portfolioPaths()[t][0]` is `0.0`.
  - `generate_preRetirementYears_poolBlendWeightsUseTrialRetirementBalances`:
    - Two taxable accounts:
      - A: 0% nominal override, $100,000 balance, no contribution.
      - B: ALL_US, $0 balance, $100,000/yr contribution.
    - `FLAT_US_MATRIX`, `pre = 1`.
    - Retirement-year taxable return = `11_000.0 / 210_000.0` (`within(1e-12)`). Weighting by opening balances would give `0.0`.
  - `generate_negativePreRetirementYears_throwsIllegalArgument`.

- [ ] **Step 2: Run the tests and confirm they fail**

  Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=PortfolioPathGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: compilation failure (no 7-argument `generate`).

- [ ] **Step 3: Implement the prefix**

  When `preRetirementYears == 0`, run today's code exactly; `startBalances` is `null`. Otherwise, per trial:

  ```
  seq     = bootstrap.generateIndexSequence(pre + years, historicalSize)   // one draw per trial, same stream
  preSeq  = seq[0, pre);  retSeq = seq[pre, pre + years)
  for each account i in model.allAccounts():
      r = overrideBased ? fixed(pre, overrideReal) : resolveReal(preSeq, allocation, matrix)
      b = balance
      for y in [0, pre): b = max(0, (b + contribution) * (1 + r[y] - feeRate))
      bal[i] = b
  start[t] = TrialStartBalances.of(model.allAccounts(), bal)
  ```

  Then compute the retirement returns from `retSeq`:
  - Pool and portfolio returns use today's blend rule (balance share, else contribution share, else equal). The balance share now uses each account's `bal[i]` instead of its opening balance.
  - `portfolioPaths[t][0] = start[t].total()`, and the path compounds from there.
  - The private `poolRealReturns` / `blendWeights` helpers take the per-account balances, aligned with the pool's account list, as a parameter.

- [ ] **Step 4: Run the tests and confirm they pass**

  Same command. Expected: PASS, including every pre-existing generator test.

- [ ] **Step 5: Commit** — `feat(projection): bootstrap pre-retirement years per Monte Carlo trial`

---

### Task 3: Per-trial starting state in `TrialConfigFactory` and `HouseholdSim`

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialSimulator.java` (`HouseholdSim`, ~:123–145)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialConfigFactory.java`
- Test (create): `backend/wealthview-projection/src/test/java/com/wealthview/projection/TrialConfigFactoryTest.java`

**Interfaces:**
- Consumes: `TrialStartBalances` (Task 1).
- Produces:
  - `HouseholdSim HouseholdSim.withStartBalances(TrialStartBalances start)`. The copy replaces:
    - `initTraditionalSpouse` with `start.traditionalSpouse()`
    - `initRothSpouse` with `start.rothSpouse()`
    - the three taxable *values* in `taxableSeed`, with `start.taxableJoint()`, `start.taxablePrimary()` and `start.taxableSpouse()`

    The three *bases* and every other field stay unchanged.
  - `TrialConfigFactory.Builder startBalances(@Nullable TrialStartBalances[] startBalances)`
  - `configFor(t, …)` precedence when `startBalances != null`:
    - **Pooled run:** starting taxable / traditional / Roth = `start.taxable()` / `start.traditional()` / `start.roth()`.
    - **Non-pooled run:** starting taxable = `start.total()`, and traditional/Roth stay at the `poolSetup` scalars.
    - **Household:** whichever household is used (the override, else the baked-in one) gets `.withStartBalances(start)`.
    - This takes precedence over `initialPortfolioPaths`.
  - When `startBalances` is `null`, behavior is unchanged.

- [ ] **Step 1: Write the failing tests**

  Use a minimal factory: `TrialConfigFactory.builder(new PoolSimSetup(…))`, two trials of zero-return arrays through `.returns(…)`, `.ages(65, 73, 60)`, and `.taxableBasis(100_000)`.

  - `configFor_noStartBalances_usesPoolSetupScalars`: with `PoolSimSetup(100_000, 50_000, 25_000, "taxable_first", true)`, the config carries exactly those three values.
  - `configFor_startBalancesWithPools_usesEachTrialsBalances`: trials 0 and 1 get different `TrialStartBalances`, and each config carries its own trial's taxable/traditional/Roth.
  - `configFor_startBalancesWithoutPools_taxableIsTrialTotal`: `simPools = false`, so `initTaxable()` == `start.total()`.
  - `configFor_household_spouseSlicesAndLotValuesFollowTrial_basesUnchanged`: a `HouseholdSim` with `TaxableSeed(10, 20, 30, 40, 50, 60)`.
    - The config's household has the trial's spouse slices and values.
    - The bases are still `10/30/50`.
  - `configFor_householdOverride_alsoReceivesTrialStartBalances`: pass a `withTrialMortality(…)` override, and the trial's spouse slices are applied to it.
  - `configFor_taxableBelowBasis_seedsLotWithUnrealizedLoss`:
    - Trial start taxable `80_000` with basis `100_000`.
    - `TrialPools.seed(config, null).sellTaxable(80_000)` is `isEqualTo(-20_000.0, within(1e-6))`.

- [ ] **Step 2: Run the tests and confirm they fail**

  Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=TrialConfigFactoryTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: compilation failure (`startBalances` and `withStartBalances` do not exist).

- [ ] **Step 3: Implement `withStartBalances`, the builder field and the `configFor` precedence**

- [ ] **Step 4: Run the tests and confirm they pass**

  Same command. Expected: PASS.

- [ ] **Step 5: Commit** — `feat(projection): seed each trial's pools from its own retirement balances`

---

### Task 4: Wire trials-start-today through the optimizer (intentional behavior change)

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/SimulationParameters.java`
  - Append the components `@Nullable TrialStartBalances[] startBalances, int preRetirementYears, PoolReturnModel returnModel`.
  - `emptyHorizon` passes `null, 0, null`.
- Modify: `…/OptimizationContextBuilder.java`, `build` (~:76–221).
- Modify: `…/MonteCarloSpendingOptimizer.java`:
  - `optimizeInternal` (~:157–160)
  - `evaluateStochasticMortality` (~:269–270)
  - `searchContextFor` (~:356)
- Modify: `…/SustainabilitySearch.java`, `buildConfigFactory` (~:476) and `initialTotalBalance` (~:544).
- Modify: `…/GuardrailResponseBuilder.java`, `buildConfigFactory` (~:214) and `computeSuccessProbabilityWithRules` (~:321–323).
- Modify: `…/StochasticMortalityEvaluator.java`, factory build (~:63).
- Modify: `…/JointConversionSearch.java`, search paths (~:104–106).
- Modify javadoc only: `PreRetirementAccumulation.java` (class doc) and `HouseholdMcResolver.java` (~:32–41). They now describe the expected-path scalar role and say that pre-retirement deaths are still not modeled.
- Test: `…/OptimizationContextBuilderTest.java` (extend), `…/PreRetirementAccumulationTest.java` (extend), `…/SustainabilitySearchTest.java` (constructor arity plus its `paths[t][0]` invariant comment ~:64), and the re-pinned tests listed in Step 5.

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `OptimizationSetup OptimizationContextBuilder.build(GuardrailOptimizationInput expectedAtRetirement, List<? extends ProjectionAccountInput> todayAccounts, RealReturnMatrix matrix)`:
    - Every scalar comes from `expectedAtRetirement`, as today.
    - The return model is `PoolReturnModel.from(todayAccounts, inflationRate)`.
    - Paths come from the 7-argument `generate` with `max(0, retirementYear − baseYear)`.
    - `SimulationParameters` gets `startBalances`, `preRetirementYears` and that `returnModel`.
  - `OptimizationSetup OptimizationContextBuilder.build(GuardrailOptimizationInput request, RealReturnMatrix matrix)` now means "request as of today" and returns `build(PreRetirementAccumulation.seedAtRetirement(request, matrix), request.accounts(), matrix)`.
  - Both optimizer entry points call the 3-argument `build(seeded, request.accounts(), matrix)`. `searchContextFor` passes `ctx.sim().startBalances()` into `PortfolioReturnPaths`.
  - The three config-factory builders call `.startBalances(...)`: `SustainabilitySearch` from `ctx.returnPaths()`, and the others from `ctx.sim()`.
  - **Year-0 guardrail baseline (`expectedStartBalance[0]`):** when start balances exist, it is `TrialStartBalances.medianTotal(...)`, in both `SustainabilitySearch.initialTotalBalance` and `GuardrailResponseBuilder`. Otherwise it stays today's scalar.
  - **`JointConversionSearch`:** generates its search paths with `ctx.sim().preRetirementYears()` and `ctx.sim().returnModel()`. Its `TaxContext` and conversion inputs keep the expected scalars.

- [ ] **Step 1: Write the failing tests**

  - `OptimizationContextBuilderTest.build_retirementInBaseYear_hasNoTrialStartBalances`: `startBalances()` is `null` and `preRetirementYears()` is `0`.
  - `OptimizationContextBuilderTest.build_retirementBeforeBaseYear_hasNoTrialStartBalances`: base year 2032, retirement 2030 → `null` and `0`.
  - `OptimizationContextBuilderTest.build_retirementTenYearsOut_eachTrialStartsFromItsOwnRetirementBalance`:
    - ALL_US account on `TEST_CMA_MATRIX`, base year = retirement − 10.
    - `startBalances()` has length `trialCount`.
    - For every `t`, `portfolioPaths()[t][0]` == `startBalances()[t].total()`.
    - The minimum total is less than the maximum.
  - `OptimizationContextBuilderTest.build_requestOverload_expectedScalarsAreSeededAtRetirement`: `ctx.portfolio().initTaxable()` equals the taxable sum of `PreRetirementAccumulation.seedAtRetirement(request, matrix).accounts()`.
  - `PreRetirementAccumulationTest.optimize_retirementYearsAway_firstRetirementYearShowsPreRetirementDispersion`:
    - Run (a): an ALL_US taxable account on `TEST_CMA_MATRIX`, base year = retirement − 15, fixed seed.
    - Run (b): the same seed, with accounts = `seedAtRetirement(a).accounts()` and base year = retirement year.
    - Assert that the first yearly row's `portfolioBalanceP10 / portfolioBalanceMedian` is strictly lower for (a) than for (b).

- [ ] **Step 2: Run the new tests and confirm they fail**

  Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='OptimizationContextBuilderTest,PreRetirementAccumulationTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: compilation failure or assertion failures on the new tests.

- [ ] **Step 3: Implement the wiring listed under Interfaces**

- [ ] **Step 4: Check the byte-identity anchor**

  Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=MonteCarloSpendingOptimizerCharacterizationTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS with the test file unmodified. If it fails, the zero-prefix path is not identical. Fix the code, not the test.

- [ ] **Step 5: Run the module, then re-pin only intentional moves**

  Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test`

  Expected movers are tests with base year < retirement year:
  - `StochasticMortalityGoldenTest`: only the success figures `lifetimeSuccessProbability`, `longevityConditional.probability` and `fixedDeathSuccess` move.
  - `StochasticMortalityEvaluatorTest`.
  - `GuardrailHouseholdReportingTest`.
  - `MonteCarloHouseholdSmokeTest`.
  - `GuardrailHouseholdSimulatedScheduleTest`.
  - `OptimizationContextBuilderTest` (base years 2020 and the parameterized one).

  Rules:
  - Update a numeric pin to the new value only if it is a simulated result.
  - The death-age distributions and `trialFraction` must not move. If they do, the mortality stream was disturbed: fix the code.
  - A failing monotonicity or gate assertion is a bug: fix the code.

  Record every moved pin's before and after values for the commit body.

- [ ] **Step 6: Run the module gates**

  Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
  Expected: BUILD SUCCESS (PMD, CPD, SpotBugs, Checkstyle, JaCoCo).

  Then run the app integration tests: `cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true`
  Expected: BUILD SUCCESS. `GuardrailControllerIT` runs the real optimizer end to end.

- [ ] **Step 7: Commit** — `feat(projection): start Monte Carlo trials in the base year`

  The body explains:
  - What changed: per-trial pre-retirement markets, expected-path scalars kept, and the median year-0 guardrail baseline.
  - That zero pre-retirement years are byte-identical.
  - The before and after values of every moved pin.

---

### Task 5: Flag stored profiles stale, explain it, document it

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V082__mark_guardrail_profiles_stale_for_trials_start_today.sql`
- Modify: `frontend/src/components/OptimizerResultsView.tsx` (stale banner copy, ~:57)
- Test: `frontend/src/components/OptimizerResultsView.test.tsx` (~:83, the banner text matcher)
- Modify: `docs/user-guide/spending-and-income.md`
  - The Stale badge paragraph (~:379).
  - One sentence in "Starting an Optimization" (~:244).
- Modify: `CHANGELOG.md`, `## [Unreleased]`.

**Interfaces:**
- Consumes: Task 4's behavior.
- Produces: nothing that code consumes.

- [ ] **Step 1: Write the migration**

  ```sql
  -- V082: Monte Carlo trials now start in the base year (today) instead of at retirement.
  -- Every stored guardrail profile was optimized under the old model, so flag it stale and let
  -- the user re-optimize from the UI. Flag only: no stored figures change. Idempotent.
  UPDATE guardrail_spending_profiles SET is_stale = true WHERE is_stale = false;
  ```

- [ ] **Step 2: Verify the migration applies**

  Run: `cd backend && mvn -q -T1 -pl wealthview-persistence -am verify`
  Expected: BUILD SUCCESS. The Testcontainers tests apply every migration through V082.

- [ ] **Step 3: Commit** — `db(persistence): flag guardrail profiles stale for the trial-start change`

  The body says why, and that only the flag changes.

- [ ] **Step 4: Update the banner test to the new copy, then run it and watch it fail**

  - The matcher becomes `screen.getByText(/profile is out of date/i)`.
  - Run: `cd frontend && npx vitest run src/components/OptimizerResultsView.test.tsx`. Expected: FAIL.

- [ ] **Step 5: Change the banner copy**

  New copy, exactly: `This profile is out of date — your scenario or WealthView's simulation model has changed since it was optimized.`

  Run the same command. Expected: PASS. Then run `npm run typecheck` and `npm run lint`; both pass.

- [ ] **Step 6: Update the docs and the CHANGELOG**

  - **`spending-and-income.md`, "Starting an Optimization":** add *"Each simulated market history starts today: your balances and contributions grow through simulated markets until you retire, so a downturn in the years before retirement is part of the result."*
  - **`spending-and-income.md`, Stale paragraph:** the badge also appears after WealthView changes how it simulates.
  - **`CHANGELOG.md`, `[Unreleased]` → `### Changed`:**
    - Monte Carlo simulations now start today rather than at retirement, for every scenario.
    - Success rates and recommended spending may move, especially when retirement is years away.
    - Existing optimized profiles are marked out of date. Re-optimize to refresh them.
    - Scenarios retiring this year are unaffected.

- [ ] **Step 7: Commit** — `feat(frontend): explain model-change staleness; document trials starting today`
