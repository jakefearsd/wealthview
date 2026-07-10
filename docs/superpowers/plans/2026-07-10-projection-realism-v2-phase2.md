# Projection Realism v2 — Phase 2 (Optimizer: Success, Confidence, Reproducibility, Tax-Aware Reporting) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Monte Carlo guardrail optimizer's success metric meaningful and consistent (essentials-funded, not depletion-only), recalibrate the confidence presets to planning norms, make runs reproducible, and make the reported statistics tax-aware and computed on the same paths the plan was optimized against.

**Architecture:** Add a per-trial "essential floor funded every year" success flag; change the optimizer's search objective from "terminal balance at a percentile ≥ target" to "success-rate ≥ target confidence"; recalibrate the risk-tolerance→confidence presets; derive a deterministic RNG seed from the scenario so runs reproduce; and pass the already-computed marginal tax rates into the reporting simulation (which already reuses the search's return sequences) so the reported success/percentiles deduct withdrawal tax.

**Tech Stack:** Java 25, Spring Boot 4.1, Maven multi-module, PostgreSQL 16 (Testcontainers ITs), JUnit 5 + Mockito + AssertJ.

## Global Constraints

- Java 25 idioms: records for DTOs/value objects, sealed interfaces, `var` only when type is obvious. No wildcard imports.
- Money is `BigDecimal` / `numeric(19,4)`; the MC hot path uses `double` exactly where it already does (per-pool return arrays, balances) — do not introduce `BigDecimal` into the trial loop.
- Constructor injection only. Return `Optional`/empty, never null, from public finders.
- Tests: AssertJ `assertThat` only. Unit test names `methodUnderTest_stateOrInput_expectedResult`. Testcontainers PostgreSQL 16 for ITs (never H2).
- TDD: failing test first, minimal impl. One logical change per commit; conventional-commit messages with a body for `feat`/`fix`/`refactor`.
- Per-task gate build (catches PMD/CPD/SpotBugs/Checkstyle/JaCoCo, which `-Dtest=` skips): `mvn -pl <module> -am verify -DskipITs -B` FOREGROUND, Bash `timeout` 600000. Never background a build. Coverage floors: core/projection ≥90% line; never lower a branch floor.
- **Success definition (the spec's single definition, used by BOTH the optimizer and the report):** a trial "succeeds" if the essential floor spending is funded every year (household income + drawable portfolio resources ≥ the year's essential floor, every year). Discretionary spending may flex down without counting as failure. This replaces the old depletion-only (`finalBalance ≤ 0`) report metric AND the old terminal-balance-at-percentile optimizer objective.
- **Confidence = target success probability** (semantics changed by this phase). The `confidenceLevel` field on `GuardrailOptimizationInput`/`GuardrailOptimizationRequest` IS the explicit target-success-probability override.
- Do NOT `git push`. Commit on `main` (no feature branches). Golden/characterization test values WILL change (success metric + tax-aware reporting) — regenerate them deliberately with arithmetic/direction justification, never blindly.

## Context from Phase 1 (already true — do not redo)

- Both engines are allocation-driven and real-terms. The MC generates per-pool real return sequences **once** in `OptimizationContextBuilder.build` (seeded from `input.seed()`), stored on the context as `taxableReturns[t]/traditionalReturns[t]/rothReturns[t]`.
- **The search and the report already reuse the SAME return sequences** (`SustainabilitySearch.isSustainable` reads `ctx.taxableReturns()[t]`; `GuardrailResponseBuilder` reads `ctx.sim().taxableReturns()[t]`). So "optimize vs. report share the same paths" is ALREADY solved — the only reproducibility gap is that `input.seed()` is `null` in production.
- `GuardrailResponseBuilder` currently passes `marginalRateByYear = null` into the reporting sim (line ~81) → reported stats deduct no withdrawal tax. `TrialSimulator.simulateTrial` already deducts withdrawal tax when `marginalRateByYear` is non-null.
- `TrialSimulator.TrialResult` = `(double finalBalance, double minBalance, double[] yearBalances, boolean traditionalExhausted)`. `simulateTrial(income, surplusTax, floors, discretionary, years, config)` — `floors[]` is the essential floor per year, `discretionary[]` the flexible part.

## File structure (Phase 2)

**Modify:**
- `wealthview-projection/.../TrialSimulator.java` — add `boolean success` to `TrialResult`; track essential-floor shortfall in `simulateTrial`.
- `wealthview-projection/.../SustainabilitySearch.java` — `isSustainable` objective → success-rate ≥ target; terminal target/portfolio floor become optional bequest constraints.
- `wealthview-projection/.../GuardrailResponseBuilder.java` — pass real `marginalRateByYear` (tax-aware); compute reported success rate from `result.success()`.
- `wealthview-core/.../projection/GuardrailProfileService.java` — recalibrate confidence presets; derive + pass a deterministic seed.
- `wealthview-core/.../projection/dto/GuardrailProfileResponse.java` — add `successProbability` (or redefine the failure-rate field) — see Task 5.
- Tests: the MC characterization + optimizer tests (`MonteCarloSpendingOptimizerTest`, `MonteCarloSpendingOptimizerCharacterizationTest`, `SustainabilitySearchTest`) — regenerate affected values.

---

## Task 1: Essential-floor "success" flag on `TrialResult`

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialSimulator.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/TrialSimulatorReturnTest.java` (extend)

**Interfaces:**
- Consumes: existing `simulateTrial` signature.
- Produces: `TrialResult` gains a trailing `boolean success` component. `success == true` iff for every year `income[y] + drawnFromPortfolio[y] ≥ floors[y]` (essential floor funded every year). `drawnFromPortfolio[y]` = `drawn.total()` (pools drawn) + cash drawn from the reserve that year.

- [ ] **Step 1: Write the failing test**
```java
// add to TrialSimulatorReturnTest
@Test
void simulateTrial_essentialFloorUnfundableInAYear_marksNotSuccess() {
    var sim = new TrialSimulator();
    // Tiny portfolio, no income, a floor larger than the portfolio can ever supply → shortfall.
    double[] flatNoReturn = {0.0, 0.0};
    var config = new TrialSimulator.SimulationConfig(
            100.0, 0.0, 0.0, "taxable_first", null,
            null, null, 60, null, 0, 0.0, false,
            flatNoReturn, flatNoReturn, flatNoReturn);
    double[] income = {0.0, 0.0};
    double[] zero = {0.0, 0.0};
    double[] floors = {80.0, 80.0};        // year 2 floor (80) unfundable: only ~20 left
    double[] discretionary = {0.0, 0.0};

    var result = sim.simulateTrial(income, zero, floors, discretionary, 2, config);

    assertThat(result.success()).isFalse();
}

@Test
void simulateTrial_floorFundedEveryYear_marksSuccess() {
    var sim = new TrialSimulator();
    double[] flatNoReturn = {0.0, 0.0};
    var config = new TrialSimulator.SimulationConfig(
            1000.0, 0.0, 0.0, "taxable_first", null,
            null, null, 60, null, 0, 0.0, false,
            flatNoReturn, flatNoReturn, flatNoReturn);
    double[] income = {0.0, 0.0};
    double[] zero = {0.0, 0.0};
    double[] floors = {50.0, 50.0};
    double[] discretionary = {0.0, 0.0};

    var result = sim.simulateTrial(income, zero, floors, discretionary, 2, config);

    assertThat(result.success()).isTrue();
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection test -Dtest=TrialSimulatorReturnTest`
Expected: FAIL — `TrialResult` has no `success()` accessor / constructor arity mismatch.

- [ ] **Step 3: Implement**

In `TrialResult`, add the trailing component:
```java
record TrialResult(
        double finalBalance,
        double minBalance,
        double[] yearBalances,       // null when trackYearBalances is false
        boolean traditionalExhausted,
        boolean success
) {}
```
In `simulateTrial`, before the year loop add `boolean essentialFloorMet = true;`. Inside the loop, capture the cash balance before withdrawals and, right after the `applyTrialWithdrawals` call (the line that returns the updated `cashBalance`), compute what the portfolio supplied and compare to the essential floor:
```java
double cashBeforeWithdrawals = cashBalance;   // add just before applyTrialWithdrawals(...)
cashBalance = applyTrialWithdrawals(pools, cashBalance, drawn, withdrawalTax,
        withdrawal, spending, hasPools, config.cashReserveYears(), portfolioReturn);
double cashDrawn = Math.max(0, cashBeforeWithdrawals - cashBalance);
double resourcesForSpending = income[y] + drawn.total() + cashDrawn;
if (resourcesForSpending < floors[y] - 1e-6) {
    essentialFloorMet = false;
}
```
Update every `new TrialResult(...)` return in the method to append `essentialFloorMet` as the final argument (there is one return at the end of `simulateTrial`; add `essentialFloorMet`).

- [ ] **Step 4: Run to verify it passes + no regression in the file's tests**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection test -Dtest=TrialSimulatorReturnTest,TrialSimulatorTest`
Expected: new tests PASS. Other `TrialSimulator` unit tests may reference `TrialResult` construction/deconstruction — update them to the new arity (append the expected `success` boolean; for existing happy-path fixtures it is `true`).

- [ ] **Step 5: Gate + commit**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection -am verify -DskipITs -B` (fix any gate finding in the changed file).
```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialSimulator.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/TrialSimulatorReturnTest.java
git commit -m "feat(projection): add essential-floor-funded success flag to TrialResult

A trial succeeds when household income plus drawable portfolio resources
cover the essential floor every year. This is the single success definition
the optimizer objective and the reported statistics will both use."
```

---

## Task 2: `SustainabilitySearch` — success-rate objective

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/SustainabilitySearch.java` (`isSustainable`, lines ~266-315)
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/SustainabilitySearchTest.java`

**Interfaces:**
- Consumes: `TrialResult.success()` (Task 1); `ctx.confidenceLevel()` (now the target success probability).
- Produces: `isSustainable` returns true iff `successRate ≥ ctx.confidenceLevel()`, where `successRate = (# trials with success==true) / trialCount`. The terminal-target and portfolio-floor checks remain but ONLY as additional constraints when set (`terminalTarget > 0` / `portfolioFloor > 0`) — they are bequest goals layered on top of the success gate, not the primary gate.

- [ ] **Step 1: Write the failing test**
```java
// SustainabilitySearchTest — a deterministic (zero-volatility) context where success rate is known.
@Test
void isSustainable_successRateBelowTarget_returnsFalse() {
    // Build a SearchContext with a target confidence of 0.90 and a spending level
    // that funds the essential floor in fewer than 90% of trials → not sustainable.
    // (Use the test's existing SearchContext builder; set confidenceLevel=0.90.)
    // assertThat(search.isSustainable(ctx, floors, tooHighDiscretionary)).isFalse();
}

@Test
void isSustainable_successRateMeetsTarget_returnsTrue() {
    // Same context, a spending level the floor funds in ≥90% of trials → sustainable.
    // assertThat(search.isSustainable(ctx, floors, safeDiscretionary)).isTrue();
}
```
> Flesh out against the existing `SustainabilitySearchTest` `SearchContext` construction (open the test to see how `ctx`, `floors`, `discretionary`, and the return arrays are built; mirror it, setting `confidenceLevel`). The two tests must bracket the target: one spending level that succeeds in ≥ target fraction of trials, one below.

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection test -Dtest=SustainabilitySearchTest`
Expected: FAIL — current objective is terminal-balance-at-percentile, not success-rate.

- [ ] **Step 3: Implement**

Replace the tail of `isSustainable` (the `Arrays.sort(finalBalances)` + terminal-target check) with a success-rate primary gate, keeping terminal/floor as optional add-ons. Collect `success` per trial in the loop, then:
```java
// inside the trial loop, alongside finalBalances[t]/minBalances[t]:
successFlags[t] = result.success();   // boolean[] successFlags = new boolean[trialCount];

// after the loop — primary gate: fraction of successful trials must meet the target
int successCount = 0;
for (boolean s : successFlags) {
    if (s) {
        successCount++;
    }
}
double successRate = (double) successCount / trialCount;
if (successRate < ctx.confidenceLevel()) {
    return false;
}

// optional bequest constraints (only when explicitly set)
if (ctx.terminalTarget() > 0) {
    Arrays.sort(finalBalances);
    if (PercentileCalculator.percentile(finalBalances, 1.0 - ctx.confidenceLevel()) < ctx.terminalTarget()) {
        return false;
    }
}
if (ctx.portfolioFloor() > 0) {
    Arrays.sort(minBalances);
    if (PercentileCalculator.percentile(minBalances, 1.0 - ctx.confidenceLevel()) < ctx.portfolioFloor()) {
        return false;
    }
}
return true;
```
Declare `boolean[] successFlags = new boolean[trialCount];` with the other per-trial arrays.

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection test -Dtest=SustainabilitySearchTest`
Expected: PASS.

- [ ] **Step 5: Gate + commit**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection -am verify -DskipITs -B`.
(Note: `MonteCarloSpendingOptimizerTest`/`...CharacterizationTest` values will shift — that's Task 6; if they fail here, note it and continue to commit this task's source + its own test, then regenerate in Task 6. If you prefer, defer the full `-am verify` green-check to Task 6 and here run only `-Dtest=SustainabilitySearchTest`.)
```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/SustainabilitySearch.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/SustainabilitySearchTest.java
git commit -m "feat(projection): optimize spending against essential-floor success rate

The sustainability gate now requires the fraction of trials that fund the
essential floor every year to meet the target confidence, replacing the
terminal-balance-at-percentile objective. Terminal target and portfolio
floor remain as optional bequest constraints."
```
> Because Tasks 2, 5 change MC outputs, run the combined regeneration + full green in Task 6.

---

## Task 3: Recalibrate confidence presets

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java` (`resolveConfidence`, lines ~330-341)
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/GuardrailProfileServiceTest.java` (or the existing service test)

**Interfaces:**
- Produces: `resolveConfidence` maps risk tolerance to planning-norm success-probability targets — `conservative → 0.95`, `moderate → 0.90`, `aggressive → 0.80` (was 0.85/0.70/0.60). An explicit `request.confidenceLevel()` still takes precedence.

- [ ] **Step 1: Write the failing test**
```java
// GuardrailProfileServiceTest (mirror existing service-test construction of the service + request)
@Test
void resolveConfidence_moderateRiskTolerance_returnsNinetyPercent() {
    // request with riskTolerance="moderate", confidenceLevel=null
    assertThat(service.resolveConfidence(request)).isEqualByComparingTo("0.90");
}

@Test
void resolveConfidence_explicitConfidence_overridesPreset() {
    // request with riskTolerance="aggressive", confidenceLevel=0.97
    assertThat(service.resolveConfidence(request)).isEqualByComparingTo("0.97");
}
```
> If `resolveConfidence` is private, either test it via the public `optimize`/input-building path that surfaces the confidence, or widen it to package-private for the test (the codebase uses package-private test seams elsewhere). Prefer package-private + a direct unit test.

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-core test -Dtest=GuardrailProfileServiceTest`
Expected: FAIL — moderate returns 0.70.

- [ ] **Step 3: Implement**

In `resolveConfidence` (lines ~333-339):
```java
if (request.riskTolerance() != null) {
    return switch (request.riskTolerance()) {
        case "conservative" -> new BigDecimal("0.95");
        case "moderate" -> new BigDecimal("0.90");
        case "aggressive" -> new BigDecimal("0.80");
        default -> DEFAULT_CONFIDENCE;
    };
}
```
Update the method's Javadoc to state these are TARGET SUCCESS PROBABILITIES (essential-floor-funded), not left-tail percentiles.

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-core test -Dtest=GuardrailProfileServiceTest`
Expected: PASS.

- [ ] **Step 5: Gate + commit**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-core -am verify -DskipITs -B`.
```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/GuardrailProfileServiceTest.java
git commit -m "feat(core): recalibrate risk-tolerance confidence presets to planning norms

conservative 0.85->0.95, moderate 0.70->0.90, aggressive 0.60->0.80. These
are now target SUCCESS PROBABILITIES (essential floor funded every year),
not left-tail percentiles. Explicit confidenceLevel still overrides."
```

---

## Task 4: Deterministic per-scenario seed (reproducibility)

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java` (seed derivation + pass into `GuardrailOptimizationInput`, ~line 293-302)
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/GuardrailProfileServiceTest.java`

**Interfaces:**
- Consumes: the existing scenario-identifying hash logic in `GuardrailProfileService` (lines ~261-276 build a SHA hex string from scenario params).
- Produces: `buildOptimizationInput` passes a deterministic `Long seed` derived from the scenario (accounts/allocations/spending/inflation/params) instead of `null`. Same scenario → same seed → reproducible optimization; different scenario → different seed. `OptimizationContextBuilder` already consumes `input.seed()`.

- [ ] **Step 1: Write the failing test**
```java
@Test
void buildOptimizationInput_sameScenario_producesStableSeed() {
    // Build the input twice for the same scenario/request → identical non-null seed.
    Long seedA = service.buildOptimizationInput(scenario, request, ...).seed();
    Long seedB = service.buildOptimizationInput(scenario, request, ...).seed();
    assertThat(seedA).isNotNull().isEqualTo(seedB);
}

@Test
void buildOptimizationInput_differentInflation_producesDifferentSeed() {
    Long seedA = service.buildOptimizationInput(scenario, requestInflation2pct, ...).seed();
    Long seedB = service.buildOptimizationInput(scenario, requestInflation4pct, ...).seed();
    assertThat(seedA).isNotEqualTo(seedB);
}
```
> Match the real signature of the input-building method (open `GuardrailProfileService` — the method around line 293 that constructs `new GuardrailOptimizationInput(...)`). If it is private, exercise the seed via the public path or widen to package-private for the test.

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-core test -Dtest=GuardrailProfileServiceTest`
Expected: FAIL — seed is currently `null`.

- [ ] **Step 3: Implement**

Add a helper that derives a stable `long` from the scenario-identifying string (reuse the same fields the existing hash at lines ~261-276 already assembles — scenario id, accounts/allocations, spending params, inflation):
```java
private static long deriveSeed(String scenarioSignature) {
    try {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(scenarioSignature.getBytes(StandardCharsets.UTF_8));
        long seed = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            seed = (seed << 8) | (hash[i] & 0xffL);
        }
        return seed;
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 unavailable", e); // never happens on a standard JRE
    }
}
```
Reuse the existing scenario-signature builder (the `StringBuilder sb` used for the cache hash) as `scenarioSignature`; if that builder is local to the cache-key method, extract it to a small private method `scenarioSignature(scenario, request)` and call it from both the cache key and `deriveSeed`. In the `new GuardrailOptimizationInput(...)` construction, pass `deriveSeed(scenarioSignature(...))` for the `seed` argument instead of `null`.

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-core test -Dtest=GuardrailProfileServiceTest`
Expected: PASS.

- [ ] **Step 5: Gate + commit**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-core -am verify -DskipITs -B`.
```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/GuardrailProfileServiceTest.java
git commit -m "feat(core): seed the guardrail optimizer deterministically from the scenario

The RNG seed is derived from the scenario's identifying inputs (accounts,
allocations, spending, inflation) instead of null, so an unchanged scenario
reproduces the same optimization run-to-run and changes when inputs change.
The search and the reporting simulation already share these seeded return
sequences, so both stay consistent."
```

---

## Task 5: Tax-aware reporting + consistent success metric

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/GuardrailResponseBuilder.java` (terminal sim, lines ~71-116)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailProfileResponse.java` (add `successProbability`)
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/MonteCarloSpendingOptimizerTest.java` (add a targeted assertion)

**Interfaces:**
- Consumes: the run's marginal tax rates (already computed in `OptimizationContextBuilder` via `MarginalRateCalculator.compute(...)` and stored on the context's `TaxContext` — locate the accessor, e.g. `ctx.taxCtx().marginalRateByYear()` or the equivalent field the response context exposes); `TrialResult.success()` (Task 1).
- Produces: the reporting sim passes the real `marginalRateByYear` (not `null`) so reported final balances/percentiles deduct withdrawal tax; the reported `successProbability` = fraction of reporting-sim trials with `success == true` (the same definition the optimizer uses). `GuardrailProfileResponse` carries `successProbability` (BigDecimal, snake_case `success_probability`). The legacy `failureRate` field is redefined to `1 − successProbability` (fraction NOT meeting essentials) for wire back-compat, OR kept as-is with `successProbability` added — see Step 3.

- [ ] **Step 1: Write the failing test**
```java
// MonteCarloSpendingOptimizerTest — a pool scenario (has traditional balance) so withdrawal tax applies.
@Test
void optimize_poolScenario_reportedSuccessProbabilityIsTaxAware() {
    // Run optimize() on a seeded pool scenario. Assert:
    //  (a) response.successProbability() is present and in [0,1];
    //  (b) it equals 1 - response.failureRate() (consistent metric), within 1e-9;
    // and, to prove tax-awareness changed reporting, that the reported median final balance
    // is <= the pre-change (no-tax) median for the SAME seed (compute/capture the expectation).
    // (Mirror an existing pool-scenario optimize() test for setup + seed.)
}
```
> Flesh out using an existing `MonteCarloSpendingOptimizerTest` pool-scenario test for the input/seed. The key assertions: `successProbability ∈ [0,1]`, `successProbability ≈ 1 - failureRate`, and tax-awareness reduces the reported median vs. the untaxed path (capture the concrete numbers when you regenerate in Task 6).

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection test -Dtest=MonteCarloSpendingOptimizerTest#optimize_poolScenario_reportedSuccessProbabilityIsTaxAware`
Expected: FAIL — `successProbability()` does not exist / reporting sim is not tax-aware.

- [ ] **Step 3: Implement**

(a) In `GuardrailResponseBuilder`, locate the run's marginal rates on the context (the `TaxContext`/`taxCtx` built in `OptimizationContextBuilder` holds `marginalRateByYear`; if the response context does not already expose it, thread it through — it is computed once at line ~100 of `OptimizationContextBuilder` and belongs on the shared context). Replace the `null` at the `marginalRateByYear` position (line ~81) with those marginal rates. Guard the non-pool case: when there is no traditional/roth balance, keep `null` (no withdrawal tax), matching the search.

(b) Track success in the reporting loop and compute the rate:
```java
int successCount = 0;
// inside the per-trial loop, after result = trialSimulator.simulateTrial(...):
if (result.success()) {
    successCount++;
}
// after the loop:
double successProbability = (double) successCount / ctx.sim().trialCount();
```
Replace the old `failureRate` computation (`finalBalances filter b<=0`) with `double failureRate = 1.0 - successProbability;` so the reported failure metric matches the essential-floor success definition.

(c) Add `BigDecimal successProbability` to `GuardrailProfileResponse` (snake_case `success_probability` — confirm the module's Jackson config emits snake_case globally, it does). Populate it in the `new GuardrailProfileResponse(...)` construction with `toBD(successProbability)`. Keep the existing `failureRate` field (now `1 - successProbability`). Update the response's factory/all constructors + any test asserting its arity.

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection test -Dtest=MonteCarloSpendingOptimizerTest#optimize_poolScenario_reportedSuccessProbabilityIsTaxAware`
Expected: PASS (after Task 6 regenerates the broader suite).

- [ ] **Step 5: Commit** (gate green deferred to Task 6, which regenerates the suite)
```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/GuardrailResponseBuilder.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailProfileResponse.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/MonteCarloSpendingOptimizerTest.java
git commit -m "feat(projection): tax-aware guardrail reporting on the essential-floor success metric

The reporting simulation now deducts withdrawal tax (passes the run's
marginalRateByYear instead of null) and reports successProbability =
fraction of trials that fund the essential floor every year — the same
definition the optimizer uses. failureRate is redefined as 1 - success."
```

---

## Task 6: Regenerate MC test values + full verify

**Files:**
- Modify: `MonteCarloSpendingOptimizerTest`, `MonteCarloSpendingOptimizerCharacterizationTest`, `SustainabilitySearchTest` (any pinned exact values that shifted)
- Test: whole backend `mvn verify`

**Interfaces:**
- Consumes: all prior tasks.

- [ ] **Step 1: Identify what changed and why**

The success-metric objective (Task 2), tax-aware reporting (Task 5), and the recalibrated presets (Task 3) shift MC optimizer outputs: recommended spending, `medianFinal`, `p10Final`, and the reported rate (now `successProbability`/`failureRate` on the essential-floor definition). Run the MC suite:
Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection test -Dtest=MonteCarloSpendingOptimizerCharacterizationTest,MonteCarloSpendingOptimizerTest,SustainabilitySearchTest`
Expected: some exact-value assertions FAIL.

- [ ] **Step 2: Regenerate deliberately (not blindly)**

For each failing exact-value assertion, confirm the new value is explained by the change and is SANE, then update it:
- Direction: with a HIGHER target (e.g. moderate 0.70→0.90) the optimizer recommends LOWER spending (stricter bar) → equal-or-lower recommended spend, higher terminal balances.
- Tax-aware reporting: pool-scenario reported `medianFinal`/`p10Final` are LOWER than before (tax deducted); `failureRate` may rise.
- `successProbability ∈ [0,1]` and `≈ 1 − failureRate`.
- Relational/invariant assertions (e.g. higher confidence ⇒ not-higher spending) must still hold — only exact numbers move.
Document each regenerated value's rationale in the commit body. If any value moves the WRONG direction, STOP and investigate — it signals a bug.

- [ ] **Step 3: Full verify (units + gates + Testcontainers ITs)**

Chunked (a full `verify` with app ITs can exceed the 10-min timeout):
Run: `cd /home/jakefear/source/wealthview/backend && mvn -f pom.xml clean install -DskipITs -B` (units + all 5 gates + install)
Then: `cd /home/jakefear/source/wealthview/backend && mvn -f pom.xml verify -pl wealthview-app -B` (app Testcontainers ITs — validates the guardrail endpoint end-to-end with the new response field)
Expected: BUILD SUCCESS both. Coverage floors met (core/projection ≥90% line; branch floors not lowered). If an app IT asserts the guardrail response shape, update it for the additive `success_probability` field.

- [ ] **Step 4: Commit**
```bash
git add backend
git commit -m "test(projection): regenerate MC values for the success-metric + tax-aware reporting

Recommended spending, terminal percentiles, and the reported rate shift under
the essential-floor success objective, recalibrated confidence presets, and
tax-aware reporting. Each regenerated value verified for direction and sanity;
relational invariants unchanged. Full verify (units + gates + app ITs) green."
```

---

## Self-review notes (author)

- **Spec coverage** (design doc "Phase 2 — Optimizer"): per-trial success flag → Task 1; `SustainabilitySearch` objective → Task 2; preset recalibration + explicit target → Task 3 (the existing `confidenceLevel` field is the explicit override); reproducible seed → Task 4; tax-aware reporting on the same paths → Task 5 (paths already shared per Phase 1; only the seed + `marginalRateByYear` were missing). Golden/characterization regen + full verify → Task 6.
- **Not in scope** (correctly deferred): the cheap Phase-1 follow-ups (V070 `DROP DEFAULT`, nominal-label doc sweep), the frontend (Phase 3), and the Tier-2/Tier-3 realism items (fees, RMD enforcement in the main projection, stochastic inflation, the 1972-2025 data-window tail-risk question).
- **Type consistency:** `TrialResult.success()` (Task 1) is consumed by `SustainabilitySearch` (Task 2) and `GuardrailResponseBuilder` (Task 5); `resolveConfidence`/`confidenceLevel` semantics (Task 3) align with the success-rate gate (Task 2); `successProbability` on `GuardrailProfileResponse` (Task 5) is the reported form of the same metric.
- **Verify-before-coding seams to confirm:** the exact name/visibility of `resolveConfidence` and the input-building method in `GuardrailProfileService`; where the run's `marginalRateByYear` is exposed on the response-builder context; the `SearchContext`/`TrialResult` construction sites in existing tests (arity updates).
```
