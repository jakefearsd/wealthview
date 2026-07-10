# RMDs in the Main Projection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce Required Minimum Distributions (physical model — force out, tax, reinvest after-tax excess to taxable) in the deterministic and Monte Carlo retirement withdrawal loops, which currently pass `rmdAmount = 0`.

**Architecture:** Compute the RMD each retired year from the *pre-growth* (prior-Dec-31) traditional balance and the IRS Uniform Lifetime divisor (reusing `RmdCalculator`), for ages at/after the SECURE 2.0 start age. In `MultiPool.executeWithdrawals` the RMD forces a traditional distribution beyond spending needs, taxes the whole distribution as ordinary income, and reinvests the after-tax excess into taxable. The RMD is computed before Roth conversions so it consumes conversion bracket headroom.

**Tech Stack:** Java 25, Spring Boot 4.1, Maven multi-module, PostgreSQL 16 (Testcontainers ITs), JUnit 5 + Mockito + AssertJ.

## Global Constraints

- Java 25 idioms: records/sealed types, no wildcard imports. `BigDecimal` for money in the deterministic path (`PoolStrategy`, `SCALE = 4`, `ROUNDING = HALF_UP`); `double` in the MC hot loop (`TrialSimulator`) exactly as the existing code does.
- Constructor injection only. Return `Optional`/empty, never null, from public finders.
- Tests: AssertJ `assertThat` only. Unit test names `methodUnderTest_stateOrInput_expectedResult`. Testcontainers PostgreSQL 16 for ITs (never H2).
- TDD: failing test first. One logical change per commit; conventional-commit messages with a body for `feat`/`fix`/`refactor`.
- Per-task gate build (catches PMD/CPD/SpotBugs/Checkstyle/JaCoCo — `-Dtest=` skips them): `mvn -pl <module> -am verify -DskipITs -B` FOREGROUND, Bash `timeout` 600000. Never background a build. Coverage floors: projection ≥90% line; never lower a branch floor.
- **RMD rules (verbatim):** RMD applies to the **traditional pool only**, for retired years where `age ≥ RmdCalculator.rmdStartAge(birthYear)` (73 if born < 1960, else 75). `rmd = priorYearEndTraditional ÷ RmdCalculator.distributionPeriod(age)`, capped at the current traditional balance; `priorYearEndTraditional` is the traditional balance BEFORE the current year's growth. Real-terms (no nominal conversion). Only `MultiPool` computes it; `SinglePool` (all-taxable) has no traditional pool.
- Golden/characterization values WILL change for traditional-heavy retirees at/after RMD age — regenerate deliberately with per-value direction justification (more tax in RMD years; traditional drains faster; taxable grows). Fixtures whose retiree never reaches RMD age, or always spends ≥ their RMD, must NOT change.
- Do NOT `git push`. Commit on `main`.

## Context from the current code (already true)

- `RmdCalculator` (projection): `static double distributionPeriod(int age)` (0 outside ages 72–120), `static double computeRmd(double priorYearEndBalance, int age)`, `static int rmdStartAge(int birthYear)`.
- `PoolStrategy.MultiPool.executeWithdrawals(BigDecimal need, int year, BigDecimal effectiveOtherIncome, BigDecimal conversionAmount, BigDecimal rmdAmount, int age)` — allocates the spend across pools, sets `taxableIncome = fromTraditional + effectiveOtherIncome + conversionAmount`, computes tax via `taxCalculator.computeDetailedTax`, deducts it via `deductFromPools`, returns `WithdrawalTaxResult(totalWithdrawn, taxLiability, fromTaxable, fromTraditional, fromRoth, taxSource)`. The `rmdAmount` param is currently only forwarded to the withdrawal-order strategy's bracket-space calc; it does NOT yet force a distribution. `MultiPool` has a private `BigDecimal traditional` field but no getter.
- `PoolStrategy.executeRothConversion(int year, BigDecimal effectiveOtherIncome)` — NO `rmdAmount` param yet.
- `DeterministicProjectionEngine.processYear`: `startBalance = pool.getTotal()` (line ~256) → `pool.applyGrowth()` (line ~265) → `processIncomeAndConversions(...)` (line ~268) → `retirementWithdrawalProcessor.process(rwCtx)` (line ~287). `age = year - ctx.birthYear()` and `ctx.birthYear()` are available.
- `RetirementWithdrawalProcessor.RetirementWithdrawalContext` — has `age`, `year`, no `birthYear`/`rmdAmount`. `process(...)` calls `pool.executeWithdrawals(portfolioNeed, year, effectiveOtherIncome, conversionAmount, BigDecimal.ZERO, age)` (the `ZERO` is `rmdAmount`).
- `TrialSimulator.SimulationConfig` — 15 fields (`initTaxable, initTraditional, initRoth, withdrawalOrder, marginalRateByYear, conversionByYear, conversionTaxByYear, retirementAge, dsBracketCeilingByYear, cashReserveYears, cashReturnRate, trackYearBalances, taxableReturns, traditionalReturns, rothReturns`). In `simulateTrial`, pools grow (`pools[1] *= (1 + traditionalReturn)`), then `applyTrialConversion(...)`, then the spend withdrawal via `splitWithdrawal(..., rmdAmount=0)`.

## File structure

**Modify:**
- `PoolStrategy.java` — add `getTraditional()`; force the physical RMD in `MultiPool.executeWithdrawals`; add an `rmdAmount` bracket-headroom param to the conversion path.
- `RetirementWithdrawalProcessor.java` — add `rmdAmount` to the context; forward it to `executeWithdrawals`.
- `DeterministicProjectionEngine.java` — snapshot pre-growth traditional; compute `rmd`; thread into the conversion + withdrawal.
- `TrialSimulator.java` — add `rmdStartAge` to `SimulationConfig`; force the physical RMD in `simulateTrial`; feed `applyTrialConversion`'s bracket hook.
- `OptimizationContextBuilder.java` — derive `rmdStartAge` from `birthYear`, pass it into the config.
- Tests/goldens: `PoolStrategyTest`/`MultiPoolDeepTest`, deterministic golden + characterization fixtures, `TrialSimulator`/MC characterization.

---

## Task 1: Physical RMD forcing in `MultiPool.executeWithdrawals` + `getTraditional()`

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/MultiPoolDeepTest.java` (extend)

**Interfaces:**
- Produces: `PoolStrategy.getTraditional()` → `BigDecimal` (`MultiPool` returns its traditional balance; `SinglePool` returns `BigDecimal.ZERO`). `MultiPool.executeWithdrawals`, when `rmdAmount > fromTraditional`, forces `extra = min(rmdAmount − fromTraditional, remaining traditional)` out of traditional, includes `fromTraditional + extra` in ordinary `taxableIncome`, and deposits `extra` (gross) into taxable — the tax on the full distribution flows through the existing `deductFromPools` path (which draws tax from taxable first, i.e. from the just-reinvested RMD proceeds). Reported `withdrawalFromTraditional` includes `extra`.

- [ ] **Step 1: Write the failing test**
```java
// MultiPoolDeepTest — RMD forces a traditional distribution beyond the spend, reinvested to taxable.
@Test
void executeWithdrawals_rmdExceedsSpendDraw_forcesExtraFromTraditionalIntoTaxable() {
    // Build a MultiPool: taxable=100000, traditional=500000, roth=0, single filer, a real FederalTaxCalculator.
    // (Mirror MultiPoolDeepTest's existing pool + tax-calc construction.)
    // Spend need is small so the normal draw takes taxable-first → fromTraditional ≈ 0.
    // Pass rmdAmount = 20000 (age 75).
    var result = pool.executeWithdrawals(new BigDecimal("10000"), 2025,
            BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("20000"), 75);

    // Traditional dropped by the full RMD (20000); taxable rose by the gross RMD (20000) minus the
    // spend draw it funded (0 here) minus the tax the cascade pulled from taxable.
    assertThat(pool.getTraditional()).isEqualByComparingTo("480000");
    assertThat(result.withdrawalFromTraditional()).isEqualByComparingTo("20000");
    assertThat(result.taxLiability()).isGreaterThan(BigDecimal.ZERO);   // RMD is taxed as ordinary income
}

@Test
void executeWithdrawals_spendDrawExceedsRmd_noForcedExtra() {
    // Traditional-first order, a large spend that draws > rmd from traditional → extra = 0, unchanged behavior.
    var result = pool.executeWithdrawals(new BigDecimal("60000"), 2025,
            BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("20000"), 75);
    // fromTraditional already >= 20000, so no extra forced; traditional == start - fromTraditional only.
    assertThat(result.withdrawalFromTraditional()).isGreaterThanOrEqualTo(new BigDecimal("20000"));
}
```
> Open `MultiPoolDeepTest` and mirror its `MultiPool` + `FederalTaxCalculator` construction (it already builds pool scenarios). Use a withdrawal order that makes `fromTraditional` small in test 1 (taxable-first) and large in test 2 (traditional-first).

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection test -Dtest=MultiPoolDeepTest`
Expected: FAIL — `getTraditional()` undefined / no forcing.

- [ ] **Step 3: Implement**

Add the interface method and both implementations:
```java
// in the PoolStrategy interface:
BigDecimal getTraditional();

// SinglePool:
@Override public BigDecimal getTraditional() { return BigDecimal.ZERO; }

// MultiPool:
@Override public BigDecimal getTraditional() { return traditional; }
```
In `MultiPool.executeWithdrawals`, right after the three `taxable/traditional/roth = ... .subtract(fromX)` lines and before computing `taxableIncome`, force the RMD excess:
```java
BigDecimal rmdExtra = BigDecimal.ZERO;
if (rmdAmount != null && rmdAmount.compareTo(fromTraditional) > 0) {
    rmdExtra = rmdAmount.subtract(fromTraditional).min(traditional).max(BigDecimal.ZERO);
    traditional = traditional.subtract(rmdExtra);
    taxable = taxable.add(rmdExtra);   // reinvest the gross RMD excess to taxable
}
BigDecimal traditionalOrdinaryIncome = fromTraditional.add(rmdExtra);
```
Change `taxableIncome` to use `traditionalOrdinaryIncome` instead of `fromTraditional`:
```java
BigDecimal taxableIncome = traditionalOrdinaryIncome.add(effectiveOtherIncome).add(conversionAmount);
```
In the returned `WithdrawalTaxResult`, report the traditional withdrawal INCLUDING the RMD excess:
```java
return new WithdrawalTaxResult(
        fromTaxable.add(fromTraditional).add(fromRoth), withdrawalTax,
        fromTaxable, traditionalOrdinaryIncome, fromRoth, withdrawalTaxSource);
```
> The existing conversion double-count guard uses `conversionAmount.add(effectiveOtherIncome)` as the base — unchanged; the RMD is part of the traditional-withdrawal marginal layer, which is exactly what that guard isolates. Keep `totalWithdrawn` = the spending draw (`fromTaxable+fromTraditional+fromRoth`); the RMD excess is an internal traditional→taxable move, not spending.

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection test -Dtest=MultiPoolDeepTest,PoolStrategyTest`
Expected: new tests PASS; existing pool tests unchanged (they pass `rmdAmount = 0` / `BigDecimal.ZERO` → `rmdExtra = 0` → no behavior change).

- [ ] **Step 5: Gate + commit**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection -am verify -DskipITs -B`.
```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/MultiPoolDeepTest.java
git commit -m "feat(projection): force physical RMD distribution in MultiPool.executeWithdrawals

When the RMD exceeds the traditional draw for spending, force the excess out
of traditional, include the full distribution in ordinary taxable income, and
reinvest the gross excess to taxable (tax paid via the existing cascade, which
draws from taxable first). Gated on a positive rmdAmount, so callers passing 0
are unaffected. Adds getTraditional()."
```

---

## Task 2: Compute + wire the RMD in the deterministic engine (+ conversion headroom, golden regen)

**Files:**
- Modify: `DeterministicProjectionEngine.java`, `RetirementWithdrawalProcessor.java`, `PoolStrategy.java` (conversion bracket param)
- Test: `DeterministicProjectionEngineWithdrawalTest.java` (add an RMD scenario); regenerate affected golden/characterization fixtures.

**Interfaces:**
- Consumes: `PoolStrategy.getTraditional()` + RMD forcing (Task 1); `RmdCalculator`.
- Produces: each retired year, `DeterministicProjectionEngine.processYear` snapshots the pre-growth traditional balance, computes `rmdAmount` (0 when `age < rmdStartAge(birthYear)` or divisor 0), passes it into `processIncomeAndConversions` (so the Roth conversion leaves bracket room for RMD income) and into the `RetirementWithdrawalContext` (physical enforcement). `RetirementWithdrawalContext` gains a `BigDecimal rmdAmount`; `PoolStrategy.executeRothConversion` gains a trailing `BigDecimal rmdAmount` that reduces the conversion's target-bracket headroom.

- [ ] **Step 1: Write the failing test**
```java
// DeterministicProjectionEngineWithdrawalTest — a traditional-heavy, low-spend retiree at RMD age.
@Test
void run_traditionalHeavyRetireeAtRmdAge_forcesRmdAndTaxesIt() {
    // birthYear so the retiree is >= 75 during the projection; a large traditional account, low spending,
    // taxable-first order so spending doesn't drain traditional. (Mirror the test file's scenario builder.)
    // Assert: in an RMD year, the traditional balance falls by ~ (balance / divisor) more than spending
    // alone would take, the taxable balance grows from the reinvested excess, and taxLiability > the
    // equivalent no-RMD year. Compute the expected first-RMD-year figures explicitly.
}
```
> Flesh out against `DeterministicProjectionEngineWithdrawalTest`'s scenario helpers; assert the first RMD year's traditional drop = spend-draw + `traditional/distributionPeriod(age)` and that year's tax reflects the RMD ordinary income.

- [ ] **Step 2: Run to verify it fails.** `mvn -q -pl wealthview-projection test -Dtest=DeterministicProjectionEngineWithdrawalTest` → FAIL.

- [ ] **Step 3: Implement**

(a) Add `rmdAmount` to `RetirementWithdrawalContext` (trailing `BigDecimal rmdAmount`) and forward it in `process`:
```java
var withdrawalResult = pool.executeWithdrawals(
        portfolioNeed, year, effectiveOtherIncome, conversionAmount, rwCtx.rmdAmount(), age);
```
(b) Add a trailing `BigDecimal rmdAmount` to `PoolStrategy.executeRothConversion` (interface + both impls). In `MultiPool.executeRothConversion`, subtract `rmdAmount` from the target-bracket ceiling when computing convertible headroom (mirror how `WithdrawalOrderStrategy.DynamicSequencingOrder` already subtracts `context.rmdAmount()` from `bracketCeiling`). `SinglePool.executeRothConversion` ignores it (returns ZERO as today).
(c) In `DeterministicProjectionEngine.processYear`: snapshot BEFORE `applyGrowth`, then compute the RMD after growth, and thread it:
```java
BigDecimal priorYearEndTraditional = pool.getTraditional();   // BEFORE pool.applyGrowth()
// ... existing pool.applyGrowth() ...
BigDecimal rmdAmount = BigDecimal.ZERO;
if (retired && age >= RmdCalculator.rmdStartAge(ctx.birthYear())) {
    double divisor = RmdCalculator.distributionPeriod(age);
    if (divisor > 0) {
        rmdAmount = priorYearEndTraditional.divide(BigDecimal.valueOf(divisor), 4, RoundingMode.HALF_UP);
    }
}
```
Pass `rmdAmount` into `processIncomeAndConversions(...)` (thread it to `executeRothConversion`) and into the `RetirementWithdrawalContext` constructor.

- [ ] **Step 4: Run + regenerate goldens**

Run the withdrawal test (PASS), then the deterministic golden + characterization tests:
`mvn -q -pl wealthview-projection test -Dtest=DeterministicProjectionEngineWithdrawalTest,DeterministicProjectionEngineCharacterizationTest,ProjectionGoldenFileTest`
Fixtures whose retiree reaches RMD age with a traditional balance will shift. For EACH changed value, confirm the direction (RMD year: extra traditional out, taxable up, more tax) and update it; fixtures without an RMD-age traditional retiree must be unchanged (if one changes, investigate). Document rationale in the commit body.

- [ ] **Step 5: Gate + commit**

Run: `mvn -q -pl wealthview-projection -am verify -DskipITs -B` (fully green incl. regenerated goldens).
```bash
git add backend/wealthview-projection backend/wealthview-projection/src/test/resources/golden
git commit -m "feat(projection): enforce RMDs in the deterministic engine (physical model)

processYear computes the RMD from the pre-growth traditional balance for ages
>= the SECURE 2.0 start age and threads it into the Roth conversion (bracket
headroom) and the withdrawal (physical enforcement). Regenerated the golden/
characterization fixtures whose retiree reaches RMD age; each changed value
verified for direction (more tax, traditional drains faster, taxable grows)."
```

---

## Task 3: Enforce the RMD in the Monte Carlo trial loop (+ MC characterization regen)

**Files:**
- Modify: `TrialSimulator.java` (`SimulationConfig` + `simulateTrial`), `OptimizationContextBuilder.java`
- Test: `TrialSimulatorReturnTest.java` (add an RMD case); regenerate MC characterization if it moves.

**Interfaces:**
- Consumes: `RmdCalculator`; `birthYear` (available in `OptimizationContextBuilder` via `input.birthYear()`).
- Produces: `SimulationConfig` gains a trailing `int rmdStartAge` (a large sentinel like `Integer.MAX_VALUE` disables RMDs). In `simulateTrial`, each year when `age ≥ config.rmdStartAge()` and `pools[1] > 0`: `rmd = pools1PreGrowth / RmdCalculator.distributionPeriod(age)`; after the spend split, `extra = max(0, rmd − drawn.traditional())` capped at remaining `pools[1]`; `pools[1] -= extra`; `taxExtra = extra × marginalRateByYear[y]` (0 if no marginal rates); `pools[0] += extra − taxExtra`. The real `rmd` is passed into `applyTrialConversion`'s bracket hook (currently `0`).

- [ ] **Step 1: Write the failing test**
```java
// TrialSimulatorReturnTest — RMD forces a traditional draw in the MC loop.
@Test
void simulateTrial_rmdAgeReached_forcesTraditionalDistributionToTaxable() {
    // 1 year, no growth, no spending: taxable=0, traditional=100000, roth=0, marginalRateByYear={0.20},
    // rmdStartAge=75, retirementAge=75 (so age year0 = 75). divisor(75)=24.6 → rmd ≈ 4065.04.
    // extra = 4065.04 (nothing drawn for spending); taxExtra = 4065.04*0.20 = 813.01;
    // pools: traditional 100000-4065.04=95934.96; taxable += 4065.04-813.01 = 3252.03.
    // finalBalance = 95934.96 + 3252.03 = 99186.99  (the 813.01 tax left the portfolio).
    // Build SimulationConfig with rmdStartAge=75, marginalRateByYear={0.20}; assert finalBalance ≈ 99186.99.
}
```
> Compute the exact expectation from `distributionPeriod(75)=24.6`. Build the config with the new trailing `rmdStartAge` arg.

- [ ] **Step 2: Run to verify it fails.** `mvn -q -pl wealthview-projection test -Dtest=TrialSimulatorReturnTest` → FAIL (config arity / no forcing).

- [ ] **Step 3: Implement**
- Add `int rmdStartAge` as the trailing `SimulationConfig` component; update all construction sites (`SustainabilitySearch`, `GuardrailResponseBuilder`, tests) to pass it — for the search/report, derive it in `OptimizationContextBuilder` from `input.birthYear()` (`RmdCalculator.rmdStartAge(input.birthYear())`) and carry it on the context so both paths use the same value; tests pass `Integer.MAX_VALUE` unless exercising RMDs.
- In `simulateTrial`, snapshot `double pools1PreGrowth = pools[1];` before the growth multiply, then after the spend `splitWithdrawal`/`applyTrialWithdrawals`:
```java
if (age >= config.rmdStartAge() && pools1PreGrowth > 0) {
    double divisor = RmdCalculator.distributionPeriod(age);
    if (divisor > 0) {
        double rmd = pools1PreGrowth / divisor;
        double extra = Math.max(0, rmd - drawn.traditional());
        extra = Math.min(extra, pools[1]);
        if (extra > 0) {
            pools[1] -= extra;
            double taxExtra = hasPools ? extra * config.marginalRateByYear()[y] : 0.0;
            pools[0] += extra - taxExtra;
        }
    }
}
```
- Feed the real `rmd` into `applyTrialConversion`'s bracket-headroom argument (replace the `0` it currently receives) — compute `rmd` before `applyTrialConversion` (which runs before the spend withdrawal) using `pools1PreGrowth`.
> Ordering note: compute `rmd` once per year from `pools1PreGrowth`; use it both for the pre-withdrawal conversion bracket hook AND the post-withdrawal forcing. `drawn.traditional()` is the spend draw from the split.

- [ ] **Step 4: Run + regen.** `mvn -q -pl wealthview-projection test -Dtest=TrialSimulatorReturnTest,MonteCarloSpendingOptimizerCharacterizationTest`. The characterization fixture only moves if its retiree reaches RMD age with a traditional balance — regenerate deliberately (direction: more tax, traditional drains) or confirm unchanged. Document.

- [ ] **Step 5: Gate + commit**

Run: `mvn -q -pl wealthview-projection -am verify -DskipITs -B`.
```bash
git add backend/wealthview-projection
git commit -m "feat(projection): enforce RMDs in the Monte Carlo trial loop (physical model)

simulateTrial forces the RMD excess out of the traditional pool for ages >=
the SECURE 2.0 start age, taxes it at the year's marginal rate, and reinvests
the after-tax remainder to taxable; the real RMD feeds the conversion bracket
hook. SimulationConfig carries rmdStartAge (derived from birthYear)."
```

---

## Task 4: Full verify (units + gates + Testcontainers ITs)

**Files:** whole backend `mvn verify`.

- [ ] **Step 1: Chunked full verify**

Run: `cd /home/jakefear/source/wealthview/backend && mvn -f pom.xml clean install -DskipITs -B` (units + all 5 gates + install)
Then: `cd /home/jakefear/source/wealthview/backend && mvn -f pom.xml verify -pl wealthview-app -B` (app Testcontainers ITs)
Expected: BUILD SUCCESS both. If an app IT asserts a projection/guardrail response whose numbers move because its scenario reaches RMD age, update the expected values (direction-verified); coverage floors met.

- [ ] **Step 2: Commit** (only if Step 1 required test updates)
```bash
git add backend
git commit -m "test: update ITs for RMD enforcement in the projection"
```

---

## Self-review notes (author)

- **Spec coverage:** physical model (force/tax/reinvest) → Tasks 1–3; pre-growth prior-year-end basis → Tasks 2 & 3; start age gate → Tasks 2 & 3; ordering (RMD before conversion, consumes headroom) → Task 2 (deterministic `executeRothConversion` rmd param) + Task 3 (MC `applyTrialConversion` hook); both engines → Tasks 2 (deterministic) & 3 (MC); reuse `RmdCalculator` → all; golden regen + full verify → Tasks 2, 3, 4.
- **Deviation from spec noted:** the spec assumed `executeRothConversion` already had an `rmdAmount` hook; it does not, so Task 2 adds the param (a signature change across both `PoolStrategy` impls).
- **Type consistency:** `getTraditional()`, `RetirementWithdrawalContext.rmdAmount()`, `executeRothConversion(..., rmdAmount)`, `SimulationConfig` trailing `int rmdStartAge`, `RmdCalculator.distributionPeriod/rmdStartAge` — used consistently across tasks.
- **Verify-before-coding seams:** `MultiPoolDeepTest`/`DeterministicProjectionEngineWithdrawalTest` scenario builders; every `new SimulationConfig(...)` and `new RetirementWithdrawalContext(...)` construction site (arity updates); the exact conversion-headroom subtraction in `MultiPool.executeRothConversion`.
```
