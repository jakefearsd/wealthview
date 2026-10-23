# Bond Tent Plan 3 — Tent Settings, Sizing and the Deterministic Engine — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A user can configure a bond tent on a scenario. WealthView then sizes the tent, the main (deterministic) projection builds and holds it, and the scenario form shows how big it needs to be.

**Architecture:**
- **Settings:** the tent config is a typed `BondTentConfig` nested in `params_json` (`ScenarioParams.bondTent`). It is validated in `ScenarioParamsValidator`.
- **Sizing:** a pure `BondTentSizer` sizes it. The target is the present value of the first `coverage` retirement years' portfolio withdrawals, discounted at the mix's real yield.
- **Holding the tent:** the deterministic engine runs twice, with no feedback loop (spec §4.3 rule 1).
  - Pass one runs without the tent and provides the withdrawals.
  - Pass two builds the tent and holds it.
  - Inside `PoolStrategy.MultiPool` the tent is a **reservation inside the pools**: dollars in the taxable, traditional or Roth pool that are earmarked as bond sleeves.
  - Every existing pool operation (withdrawal order, RMDs, conversions, tax) works unchanged on pool totals.
  - A reservation changes only two things: how that pool grows (each sleeve earns its own maturity's return), and that pool debits spend growth assets before tent sleeves.
- **Results:** a `bond_tent` side channel on the run response carries the sizing and the per-year tent balances, so the six existing goldens cannot move. A new sizing endpoint feeds the scenario form's "Bond tent" section.

**Tech Stack:** Java 25 / Spring Boot 4.1 (Jackson 3), JUnit 5 + AssertJ + Mockito, Testcontainers; React 19 / TypeScript / Vitest.

**Spec:** `docs/superpowers/specs/2026-10-18-bond-tent-design.md`. Covers §4.2, §4.3 rules 1–7, §4.4 "Deterministic", §4.5, §6.1 (the 100%-ultrashort hint) and §6.2 (rule-of-thumb baseline).

**Where this plan sits.** The spec's "plan 3" was too large for one reviewable plan, so it is split. Plan 1 (trials start today) and plan 2 (bond maturity data) have shipped.
- **This plan (3):** settings, sizing and the deterministic engine.
- **Plan 4:** Monte Carlo tent, optimizer certification, the scenario signature and retiring the cash reserve with its data migration.
- **Plan 5:** crash replays, the fixed-spending Monte Carlo evaluation and the Bond tent results tab.

Until plan 4 lands, the optimizer ignores a configured tent. Main is not tagged in between.

### Builder's calls this plan makes

1. **Storage.** The config is a nested `bond_tent` object in `params_json`. There is no migration: `ScenarioParams` is `NON_NULL`, so a scenario without a tent serializes byte-identically.
2. **Withdrawals that drive sizing.**
   - Per retired year: `max(0, essential + discretionary + taxLiability − incomeStreamsTotal)`, read from pass one's year rows. This is the portfolio's spending outflow: spending net of income, plus the taxes the portfolio pays.
   - "Coverage counts from retirement", or from the first projected year when retirement is already past.
   - Projected portfolio at retirement = pass one's `startBalance` of the first retired year.
3. **Present value timing.** Withdrawal `k` (0-based) is discounted by `(1 + r)^(k+1)`: the engine applies growth before withdrawals within a year, and the tent is full at the start of the first retired year. A partial final year counts at its fraction. If the projection has fewer retired years than the coverage, only the available years count.
4. **Two kinds of rate.**
   - **Sizing** uses the mix's real yield from the typed nominal yields and the scenario inflation: `Σ share × ((1 + y)/(1 + inflation) − 1)`.
   - **Sleeve growth** in the deterministic engine uses the historical real geometric means per maturity, from `BondMaturityReturnsProvider.geometricMeansOf(alignedTo(matrix(false)))`. That is the same constant-average convention as every other asset there.
5. **Placement (spec rule 7).** The tent is reserved in pools in the withdrawal order's own sequence:
   - `TRADITIONAL_FIRST` → traditional, taxable, Roth.
   - `ROTH_FIRST` → Roth, taxable, traditional.
   - All other orders → taxable, traditional, Roth.

   Each pool holds at most its own balance; the rest spills to the next pool.
6. **Build-up line.** Let `start = max(currentYear, retirementYear − buildUpStartYears)` and `B = retirementYear − start`.
   - At the start of each year `y` in `[start, max(retirementYear, currentYear)]`, before growth, the reservation is topped up to `L(y) = target × (y − start) / B`. When `B == 0` or `y ≥ retirementYear`, `L(y) = target`.
   - No top-ups happen after the first retired year (spec rule 5: no refill).
   - Re-labelling inside a pool is untaxed (spec rule 2, "moves inside accounts are untaxed"). The taxable gain a real sale of stocks to buy bonds would realize is ignored and documented.
   - The deterministic engine never sees stocks below a prior high, so every scheduled top-up happens.
7. **Withdrawing past the growth assets.** A pool debit that exceeds the pool's growth part eats its tent sleeves, shortest maturity first. A deterministic projection that is running out of money therefore spends its bonds rather than failing with bonds left over. The spec is silent on this case, and this is the behavior a reasonable person expects.
8. **Tax on a taxable reservation.** It earns ordinary interest at its sleeves' typed nominal yields, in retired years only (audit C8 gating). The interest is added to the year's `ordinaryInterestIncome` and reinvested at cost, through the same mechanism as the audit-C1 split. Reservations in traditional or Roth accounts book no interest.
9. **Legacy data guard.** With a tent active, the engine uses the 1972–2025 window even if `include_depression_years` is true. Validation already rejects that combination (bond data starts in 1954).
10. **Spending plan required.** A tent with no spending plan is rejected on save and ignored by the engine.
11. **Sizing explanation.** The UI states one reason the size differs from the rule of thumb (`coverage × 4%` of the projected portfolio). It is the largest of four additive components, sizer-defined in Task 2: spending level, income, withdrawal tax and the mix's yields.

## Global Constraints

**Engine and data rules**
- Real terms throughout. `BigDecimal` in the deterministic engine and its DTOs; the pure sizer works in `double`, converted at the boundary with `BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_UP)` for money and scale 6 for rates and fractions.
- **No tent means byte-identical behavior.** The six goldens in `ProjectionGoldenFileTest`, `DeterministicProjectionEngineCharacterizationTest`, `EngineInvariantsTest`, `PoolStrategyCharacterizationTest` and every Monte Carlo pin must pass with no edits. A no-tent run response carries no `bond_tent` key. Every tent code path is guarded so it is inert when there is no reservation.
- **Spec values:**
  - coverage is 1–10 years in 0.5 steps;
  - build-up start is 0–40 years before retirement;
  - a tent has 1–3 mixes, and the first mix is the plan mix;
  - each share is 0–1, and a mix's shares sum to 1 within 1e-6;
  - yields are nominal decimals, each in [−0.02, 0.20];
  - mix names are non-blank, at most 40 characters.
- **JSON:** `params_json` and API fields are snake_case. The four maturity keys are exactly `ultrashort`, `short`, `intermediate` and `long`.

**Code conventions**
- Records for DTOs and value types.
- `@Nullable` from `org.springframework.lang`.
- No wildcard imports, AssertJ only.
- Test names follow `method_state_expected`.
- 120-character lines.
- A PMD suppression needs an adjacent justifying comment. Never suppress CPD.

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

1. **A scenario with no tent.** Its projection and run response are unchanged byte for byte, with no `bond_tent` key and all six goldens identical. Pinned in Task 4 by `runDetailed_noTent_hasNoBondTentResult` and the untouched goldens, and in Task 1 by `toJson_noTent_omitsBondTentKey`.
2. **A legacy scenario that has both a tent and `include_depression_years: true`.** This can happen through plan 4's data migration or a direct database edit. The engine must project on the 1972–2025 window and never throw. Pinned in Task 4 by `runDetailed_tentWithDepressionYearsFlag_usesDefaultWindow`.
3. **Retirement this year or already past.** The tent is fully reserved in the first projected year, and coverage counts from that year. Build-up start earlier than today compresses the ramp. Pinned in Task 4 by `runDetailed_retiredAlready_fundsFullTargetInFirstYear` and `runDetailed_buildUpStartBeforeToday_compressesRamp`.
4. **A pool too small for the tent.** The reservation spills to the next pool in placement order. A portfolio smaller than the target reserves everything, and nothing goes negative. Pinned in Task 3 by `topUpTent_firstPoolTooSmall_spillsToNextPool` and `topUpTent_targetAbovePortfolio_reservesEverything`.
5. **A projection that runs short of money.** Withdrawals spend the tent, shortest maturity first, once a pool's growth assets are gone. They never fail while bonds remain in that pool. Pinned in Task 3 by `reconcile_debitBeyondGrowthPart_spendsShortestSleeveFirst`.

---

### Task 1: Tent configuration on scenarios

**Files:**
- Create in `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/`: `BondMaturityValues.java`, `BondTentMix.java`, `BondTentConfig.java`.
- Modify:
  - `.../dto/ScenarioParams.java`
  - `.../dto/ScenarioParamsSource.java`
  - `.../dto/ScenarioRequest.java`
  - `.../projection/ScenarioParamsValidator.java`
  - `.../projection/ScenarioCrudService.java`
  - `backend/wealthview-core/src/test/java/com/wealthview/core/testutil/ScenarioRequestBuilder.java`, or wherever the builder lives.
- Test: `BondTentConfigTest.java` and `ScenarioParamsValidatorTest.java` (extend), plus `ScenarioCrudServiceTest.java` (extend), all in core.

**Interfaces:**
- Produces:
  - `public record BondMaturityValues(BigDecimal ultrashort, @JsonProperty("short") BigDecimal shortTerm, BigDecimal intermediate, @JsonProperty("long") BigDecimal longTerm)`:
    - `BigDecimal get(BondMaturity m)`.
    - `BigDecimal sum()`, which treats null as zero.
  - `public record BondTentMix(String name, BondMaturityValues shares)`.
  - `@JsonNaming(SnakeCaseStrategy) @JsonInclude(NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true) public record BondTentConfig(BigDecimal coverageYears, Integer buildUpStartYears, List<BondTentMix> mixes, BondMaturityValues yields)`:
    - `BondTentMix planMix()` returns `mixes.getFirst()`.
  - `ScenarioParams` gains a 33rd component, `BondTentConfig bondTent`, which comes last.
    - Today's 32-arg canonical constructor becomes a back-compat constructor that pads `null`, alongside the existing 29-arg one.
    - `EMPTY` and `from(source)` are updated.
  - `ScenarioParamsSource` gains `@Nullable BondTentConfig bondTent()`.
  - `ScenarioRequest` gains `@Valid @Nullable BondTentConfig bondTent` immediately after `heirTaxRate`. The back-compat constructors pad `null`, and the test builder gains `.bondTent(...)`.
  - `ScenarioParamsValidator`:
    - It gains `public static void validateBondTent(@Nullable BondTentConfig tent, @Nullable Boolean includeDepressionYears)`.
    - It throws `IllegalArgumentException` with the messages below, and is called from `validate(ScenarioParamsSource)`.

- [ ] **Step 1: Write the failing tests**

```java
// BondTentConfigTest — JSON round trip through ScenarioParams with the app's ObjectMapper
@Test void toJson_tent_writesSnakeCaseAndMaturityKeys()
    // params with a tent serializes to contain exactly:
    // "bond_tent":{"coverage_years":2.5,"build_up_start_years":3,"mixes":[{"name":"Balanced","shares":
    // {"ultrashort":0.2,"short":0.3,"intermediate":0.5,"long":0}}],"yields":{"ultrashort":0.0394,
    // "short":0.0465,"intermediate":0.048,"long":0.0539}}
@Test void parseOrEmpty_tentJson_roundTrips()               // parse(toJson(p)) equals p
@Test void toJson_noTent_omitsBondTentKey()                 // a params value with other fields set: no "bond_tent" substring
@Test void get_eachMaturity_returnsItsValue()               // BondMaturityValues.get(SHORT) == shortTerm, etc.

// ScenarioParamsValidatorTest
@Test void validateBondTent_valid_passes()
@Test void validateBondTent_coverageNotHalfStep_throws()    // 2.3 -> "Bond tent coverage must be between 1 and 10 years, in half-year steps"
@Test void validateBondTent_coverageOutOfRange_throws()     // 0.5 and 10.5
@Test void validateBondTent_buildUpOutOfRange_throws()      // -1 and 41 -> "Bond tent build-up start must be between 0 and 40 years before retirement"
@Test void validateBondTent_noMixesOrFour_throws()          // 0 and 4 -> "A bond tent needs one to three bond mixes"
@Test void validateBondTent_blankOrLongName_throws()        // "Each bond mix needs a name of at most 40 characters"
@Test void validateBondTent_sharesNotSummingToOne_throws()  // 0.2/0.3/0.4/0 -> "Bond mix 'Balanced' shares must each be between 0% and 100% and add up to 100%"
@Test void validateBondTent_yieldOutOfRange_throws()        // 0.25 -> "Bond tent yields must each be between -2% and 20%"
@Test void validateBondTent_withDepressionYears_throws()    // "Turn off the 1928–1971 market history to use a bond tent: bond maturity data starts in 1954"

// ScenarioCrudServiceTest
@Test void createScenario_tentWithoutSpendingPlan_throws()  // "A bond tent needs a spending plan: choose a spending profile or optimized plan first"
@Test void updateScenario_tentWithActiveSpendingProfile_savesTentInParamsJson()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test-compile`
Expected: compilation FAILS because the new records and the new `ScenarioParams` component do not exist.

- [ ] **Step 3: Implement the records, the plumbing and the validation**

The spending-plan check lives in `ScenarioCrudService` create and update. It throws when the tent is present and the scenario's resulting active plan is "None": no `spendingProfileId`, and no guardrail profile kept active through `useGuardrailProfile`. Read how `updateScenario` resolves the active plan, and reuse that resolution.

- [ ] **Step 4: Run the gate**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am verify -DskipITs`
Expected: BUILD SUCCESS.

Run: `cd backend && mvn -q -T1 -pl wealthview-api -am verify -DskipITs`
Expected: BUILD SUCCESS. `ScenarioRequest` is the API body, so controller tests must still pass.

- [ ] **Step 5: Commit**

Subject: `feat(core): store a bond tent configuration on scenarios`. The body says that `params_json` is NON_NULL, so scenarios without a tent are unchanged, and lists the validation rules.

---

### Task 2: `BondTentSizer`

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/BondTentSizingReason.java`
- Create: `backend/wealthview-projection/src/main/java/com/wealthview/projection/BondTentSizer.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/BondTentSizerTest.java`

**Interfaces:**
- Consumes: Task 1's `BondMaturityValues`.
- Produces:
  - `public enum BondTentSizingReason { SPENDING_LEVEL, INCOME, WITHDRAWAL_TAX, MIX_YIELDS }`. Declaration order is the tie-break order.
  - `final class BondTentSizer`, package-private with a private constructor:
    - `static double mixRealYield(BondMaturityValues yields, BondMaturityValues shares, double inflationRate)` returns `Σ share_m × ((1 + y_m)/(1 + inflation) − 1)`.
    - `static double target(double[] withdrawals, double coverageYears, double realYield)` returns `Σ_{k<K} f_k × w_k / (1+r)^(k+1)`, where `K = min(ceil(coverage), w.length)` and `f_k = min(1, coverage − k)`.
    - `static Sizing size(double[] spending, double[] income, double[] tax, double coverageYears, double realYield, double portfolioAtRetirement)`.
  - `record Sizing(double target, double[] withdrawals, double[] fractions, double ruleOfThumb, BondTentSizingReason reason, double reasonAmount)`, package-private and nested:
    - `withdrawals[k] = max(0, spending[k] − income[k] + tax[k])` for `k < K`.
    - `fractions[k] = f_k`.
    - `ruleOfThumb = coverage × 0.04 × portfolioAtRetirement`.
    - Components, with `S = Σ f_k × withdrawals[k]`:
      - `SPENDING_LEVEL = Σ f_k spending[k] − ruleOfThumb`
      - `INCOME = −Σ f_k income[k] + (S − Σ f_k (spending[k] − income[k] + tax[k]))`. The second term is the zero-clamp residual.
      - `WITHDRAWAL_TAX = Σ f_k tax[k]`
      - `MIX_YIELDS = target − S`
    - `reason` is the component with the largest absolute value, and `reasonAmount` is that component's signed value.

- [ ] **Step 1: Write the failing tests**

```java
@Test void target_fiveYearsFlat40kAt1Point5Percent_matchesPresentValue()      // isCloseTo(191305.7989, within(1e-3))
@Test void target_twoAndAHalfYears_countsPartialYearAtHalf()                  // r=0.015 -> 97361.6768 within 1e-3
@Test void target_zeroYield_isUndiscountedSum()                               // 2.5y, 40k flat, r=0 -> 100000.0
@Test void target_horizonShorterThanCoverage_usesAvailableYears()             // w={40000,30000}, cov 5, r=0 -> 70000.0
@Test void target_negativeRealYield_exceedsWithdrawals()                      // w={10000}, cov 1, r=-0.01 -> 10101.0101 within 1e-3
@Test void mixRealYield_balancedMixAtTwoAndAHalfPercentInflation_isWeightedRealYield()
    // yields .0394/.0465/.048/.0539, shares .2/.3/.5/0, inflation .025 -> 0.0203219512 within 1e-9
@Test void size_ruleOfThumbsFallOutOfTheMath()
    // spending 40k, income 0, tax 0, P = 1,000,000, r = 0.015:
    // cov 2.5 -> target/P within [0.09, 0.11]; cov 6 -> target/P within [0.18, 0.25]
@Test void size_spendingAboveFourPercent_reasonIsSpendingLevel()
    // spending 60k x5, income {0,0,0,30k,30k}, tax 5k x5, cov 5, r .02, P 1M:
    // target 251487.581 within 1e-2; ruleOfThumb 200000; reason SPENDING_LEVEL, reasonAmount 100000
@Test void size_incomeCoversMost_reasonIsIncome()               // spending 40k, income 30k, tax 0, r 0, cov 5, P 1M -> INCOME, -150000
@Test void size_taxDominates_reasonIsWithdrawalTax()            // spending 40k, income 0, tax 10k, r 0, cov 5, P 1M -> WITHDRAWAL_TAX, 50000
@Test void size_highYieldNothingElse_reasonIsMixYields()        // spending 40k, income 0, tax 0, r .03, cov 5, P 1M -> MIX_YIELDS, negative
@Test void size_incomeAboveSpending_clampsWithdrawalAtZero()    // spending 20k, income 30k -> withdrawals all 0, target 0
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement the enum and `BondTentSizer`**

- [ ] **Step 4: Run the gate**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

Subject: `feat(projection): size a bond tent from planned withdrawals and mix yields`

---

### Task 3: Tent reservation inside `MultiPool`

**Files:**
- Create: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TentReservation.java`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java`. Change only `MultiPool` and the `PoolStrategy` interface methods it needs.
- Test:
  - `backend/wealthview-projection/src/test/java/com/wealthview/projection/TentReservationTest.java`
  - `backend/wealthview-projection/src/test/java/com/wealthview/projection/MultiPoolTentTest.java`

**Interfaces:**
- Consumes: `BondMaturity` from plan 2 and `PoolType` from core.
- Produces:
  - `final class TentReservation`, package-private. It holds `EnumMap<PoolType, EnumMap<BondMaturity, BigDecimal>>` sleeve values at `PoolStrategy.SCALE`.
    - `static TentReservation empty()`, `boolean isEmpty()`.
    - `BigDecimal total()`, `BigDecimal totalIn(PoolType)`.
    - `Map<BondMaturity, BigDecimal> byMaturity()`: summed over pools, shortest-first `EnumMap`.
    - `void reserve(PoolType pool, BigDecimal amount, Map<BondMaturity, BigDecimal> mixShares)`: splits `amount` across sleeves by share.
    - `BigDecimal grow(PoolType pool, Map<BondMaturity, BigDecimal> realReturns)`: grows that pool's sleeves and returns their growth.
    - `void clampTo(PoolType pool, BigDecimal poolBalance)`: when the pool's sleeves exceed `poolBalance`, it removes the excess shortest maturity first.
    - `BigDecimal interestIn(PoolType pool, Map<BondMaturity, BigDecimal> nominalYields)`: `Σ sleeve × yield`, no mutation.
    - `TentReservation copy()`.
  - `record TentSetup(List<PoolType> placementOrder, Map<BondMaturity, BigDecimal> mixShares, Map<BondMaturity, BigDecimal> realReturns, Map<BondMaturity, BigDecimal> nominalYields)`, nested in `MultiPool` or package-level. It has `static List<PoolType> placementFor(WithdrawalOrder order)` following Builder's call 5.
  - `PoolStrategy` gains:
    - `void configureTent(TentSetup setup)`
    - `void topUpTent(BigDecimal targetBalance)`: reserves `max(0, target − reserved)`, filling each pool in placement order up to its unreserved balance.
    - `BigDecimal tentTotal()`
    - `Map<BondMaturity, BigDecimal> tentByMaturity()`
  - Inside `MultiPool`:
    - Every one of the four is a no-op, or ZERO / an empty map, until `configureTent` is called.
    - The reservation is part of `MultiPoolMemento`, `snapshot()` and `restore()`. `YearFinanceResolver`'s Social Security fixed-point loop must not double-apply it.
    - Before growth, and before `tentTotal`/`tentByMaturity` answer, each pool's reservation is clamped to that pool's current balance (`clampTo`).
    - **Growth when a pool `p` holds a reservation `T_p` and has balance `V_p`:** the pool reaches `(V_p − T_p)(1 + r_p) + Σ_m T_p,m (1 + r_m)` and the sleeves grow at `r_m`.
      - For traditional and Roth, grow the `OwnerPool` at the effective rate `newV/V − 1`, computed at `SCALE + 4`.
      - For taxable, use that blended figure as the target total that `growToExactTotal` / `applyTaxableYieldSplit` already reach.
    - **Interest on a taxable reservation in a retired year:** `interestIn(TAXABLE, nominalYields)` is added to `ordinaryInterestIncome` and reinvested as an at-cost lot. The C1 split runs over the unreserved part only. Total taxable growth still equals the blended target exactly.
    - With no reservation, every one of these paths is the existing code, byte for byte.

- [ ] **Step 1: Write the failing tests**

```java
// TentReservationTest
@Test void reserve_splitsAmountByMixShares()                         // 100,000 at .2/.3/.5/0 -> 20,000/30,000/50,000/0
@Test void grow_eachSleeveAtItsOwnReturn()                           // returns .01/.02/.03/.04 -> growth 2,100 on the above
@Test void clampTo_balanceBelowReservation_removesShortestFirst()    // 100k reserved, clamp to 75k -> ultrashort 0, short 25,000, intermediate 50,000
@Test void interestIn_taxablePool_isSleeveTimesYield()
@Test void copy_isIndependent()

// MultiPoolTentTest — build a MultiPool through PoolStrategy.create with explicit expectedReturn overrides
@Test void noTentConfigured_growthAndTotalsUnchanged()               // tentTotal 0; applyGrowth result equals a twin pool's without configureTent
@Test void topUpTent_reservesInPlacementOrder()                      // taxable 300k, traditional 500k, target 200k -> all 200k in taxable
@Test void topUpTent_firstPoolTooSmall_spillsToNextPool()            // taxable 50k -> 50k taxable + 150k traditional
@Test void topUpTent_targetAbovePortfolio_reservesEverything()       // portfolio 120k, target 200k -> tentTotal 120k; no pool negative
@Test void applyGrowth_reservedPool_growsSleevesAtMaturityReturns()  // traditional 500k at 5% with 100k reserved at 1% -> 520,000
@Test void applyGrowth_retiredTaxableReservation_booksOrdinaryInterest() // interest = Σ sleeve x yield joins ordinaryInterestIncome
@Test void applyGrowth_accumulationYearTaxableReservation_booksNoInterest()
@Test void reconcile_debitBeyondGrowthPart_spendsShortestSleeveFirst()
    // taxable 100k with 80k reserved; withdraw 50k -> 50k left, reservation 50k with ultrashort drained first
@Test void snapshotRestore_restoresReservation()
@Test void placementFor_traditionalFirst_putsTraditionalFirst()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement `TentReservation` and the `MultiPool` integration**

Keep the tent logic in `TentReservation`. `MultiPool` only wires it in, and `PoolStrategy.java` must not grow by more than the wiring needs. If `MultiPool` needs new small helpers, add them beside the code they serve.

- [ ] **Step 4: Run the gate**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS, with the goldens, `PoolStrategyCharacterizationTest` and `EngineInvariantsTest` unedited.

- [ ] **Step 5: Commit**

Subject: `feat(projection): hold a bond tent as a reservation inside the pools`. The body explains the reservation model and the untaxed-relabel simplification.

---

### Task 4: Deterministic engine builds and holds the tent

**Files:**
- Create in `backend/wealthview-core/src/main/java/com/wealthview/core/projection/`:
  - `BondTentAssumptions.java`
  - `dto/BondTentMixSizing.java`, `dto/BondTentCoveredYear.java`, `dto/BondTentSizing.java`, `dto/BondTentYear.java`, `dto/BondTentResult.java`
- Modify:
  - `.../dto/ProjectionRunDetail.java`
  - `.../dto/ProjectionRunResult.java`
  - `.../ProjectionService.java`
  - `backend/wealthview-api/src/main/java/com/wealthview/api/dto/ProjectionRunResponse.java`
  - `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java`
- Test:
  - `backend/wealthview-projection/src/test/java/com/wealthview/projection/DeterministicProjectionEngineBondTentTest.java`
  - `.../projection/BondTentGoldenFileTest.java`, with fixtures `backend/wealthview-projection/src/test/resources/golden/bond-tent-early-retiree-input.json`, `bond-tent-early-retiree.json` and `bond-tent-early-retiree.tent.json`
  - `ProjectionRunResponse` serialization: extend the existing test in api.

**Interfaces:**
- Consumes:
  - Task 1's `BondTentConfig` and `ScenarioParams.bondTent()`.
  - Task 2's `BondTentSizer.size/mixRealYield`.
  - Task 3's `PoolStrategy.configureTent/topUpTent/tentTotal/tentByMaturity` and `TentSetup.placementFor`.
  - Plan 2's `BondMaturityReturnsProvider`.
- Produces:
  - `@Component public class BondTentAssumptions(CapitalMarketAssumptionsProvider, BondMaturityReturnsProvider)`:
    - `public Map<BondMaturity, Double> realReturns()` returns `BondMaturityReturnsProvider.geometricMeansOf(maturityProvider.alignedTo(cma.matrix(false)))`, cached after the first call.
  - DTO records:
    - `BondTentMixSizing(String name, BigDecimal realYield, BigDecimal target, BigDecimal percentOfPortfolio, BigDecimal ruleOfThumb, BondTentSizingReason reason, BigDecimal reasonAmount)`
    - `BondTentCoveredYear(int year, BigDecimal withdrawal, BigDecimal fraction)`
    - `BondTentSizing(BigDecimal projectedPortfolioAtRetirement, BigDecimal coverageYears, List<BondTentMixSizing> mixes, List<BondTentCoveredYear> coveredYears)`
    - `BondTentYear(int year, BondMaturityValues balances, BigDecimal total, @Nullable BigDecimal buildUpTarget)`
    - `BondTentResult(BondTentSizing sizing, List<BondTentYear> years)`
  - `ProjectionRunDetail` gains a 5th component, `@Nullable BondTentResult bondTent`, keeping a 4-arg back-compat constructor.
  - `ProjectionRunResult` gains `@Nullable BondTentResult bondTent` last; its back-compat constructors pass `null`. `ProjectionService` passes it through.
  - `ProjectionRunResponse` gains `@JsonInclude(NON_NULL) @Nullable BondTentResult bondTent`, last.
  - `DeterministicProjectionEngine`:
    - A new 8-arg constructor adds `@Nullable BondTentAssumptions bondTentAssumptions` and carries `@Autowired`. The 7-arg constructor delegates with `null`.
    - `public BondTentSizing sizeBondTent(ProjectionInput input, BondTentConfig config)` runs pass one only and sizes every mix of `config`. Task 5 calls it.
    - `runDetailed(input)`:
      - The tent is active iff `params.bondTent() != null` and the run resolves a spending plan.
      - If it is active but `bondTentAssumptions == null`, the engine throws `IllegalStateException("A bond tent is configured but bond return assumptions are not wired")`.
      - When active, it runs pass one (the same run with the tent treated as absent), sizes every mix (Builder's calls 2–4 and 11), then runs pass two with the plan mix's target. The build-up line follows Builder's call 6, applied after contributions and before growth.
      - Pool returns use `geometricMeans(false)` whenever the tent is active.
      - It returns pass two's result with `bondTent` set. `BondTentYear.buildUpTarget` is `L(y)` for every year in `[start, max(retirementYear, currentYear)]` and `null` otherwise.
      - When the tent is inactive, there is a single pass and `bondTent == null`.

- [ ] **Step 1: Write the failing tests**

```java
// DeterministicProjectionEngineBondTentTest
// Fixtures: expectedReturn overrides; tier spending profile; BondTentAssumptions mocked to return
// {ULTRASHORT .005, SHORT .013, INTERMEDIATE .018, LONG .026}
@Test void runDetailed_noTent_hasNoBondTentResult()
@Test void runDetailed_tentWithoutSpendingPlan_ignoresTent()              // bondTent null; yearlyData equals the no-tent run
@Test void runDetailed_tent_reachesTargetAtRetirement()
    // first retired year: buildUpTarget == plan-mix target, and total == target x Σ share_m (1 + r_m) within 1.00
    // (the year's row is recorded after that year's growth)
@Test void runDetailed_buildUpThreeYears_followsStraightLine()
    // buildUpTarget is 0, target/3, 2·target/3 for the three pre-retirement years and target for the retirement year
@Test void runDetailed_buildUpStartBeforeToday_compressesRamp()          // build-up 10, retirement in 4 -> line over 4 years from today
@Test void runDetailed_retiredAlready_fundsFullTargetInFirstYear()
@Test void runDetailed_tentNeverDrawnInGoodProjection()                  // tent total never decreases after retirement
@Test void runDetailed_tentCostsGrowth()                                 // final balance below the no-tent run's when bonds earn less than the pools
@Test void runDetailed_tentWithDepressionYearsFlag_usesDefaultWindow()   // CMA mock: geometricMeans(true) never called
@Test void runDetailed_sizingCoversEveryMix()                            // three mixes -> three BondTentMixSizing, plan mix first
@Test void runDetailed_tentConfiguredWithoutAssumptions_throws()
@Test void sizeBondTent_matchesRunDetailedSizing()

// BondTentGoldenFileTest — same harness and update flag as ProjectionGoldenFileTest
// Scenario: age 57, retires in 3 years; taxable 400k + traditional 600k; tier spending 60k;
// SS at 67; coverage 5; build-up 3; one mix .2/.3/.5/0; yields .0394/.0465/.048/.0539.
@Test void bondTentEarlyRetiree_matchesGoldens()                          // pins both result JSON and BondTentResult JSON

// ProjectionRunResponse serialization (api)
@Test void serialize_noTent_omitsBondTentKey()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

- `BondTentAssumptions`, the DTOs and the run-detail/result/response plumbing come first.
- Then the engine:
  - Pass one and the sizing.
  - Pass two with `configureTent` (built from `TentSetup.placementFor(resolvedWithdrawalOrder)`, the plan mix shares, `realReturns()` and the config's yields) and `topUpTent(L(y))` in `processYear`.
  - Collect one `BondTentYear` per projected year after the year is processed.
- Generate the golden with `-Dupdate.golden=true` once. Inspect it by hand:
  - the reservation reaches the target at retirement;
  - it is never drawn;
  - the yearly pool totals still sum to `endBalance`.

  Then commit it.

- [ ] **Step 4: Run the gates**

Run:
```
cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs
cd backend && mvn -q -T1 -pl wealthview-api -am verify -DskipITs
```
Expected: BUILD SUCCESS for both. The six goldens and the Monte Carlo pins are unedited.

- [ ] **Step 5: Commit**

Subject: `feat(projection): build and hold a bond tent in the deterministic projection`. The body covers the two passes, the line, the placement, the legacy depression-years guard and the new golden.

---

### Task 5: Tent sizing endpoint

**Files:**
- Modify:
  - `backend/wealthview-core/src/main/java/com/wealthview/core/projection/ProjectionEngine.java`
  - `.../ProjectionService.java`
  - `backend/wealthview-api/src/main/java/com/wealthview/api/controller/ProjectionController.java`
- Test:
  - `ProjectionServiceTest` (extend, core)
  - `ProjectionControllerTest` (extend, api)
  - `backend/wealthview-app/src/test/java/com/wealthview/app/it/BondTentSizingIT.java`. Follow the naming and base class of the existing ITs there.

**Interfaces:**
- Consumes: Task 4's `DeterministicProjectionEngine.sizeBondTent` and `BondTentSizing`, and Task 1's `ScenarioParamsValidator.validateBondTent`.
- Produces:
  - `ProjectionEngine` gains `default BondTentSizing sizeBondTent(ProjectionInput input, BondTentConfig config)`, which throws `UnsupportedOperationException`. `DeterministicProjectionEngine` overrides it.
  - `ProjectionService` gains `@Transactional(readOnly = true) public BondTentSizing sizeBondTent(UUID tenantId, UUID scenarioId, BondTentConfig config)`:
    - It looks the scenario up by tenant, throwing `EntityNotFoundException` if it is absent.
    - It runs `validateBondTent(config, savedParams.includeDepressionYears())`.
    - If the saved scenario has no active spending plan it throws `IllegalArgumentException("A bond tent needs a spending plan: choose a spending profile or optimized plan first")`.
    - Otherwise it builds the input exactly as `runProjection` does and calls the engine.
  - `ProjectionController` gains `POST /api/v1/projections/{id}/bond-tent/sizing` with a `@Valid @RequestBody BondTentConfig` body. It returns 200 with a `BondTentSizing` and is tenant-scoped by the principal.

- [ ] **Step 1: Write the failing tests**

```java
// ProjectionControllerTest (MockMvc)
@Test void sizeBondTent_valid_returns200WithSnakeCaseSizing()     // $.mixes[0].percent_of_portfolio, $.covered_years exist
@Test void sizeBondTent_invalidShares_returns400()                 // service throws IllegalArgumentException -> 400 envelope
@Test void sizeBondTent_unknownScenario_returns404()
// ProjectionServiceTest
@Test void sizeBondTent_noSpendingPlan_throws()
@Test void sizeBondTent_otherTenantsScenario_throwsNotFound()
// BondTentSizingIT (Testcontainers, real HTTP)
@Test void sizeBondTent_scenarioWithSpendingProfile_returnsPositiveTargetPerMix()
@Test void sizeBondTent_otherTenant_returns404()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-api -am test-compile`
Expected: compilation FAILS.

- [ ] **Step 3: Implement the engine default, the service method and the controller mapping**

- [ ] **Step 4: Run the gates**

Run:
```
cd backend && mvn -q -T1 -pl wealthview-api -am verify -DskipITs
cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true
```
Expected: BUILD SUCCESS for both.

- [ ] **Step 5: Commit**

Subject: `feat(api): size a bond tent for a saved scenario`

---

### Task 6: Scenario form "Bond tent" section and docs

**Files:**
- Create:
  - `frontend/src/components/scenario/ScenarioTentSection.tsx` and `ScenarioTentSection.test.tsx`
  - `frontend/src/components/scenario/bondTentDefaults.ts`
- Modify:
  - `frontend/src/types/projection.ts`
  - `frontend/src/api/projections.ts`
  - `frontend/src/components/ScenarioForm.tsx` and `ScenarioForm.test.tsx`
  - `frontend/src/components/scenario/ScenarioBasicsSection.tsx` and its test
  - `docs/user-guide/<the page that documents the scenario form>`. Find it, and add a "Bond tent" section.
  - `CHANGELOG.md` ([Unreleased] → Added)

**Interfaces:**
- Consumes: Task 1's JSON shape (`bond_tent`) and Task 5's `POST /projections/{id}/bond-tent/sizing` → `BondTentSizing` (snake_case).
- Produces:
  - Types `BondMaturityValues`, `BondTentMix`, `BondTentConfig`, `BondTentMixSizing`, `BondTentSizing`, `BondTentYear` and `BondTentResult` (snake_case fields).
  - `CreateScenarioRequest.bond_tent?: BondTentConfig | null` and `ProjectionResult.bond_tent?: BondTentResult | null`.
  - `sizeBondTent(scenarioId: string, config: BondTentConfig): Promise<BondTentSizing>`.
  - `bondTentDefaults.ts`:
    - `DEFAULT_TENT_YIELDS = { ultrashort: 0.0394, short: 0.0465, intermediate: 0.048, long: 0.0539 }`, with the comment "FRED monthly averages, September 2026: TB3MS, GS2, GS5, and the midpoint of GS20/GS30. Refresh at release."
    - `DEFAULT_TENT_MIX = { name: 'Balanced', shares: { ultrashort: 0.2, short: 0.3, intermediate: 0.5, long: 0 } }`
    - `DEFAULT_COVERAGE_YEARS = 5`, `DEFAULT_BUILD_UP_START_YEARS = 5`
    - `EXAMPLE_FUNDS = { ultrashort: 'SGOV', short: 'VGSH', intermediate: 'VGIT', long: 'TLT' }`

**Behavior and copy (exact strings):**
- **On/off.** A checkbox, "Use a bond tent".
  - When the Spending Plan is "None", the checkbox is disabled, with the note: "A bond tent needs a spending plan. Choose a spending profile or an optimized plan above."
- **Coverage.** A select from 1 to 10 in 0.5 steps, labelled "Years of spending to cover".
- **Build-up start.** A number input, labelled "Start building this many years before retirement" (0–40).
- **Mixes.**
  - Each mix is a chip (name editable), up to 3, with "Add mix". The first chip is labelled "(plan)".
  - Each mix has four share inputs in %, labelled with the maturity and its example fund, e.g. "Short (VGSH)".
  - A sum indicator reads "Shares add up to N%" and turns into an error unless it is 100.
- **Yields.** "Today's yields" offers four % inputs, prefilled from `DEFAULT_TENT_YIELDS`.
- **Hint.** When the selected mix is 100% ultrashort, show exactly:
  > 100% ultrashort is a classic cash reserve. Moving part of it into short or intermediate bonds usually earns more, at the cost of some price swings.
- **Rules line**, read-only:
  > The tent is drawn only when stocks are below their previous high, shortest bonds first. It is never refilled, and whatever is left stays in bonds.
- **Sizing box.**
  - **Saved scenario:** call `sizeBondTent` (debounced 500 ms) whenever the tent config is valid.
    - Plan-mix line: "Tent size needed: {money(target)} · {pct(percent_of_portfolio)} of your projected {money(projected_portfolio_at_retirement)} at retirement".
    - One line per other mix, in the same format, prefixed by its name.
    - Then a "Why it differs from the rule of thumb ({money(rule_of_thumb)})" line. Its text depends on the reason, with X = `money(abs(reason_amount))`:
      - SPENDING_LEVEL: "Your planned spending in these years is {higher|lower} than 4% of your portfolio."
      - INCOME: "Income such as Social Security, a pension or rent pays for about X of these years."
      - WITHDRAWAL_TAX: "About X of the withdrawals go to income tax."
      - MIX_YIELDS: "This mix's yields earn about X while the tent is spent down."
    - Finally the covered withdrawals by year, with partial years marked.
  - **Unsaved scenario:** "Save the scenario to see how big the tent needs to be."
- **Depression years.** While the tent is on, the "Include 1928–1971 market history" checkbox is unchecked and disabled, with the helper text: "Not available with a bond tent: bond maturity data starts in 1954."
- **Form state.** `buildInitialFields` parses `params_json.bond_tent`. `handleSubmit` sends `bond_tent` only when the tent is on, and omits the key entirely otherwise.

- [ ] **Step 1: Write the failing tests** (Vitest + React Testing Library)

`ScenarioTentSection.test.tsx`:
- `renders defaults when turned on`: coverage 5, build-up 5, Balanced 20/30/50/0, yields prefilled.
- `disables the tent with a note when there is no spending plan`
- `flags shares that do not add up to 100%`
- `shows the cash-reserve hint for a 100% ultrashort mix`
- `limits mixes to three`
- `calls sizeBondTent for a saved scenario and renders the sizing lines`: mock the api.
- `asks to save first for an unsaved scenario`

`ScenarioForm.test.tsx`:
- `round-trips bond_tent from params_json into the submit payload`
- `omits bond_tent when the tent is off`

`ScenarioBasicsSection` test:
- `disables depression years while a tent is on`

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npm run test -- ScenarioTentSection ScenarioForm ScenarioBasicsSection`
Expected: FAIL.

- [ ] **Step 3: Implement the section, the types, the api function and the form wiring**

Follow the existing section props pattern (`{ fields, setField, ... }`), with separate state for the nested config, like `accounts`.

Docs:
- Write the user-guide section: what a tent is, how coverage and sizing work, the build-up, placement, and that the optimizer becomes tent-aware in a later release.
- Add the CHANGELOG [Unreleased] → Added entry.

- [ ] **Step 4: Run the gates**

Run: `cd frontend && npm run test:coverage && npm run typecheck && npm run lint`
Expected: all pass, with coverage floors held.

- [ ] **Step 5: Commit**

Subject: `feat(frontend): configure and size a bond tent on the scenario form`
