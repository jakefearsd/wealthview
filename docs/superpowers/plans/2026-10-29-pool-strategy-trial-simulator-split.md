# PoolStrategy / TrialSimulator Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Break `PoolStrategy.java` (2,029 lines) and `TrialSimulator.java` (1,214 lines) in `wealthview-projection` into focused files without changing a single projected number.

**Architecture:** Pure structural refactor, done as moves plus one real extraction:
- **Moves:** nested types and static helpers go to top-level package-private files in `com.wealthview.projection`, and call sites are re-pointed.
- **Real extraction:** MultiPool's withdrawal tax-bill fixed-point math becomes its own class, `WithdrawalTaxBillSolver`, with a direct unit test.

The deterministic engine stays `BigDecimal` and the Monte Carlo engine stays `double`; that split is intentional and must not be touched. Expected sizes afterward:

| File | Before | After (about) |
|---|---|---|
| `PoolStrategy.java` | 2,029 | 550 |
| `MultiPool.java` | (nested) | 700 |
| `TrialSimulator.java` | 1,214 | 500 |

**Tech Stack:** Java 25, Maven, JUnit 5, AssertJ, Mockito; PMD/CPD/SpotBugs/Checkstyle/JaCoCo gates.

**Spec:** None. This is a behavior-preserving refactor requested by the owner on 2026-10-29. The binding spec is the existing behavior, pinned by:
- the golden files under `backend/wealthview-projection/src/test/resources/golden/`;
- `ProjectionGoldenFileTest`, `BondTentGoldenFileTest`, `BondTentReplayGoldenFileTest`;
- `PoolStrategyCharacterizationTest`, `DeterministicProjectionEngineCharacterizationTest`, `MonteCarloSpendingOptimizerCharacterizationTest`, `MonteCarloBondTentCharacterizationTest`, `RothConversionOptimizerCharacterizationTest`.

All of these live in `wealthview-projection`.

## Global Constraints

- **No numeric change.** Every golden file stays byte-identical. Never pass `-Dupdate.golden`. After each task, `git status --porcelain backend/wealthview-projection/src/test/resources/golden` must print nothing.
- **Don't weaken tests.** No existing test assertion may be weakened or deleted. Test changes are limited to:
  - re-pointing references to moved types or methods;
  - renames done with `git mv`;
  - the new tests this plan names.
- **New files** are package-private (no `public`) in `com.wealthview.projection`, under `backend/wealthview-projection/src/main/java/com/wealthview/projection/`.
- **Static-only classes** use the existing idiom: `final class X { private X() { } ... }` (see `PoolTaxCascade.java`).
- **Move code, don't copy it.** CPD fails on duplicates, and CPD findings must never be suppressed.
- **Suppressions travel with their code**, comment included. Don't add new `@SuppressWarnings` unless a gate demands it; any new one needs a justifying comment.
- **Fix every `{@link}` / `{@see}`** a move breaks, by re-pointing it to the new owner. `{@code}` prose that names an old location is updated to the new class name.
- **Java style:** 120-column lines, no wildcard imports, import order `java.*`, `jakarta.*`, `org.*`, `com.*`, static imports last.
- **Verification command** (run from the repo root):
  ```
  cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs
  ```
  It must exit 0. This runs every unit, golden and characterization test plus all five gates.
  - Never run a full-reactor `mvn verify`; it gets killed for memory.
  - `-am` is required.
- **Git:**
  - Commit on `main`. No branches, no worktrees, no `git push`.
  - Use Conventional Commits with scope `projection` and a mandatory body for `refactor`.
  - End every commit with the trailer `Co-Authored-By: <implementing model name> <noreply@anthropic.com>`.
  - Never `git add` `.superpowers/`.

## Review Focus

1. **Order of the recorded tax breakdown.** Before the move, `lastTaxBreakdown` is overwritten deep inside the bill computation, and only by calls that meet the "record" condition. After the move it must hold the same value: the last breakdown recorded anywhere during that settle, or stay unchanged if nothing was recorded.
   - Pinned by Task 4's `settle_recordsLastBreakdownOnlyWhenIncomeOrTax` and `settle_nothingRecordedWhenNoIncomeAndNoTax`.
   - Also pinned by `executeWithdrawals_zeroBillAfterConversion_keepsConversionBreakdown` in `MultiPoolDeepTest`.
2. **Filing status after the first death.** `applyFirstDeathTransition` flips MultiPool's filing status to SINGLE mid-projection, and the extracted solver must use the flipped value. Filing status is passed per call, never captured at construction.
   - Pinned by Task 4's `settle_usesFilingStatusFromInputs`.
   - Also pinned by `executeWithdrawals_afterFirstDeath_billUsesSingleFiling` in `MultiPoolOwnerTest`.
3. **Early-withdrawal penalty edge.** The penalty applies at age < early-access age, not ≤. Pinned by Task 4's penalty pair (55 vs 60, and 60 vs 60).
4. **Tax-sale gain short-circuit.** When the funding sale has no embedded gain (the peek returns |gain| < $1), the solver returns the gain-0 bill after exactly one peek. Pinned by Task 4's `settle_noEmbeddedGain_returnsGainZeroBillAfterOnePeek`.
5. **Monte Carlo hot loop.** Moving static helpers out of `TrialSimulator` must not add per-year allocations. Task 6 adds none, and the moved records are the same records, so no new object is created per year.

---

### Task 1: Move `PoolConfig` to its own file

**Files:**
- Create: `PoolConfig.java` (the record plus its nested `Builder`, moved verbatim from `PoolStrategy.java:546-728`, including every back-compat constructor and its javadoc)
- Modify: `PoolStrategy.java` (delete the moved block and the `// --- PoolConfig + Factory Method ---` banner)
- Modify: every main and test file that spells `PoolStrategy.PoolConfig` (11 files today)

**Interfaces:**
- Produces: `record PoolConfig(...)` with the same 18 components, the same three back-compat constructors, `static PoolConfig.Builder builder(...)` and `PoolConfig.Builder`. All are unchanged except for the enclosing name.

- [ ] **Step 1:** Create `PoolConfig.java` holding the record moved verbatim, with its own imports. Delete it from `PoolStrategy.java` and drop any `PoolStrategy` import that becomes unused.
- [ ] **Step 2:** Re-point the references:
  ```
  grep -rl 'PoolStrategy\.PoolConfig' backend --include=*.java | xargs sed -i 's/PoolStrategy\.PoolConfig\b/PoolConfig/g'
  ```
  Then confirm that `grep -rn 'PoolStrategy\.PoolConfig' backend --include=*.java` prints nothing.
- [ ] **Step 3:** Run the verification command. Expected: exit 0 and an empty golden status.
- [ ] **Step 4:** Commit with the message `refactor(projection): move PoolConfig out of PoolStrategy`. The body says this is a pure move, with no behavior change, and gives the new line count of `PoolStrategy.java`.

---

### Task 2: Extract the account-return blending statics to `PoolReturnBlender`

**Files:**
- Create: `PoolReturnBlender.java`
- Modify: `PoolStrategy.java`. The blending statics leave, and `create` stays as the factory but delegates to the new class.
- Modify: callers of `PoolStrategy.realReturnFor`, `PoolStrategy.taxableEquityShare`, `PoolStrategy.blendedRealReturn` and `PoolStrategy.poolReturnsFor`, in main and test code.

**Interfaces:**
- Produces, in `final class PoolReturnBlender`. Each item keeps its existing javadoc. Use `PoolStrategy.SCALE` and `PoolStrategy.ROUNDING` and don't redeclare them.
  - `static BigDecimal realReturnFor(ProjectionAccountInput acct, Map<AssetClass, Double> geoMeans, BigDecimal inflationRate, BigDecimal feeRate)`
  - `static BigDecimal taxableEquityShare(List<ProjectionAccountInput> taxableAccounts)`
  - `static BigDecimal blendedRealReturn(List<ProjectionAccountInput> accounts, Map<AssetClass, Double> geoMeans, BigDecimal inflationRate, BigDecimal feeRate)`. This absorbs the body of the old private `computeWeightedReturn`, which was a pure alias. Merge that method's javadoc into this one.
  - `static PoolStrategy.PoolReturns poolReturnsFor(List<ProjectionAccountInput> accounts, Map<AssetClass, Double> assetReturns, BigDecimal inflationRate, BigDecimal feeRate)`
  - Package-private, for `PoolStrategy.create`:
    - `static Map<PoolType, List<ProjectionAccountInput>> groupByPool(List<ProjectionAccountInput> accounts)`
    - `static PoolStrategy.PoolReturns poolReturns(Map<PoolType, List<ProjectionAccountInput>> grouped, BigDecimal household, Map<AssetClass, Double> assetReturns, BigDecimal inflationRate, BigDecimal feeRate)`
  - Private: `sumInitialBalances`, `blendWeights`, `poolWeightedReturn`. Rewrite `poolWeightedReturn` to call `blendedRealReturn`.
- `PoolStrategy.create(List<ProjectionAccountInput>, PoolConfig)` keeps its signature and javadoc. Its body calls `PoolReturnBlender.groupByPool`, `blendedRealReturn` and `poolReturns`, in the same order and with the same arguments as today.
- `sumContribsByOwner` moves into `MultiPool` as a `private static` method. MultiPool is still nested at this point; that is fine.

- [ ] **Step 1:** Create the class, move the methods, and rewrite `create`. Inside MultiPool, change the unqualified `taxableEquityShare(taxableAccounts)` call to `PoolReturnBlender.taxableEquityShare(taxableAccounts)`. The field of the same name stays.
- [ ] **Step 2:** Re-point callers:
  ```
  grep -rlE 'PoolStrategy\.(realReturnFor|taxableEquityShare|blendedRealReturn|poolReturnsFor)\(' backend --include=*.java | xargs sed -i -E 's/PoolStrategy\.(realReturnFor|taxableEquityShare|blendedRealReturn|poolReturnsFor)\(/PoolReturnBlender.\1(/g'
  ```
  If a test class exercises nothing but these four statics, `git mv` it to `PoolReturnBlenderTest.java` and rename the class. Otherwise, leave its name.
- [ ] **Step 3:** `PoolStrategy` now has 4 fewer public members, so try deleting its `@SuppressWarnings("PMD.ExcessivePublicCount")` and the four-line comment above it, then run the verification command.
  - If PMD reports `ExcessivePublicCount` on `PoolStrategy`, restore the suppression. Update the comment's numbers to what PMD now counts.
  - Otherwise, leave it deleted.
- [ ] **Step 4:** Run the verification command. Expected: exit 0 and an empty golden status.
- [ ] **Step 5:** Commit with the message `refactor(projection): extract account-return blending into PoolReturnBlender`. The body explains:
  - what moved;
  - that `computeWeightedReturn` was folded into its alias;
  - whether the suppression was removed.

---

### Task 3: Move `MultiPool` to its own file

**Files:**
- Create: `MultiPool.java`. It holds `final class MultiPool implements PoolStrategy`, moved verbatim from the nested class. That includes:
  - the `MultiPoolMemento` record, still nested in MultiPool;
  - every constant, field and comment;
  - `taxableLotCount()`.
- Modify: `PoolStrategy.java`:
  - The header becomes `sealed interface PoolStrategy permits MultiPool`.
  - `Memento` becomes `sealed interface Memento permits MultiPool.MultiPoolMemento`.
  - Delete the `// --- MultiPool ---` banner.
- Modify: every file that spells `PoolStrategy.MultiPool` (26 today, almost all tests).

**Interfaces:**
- Produces: `final class MultiPool implements PoolStrategy`. Both constructors are unchanged. Nested types of `PoolStrategy` (such as `TaxSourceResult` and `GrowthResult`) and its constants (`SCALE`, `ROUNDING`) stay usable unqualified inside MultiPool, because they are inherited from the interface.

- [ ] **Step 1:** Move the class. Add only the imports it needs, and drop the ones `PoolStrategy.java` no longer needs.
- [ ] **Step 2:** Re-point the references:
  ```
  grep -rl 'PoolStrategy\.MultiPool' backend --include=*.java | xargs sed -i 's/PoolStrategy\.MultiPool\b/MultiPool/g'
  ```
  Then fix any javadoc `{@code PoolStrategy.MultiPool#...}` text this leaves stale.
- [ ] **Step 3:** Run the verification command. Expected: exit 0 and an empty golden status. If PMD now flags MultiPool as a new top-level class (for example `GodClass` or `TooManyFields`), stop and report the exact finding rather than suppressing it. Task 4 shrinks the class.
- [ ] **Step 4:** Commit with the message `refactor(projection): move MultiPool to its own file`, with a body.

---

### Task 4: Extract the withdrawal tax-bill solver from MultiPool

**Files:**
- Create: `WithdrawalTaxBillSolver.java`
- Create test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/WithdrawalTaxBillSolverTest.java`
- Modify: `MultiPool.java`
- Modify tests: `MultiPoolDeepTest.java` and `MultiPoolOwnerTest.java`, one new test each.

**What moves from MultiPool into the solver.** It moves verbatim, with comments, except for the changes listed under Interfaces:
- the records `BillInputs`, `TaxBill`, `OrdinaryTax` and `GrossUpResult`;
- the methods `computeBill`, `settleTaxSaleGain`, `computeOrdinaryTax`, `growTraditionalGrossUp`, `recomputeBill`, `peekTraditionalSlice`, `computeLtcgTax` and `resolveOrdinaryDeduction`;
- the constants `MAX_GROSS_UP_ITERATIONS`, `GROSS_UP_TOLERANCE`, `GROSS_UP_WARM_START_RATE_CAP`, `MAX_TAX_SALE_GAIN_ITERATIONS` and `EARLY_WITHDRAWAL_PENALTY_RATE`.

**Interfaces:**
- Constructor, built once in each MultiPool constructor from its `PoolConfig`:
  ```
  WithdrawalTaxBillSolver(@Nullable TaxCalculationStrategy taxCalculator,
          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
          @Nullable FederalTaxCalculator federalTaxCalculator,
          int baseYear, BigDecimal inflationRate, int earlyAccessAge)
  ```
- `record BillInputs(int year, BigDecimal effectiveOtherIncome, BigDecimal conversionAmount, BigDecimal traditionalSpendIncome, BigDecimal baseRealizedGain, BigDecimal alreadyChargedBaseTax, BigDecimal extraPoolFundedTax, BigDecimal federallyTaxedSocialSecurity, BigDecimal netRentalIncome, int age, BigDecimal taxableAvail, BigDecimal traditionalAvail, FilingStatus filingStatus, BigDecimal qualifiedDividendIncome, BigDecimal ordinaryInterestIncome)`.
  - This is package-private and nested in the solver.
  - It is today's 12 components plus the last three, which MultiPool used to read from its own fields.
  - That makes 15 components, under the PMD `ExcessiveParameterList` minimum of 17.
- `record TaxBill(BigDecimal total, BigDecimal ltcgTax, BigDecimal realizedLtcgIncome, BigDecimal traditionalGrossUp, BigDecimal earlyWithdrawalPenalty, Optional<CombinedTaxResult> recordedBreakdown)`.
  - This is package-private and nested.
  - `traditionalGrossUp` replaces the old `grossUp` component, because MultiPool only ever read `grossUp().traditionalGrossUp()`. `GrossUpResult` stays private to the solver.
  - `recordedBreakdown` is the last breakdown recorded by any ordinary-tax evaluation during this `settle` call (see the algorithm below), or empty if none was recorded.
- `TaxBill settle(BillInputs in, UnaryOperator<BigDecimal> fifoGainPeek)` is the only non-private method. MultiPool passes `lots::peekFifoGain`.
- **The algorithm the signatures don't determine: per-call recording.**
  - The old code wrote `lastTaxBreakdown = Optional.of(detailed)` from inside `computeOrdinaryTax`. The new code records into per-call state instead.
  - Implement it with a private nested `final class Run`, created fresh by each `settle` call. It holds `in`, `fifoGainPeek` and a `@Nullable CombinedTaxResult recorded` field.
  - `computeBill`, `settleTaxSaleGain`, `computeOrdinaryTax`, `growTraditionalGrossUp` and `recomputeBill` become instance methods of `Run`. Each assignment to `lastTaxBreakdown` becomes `recorded = detailed`, under the same condition as today.
  - Every read of `filingStatus`, `qualifiedDividendIncome` and `ordinaryInterestIncome` becomes a read of `in`.
  - The returned `TaxBill` carries `Optional.ofNullable(recorded)`. This is exact, not approximate: nothing inside the bill computation ever reads `lastTaxBreakdown`.
  - The solver itself holds no mutable state.
- MultiPool changes:
  - Remove the fields `capitalGainsTaxCalculator`, `inflationRate`, `baseYear` and `federalTaxCalculator`. Afterwards only the solver uses them.
  - Keep `taxCalculator` and `earlyAccessAge`, which the conversion path and the withdrawal-order strategy still use.
  - Add `private final WithdrawalTaxBillSolver billSolver`.
  - In `executeWithdrawals`, build `BillInputs` with the pool's current `filingStatus`, `qualifiedDividendIncome` and `ordinaryInterestIncome`. Then call:
    ```
    var bill = billSolver.settle(inputs, lots::peekFifoGain);
    bill.recordedBreakdown().ifPresent(b -> lastTaxBreakdown = Optional.of(b));
    ```
    Replace `bill.grossUp().traditionalGrossUp()` with `bill.traditionalGrossUp()`. Everything else in the method is unchanged.

- [ ] **Step 1: Write the failing solver tests.** Put them in `WithdrawalTaxBillSolverTest`, using AssertJ and `isEqualByComparingTo` for money. Build inputs with a small private helper:
  - `inputs(int age, BigDecimal traditionalSpendIncome, FilingStatus status)`
  - year 2030, every other money field `BigDecimal.ZERO`
  - `taxableAvail` = 100000, `traditionalAvail` = 100000
  - Unless a test says otherwise, `fifoGainPeek` is `x -> BigDecimal.ZERO`, and the Mockito-based solvers are `(tax, null, null, 2025, ZERO, 60)`.

  The tests:
  - `settle_belowEarlyAccessAge_chargesTenPercentPenalty`: solver `(null, null, null, 2025, ZERO, 60)`; age 55, traditional income 10000. Expect `earlyWithdrawalPenalty` = 1000 and `total` = 1000.
  - `settle_atEarlyAccessAge_chargesNoPenalty`: the same, but age 60. Expect penalty 0 and total 0.
  - `settle_nothingRecordedWhenNoIncomeAndNoTax`: a Mockito `TaxCalculationStrategy`, with traditional income 0 and no gains. Expect `recordedBreakdown()` empty, and `verifyNoInteractions(tax)`.
  - `settle_recordsLastBreakdownOnlyWhenIncomeOrTax`: stub the 5-arg `computeDetailedTax(any(), anyInt(), any(), any(), any())` to return `new CombinedTaxResult(bd("1500"), ZERO, bd("1500"), ZERO, ZERO, false)`. Age 65, traditional income 20000. Expect `recordedBreakdown()` to contain that exact result and `total` = 1500.
  - `settle_usesFilingStatusFromInputs`: the same stub, with status `FilingStatus.SINGLE`. Verify `computeDetailedTax(any(), eq(2030), eq(FilingStatus.SINGLE), any(), any())` was called and that `FilingStatus.MARRIED_FILING_JOINTLY` was never passed.
  - `settle_noEmbeddedGain_returnsGainZeroBillAfterOnePeek`: the same stub. `fifoGainPeek` counts its calls and always returns `ZERO`. Expect exactly 1 peek and `total` = 1500.
- [ ] **Step 2:** Run `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=WithdrawalTaxBillSolverTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: a compilation failure, because `WithdrawalTaxBillSolver` doesn't exist yet.
- [ ] **Step 3: Write the MultiPool regression tests.** They must pass before AND after the extraction, so they pin the behavior across the change:
  - In `MultiPoolDeepTest`, add `executeWithdrawals_zeroBillAfterConversion_keepsConversionBreakdown`:
    - Build a pool whose tax calculator records a non-zero breakdown for a Roth conversion.
    - Run `executeRothConversion`, then `executeWithdrawals(WithdrawalCycleInputs.of(ZERO, year, 70))` with no income and no gains.
    - Assert that `getLastTaxBreakdown()` still equals the conversion's breakdown.
    - Reuse the file's existing tax-stub style.
  - In `MultiPoolOwnerTest`, add `executeWithdrawals_afterFirstDeath_billUsesSingleFiling`:
    - Build a two-owner MFJ pool with a Mockito `TaxCalculationStrategy`.
    - Call `applyFirstDeathTransition(PersonId.SPOUSE, PersonId.PRIMARY, false)`, then `executeWithdrawals` with a 10000 traditional need at age 75.
    - Verify that `computeDetailedTax` received `FilingStatus.SINGLE`.

  Run both. Expected: PASS against the current code. If either fails, stop and report it, because that means the test doesn't describe today's behavior.
- [ ] **Step 4:** Create `WithdrawalTaxBillSolver` and rewire MultiPool as specified under Interfaces.
- [ ] **Step 5:** Run the verification command. Expected: exit 0 (all 6 solver tests and both regression tests pass) and an empty golden status.
- [ ] **Step 6:** Commit with the message `refactor(projection): extract the withdrawal tax-bill solver from MultiPool`. The body covers:
  - the per-call recording design;
  - the four fields MultiPool lost;
  - the new tests.

---

### Task 5: Move TrialSimulator's configuration types to their own files

**Files:**
- Create: `SimulationConfig.java` (the record plus its nested `Builder`, verbatim, with the Builder's existing `@SuppressWarnings` and comment)
- Create: `GuardrailAdaptation.java`
- Create: `HouseholdSim.java`
- Create: `TaxableSeed.java` (with `EMPTY` and `hasOwnedSlices()`)
- Create: `SurvivorRegime.java`
- Modify: `TrialSimulator.java` (delete the moved records)
- Modify: every file spelling `TrialSimulator.SimulationConfig`, `TrialSimulator.GuardrailAdaptation`, `TrialSimulator.HouseholdSim`, `TrialSimulator.TaxableSeed` or `TrialSimulator.SurvivorRegime` (about 20 files, 159 references)

**Interfaces:**
- Produces: the same five records under top-level names. `TrialResult`, `WithdrawalOutcome`, `PRIMARY_SURVIVES` and `SPOUSE_SURVIVES` stay on `TrialSimulator`.
- In the moved javadoc:
  - `{@link #PRIMARY_SURVIVES}` and `{@link #SPOUSE_SURVIVES}` become `{@link TrialSimulator#PRIMARY_SURVIVES}` and `{@link TrialSimulator#SPOUSE_SURVIVES}`;
  - `{@link #simulateTrial}` becomes `{@link TrialSimulator#simulateTrial}`.

- [ ] **Step 1:** Move the five records, then re-point the references:
  ```
  grep -rlE 'TrialSimulator\.(SimulationConfig|GuardrailAdaptation|HouseholdSim|TaxableSeed|SurvivorRegime)\b' backend --include=*.java | xargs sed -i -E 's/TrialSimulator\.(SimulationConfig|GuardrailAdaptation|HouseholdSim|TaxableSeed|SurvivorRegime)\b/\1/g'
  ```
  Then confirm the same grep prints nothing. Don't rename any test class in this task.
- [ ] **Step 2:** Run the verification command. Expected: exit 0 and an empty golden status.
- [ ] **Step 3:** Commit with the message `refactor(projection): move TrialSimulator's configuration records to their own files`, with a body.

---

### Task 6: Extract TrialSimulator's tax, growth and spending helpers

**Files:**
- Create: `TrialTaxes.java`
- Create: `TrialGrowth.java`
- Create: `TrialSpending.java`
- Modify: `TrialSimulator.java`
- Modify tests that call the moved package-private statics: `TrialSimulator.adaptYearSpending` (5 references) and `TrialSimulator.ltcgTaxForYear` (3 references)

**Interfaces.** Every body is moved verbatim, with its comments. Each class is a static-only `final class` with a private constructor.
- `TrialTaxes` holds:
  - the constants `GROSS_UP_RATE_CAP` and `EARLY_WITHDRAWAL_PENALTY_RATE`;
  - package-private statics with the same signatures as today: `applyEarlyWithdrawalPenalty`, `deductTaxFromPoolsGrossedUp`, `settleBaseIncomeTaxAndSurplus`, `applyInterestTax`, `applyLtcgTax`, `ltcgTaxForYear`.
  - It uses `TrialPools.TRAD_P` / `TrialPools.TRAD_S` directly.
- `TrialGrowth` holds:
  - `record GrowthYieldResult(double dividendIncome, double baseWithInterest)` and `record TentGrowthYear(...)`, unchanged and now package-private;
  - the statics `applyGrowthAndInterestTax`, `applyTentGrowth` and `effectiveReturn`. These call `TrialTaxes.applyInterestTax`.
- `TrialSpending` holds:
  - `record SpendingFunding(...)`, now package-private;
  - the statics `resolveSpendingFunding`, `resolveYearSpending` and `adaptYearSpending`.
- `TrialSimulator` keeps:
  - the constants `PRIMARY_SURVIVES` and `SPOUSE_SURVIVES`;
  - the records `TrialResult` and `WithdrawalOutcome`;
  - `simulateTrial` and `carryForwardBequest`;
  - `activeTent`, `yearsCovered` and `settleTent`;
  - `YearAuxInputs` with `resolveYearAuxInputs`, and `YearRegimeView` with `resolveYearRegimeView`;
  - `regimeAt`, `applyTrialConversion`, `applyTrialWithdrawals` and both `splitWithdrawal` overloads.
- Remaining calls into the moved helpers become qualified (`TrialTaxes.x(...)`, `TrialGrowth.x(...)`, `TrialSpending.x(...)`).
- Delete the `TRAD_P`/`TRAD_S`/`ROTH_P`/`ROTH_S` aliases from `TrialSimulator` if nothing there still uses them, and move their explanatory comment with whichever class still needs it.
- `simulateTrial`'s `@SuppressWarnings("PMD.NPathComplexity")` stays.
- No new object is allocated per simulated year: the same records are created at the same points as today.

- [ ] **Step 1:** Create the three classes and move the members. Re-point the test calls:
  ```
  sed -i 's/TrialSimulator\.adaptYearSpending(/TrialSpending.adaptYearSpending(/g; s/TrialSimulator\.ltcgTaxForYear(/TrialTaxes.ltcgTaxForYear(/g' <the test files grep finds>
  ```
- [ ] **Step 2:** Run the verification command. Expected: exit 0 and an empty golden status. In particular, `MonteCarloSpendingOptimizerCharacterizationTest`, `MonteCarloBondTentCharacterizationTest` and `TrialSimulatorReturnTest` must pass unchanged.
- [ ] **Step 3:** Commit with the message `refactor(projection): split TrialSimulator's tax, growth and spending helpers into their own classes`. The body lists what moved where and states that there is no numeric change.

---

## Finish (controller, after Task 6)

- [ ] Run the app integration suite once:
  ```
  cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true
  ```
  Expected: exit 0, with 0 failures and 0 errors.
- [ ] Record `wc -l` for `PoolStrategy.java`, `MultiPool.java`, `WithdrawalTaxBillSolver.java`, `PoolConfig.java`, `PoolReturnBlender.java`, `TrialSimulator.java`, `SimulationConfig.java`, `TrialTaxes.java`, `TrialGrowth.java` and `TrialSpending.java` for the report.
- [ ] Confirm JaCoCo still passes the projection floors (line 0.98, branch 0.91). The verification command enforces this; the report should give the measured numbers.
