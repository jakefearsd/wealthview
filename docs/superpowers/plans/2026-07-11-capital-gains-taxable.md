# Capital Gains on Taxable Accounts — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Track per-lot FIFO cost basis on the taxable pool and tax realized long-term capital gains + qualified dividends (0/15/20 stacked on ordinary income + 3.8% NIIT + an annual dividend drag), in both engines.

**Architecture:** A `TaxableLots` FIFO lot structure replaces the scalar taxable balance in each engine. A new `ltcg_brackets` table + `CapitalGainsTaxCalculator` computes LTCG tax stacked on ordinary income (constant-real brackets, deflated NIIT thresholds). The taxable return splits into `(r − yield)` appreciation + a `yield` dividend taxed annually; withdrawals realize FIFO gains. The MC uses a precomputed per-year LTCG rate for the hot loop.

**Tech Stack:** Java 25, Spring Boot 4.1, Maven multi-module, PostgreSQL 16 (Testcontainers ITs), JUnit 5 + Mockito + AssertJ.

## Global Constraints

- Java 25 idioms: records/sealed types, no wildcard imports. `BigDecimal` money in the deterministic path (`SCALE = 4`, `HALF_UP`); `double` in the MC hot loop.
- Constructor injection only. Return `Optional`/empty, never null, from finders.
- Tests: AssertJ only. Test names `methodUnderTest_stateOrInput_expectedResult`. Testcontainers PostgreSQL 16 (never H2), `@DataJpaTest`+`@Testcontainers`+`@AutoConfigureTestDatabase(replace=NONE)`, extend `AbstractIntegrationTest`.
- TDD: failing test first. One logical change per commit; conventional-commit with body for feat/fix/refactor/db.
- Per-task gate build (catches PMD/CPD/SpotBugs/Checkstyle/JaCoCo): `mvn -pl <module> -am verify -DskipITs -B` FOREGROUND, Bash `timeout` 600000. Never background. Coverage floors: core/projection ≥90% line; never lower a branch floor.
- **LTCG rules (verbatim):** long-term only. LTCG income (realized FIFO gains + qualified dividends) is taxed **stacked on ordinary income** at 0/15/20 (`ltcg_brackets`, held **constant real** at base-year values). NIIT = `0.038 × min(netInvestmentIncome, MAGI − threshold)`, thresholds $200k single / $250k MFJ, **deflated** by `(1+inflation)^yearsFromBase`. Dividend split: appreciation `(r − yield)`, dividend `value × yield` taxed at LTCG + reinvested (basis = dividend), total return preserved. FIFO realized gain per consumed lot = `soldValue × (value − basis)/value`.
- PG conventions: `uuid` PK `gen_random_uuid()`, `timestamptz` audit cols, snake_case, explicit constraint names, `numeric` for money/rates. Flyway `V071` (V070 is head). Commit on `main`; do not push.
- Golden/characterization values move for ANY scenario with a taxable balance (taxable is no longer tax-free). Regenerate deliberately, per-value direction-verified (more tax, lower after-tax balances).

## Context from current code

- `tax_brackets` (`V013`) + `TaxBracketEntity` + `TaxBracketRepository` (`findByTaxYearAndFilingStatusOrderByBracketFloorAsc`) — MIRROR for `ltcg_brackets`. `FederalTaxCalculator` iterates them; `CombinedTaxResult(federalTax, stateTax, totalTax, salt, itemized, useItemized)` with `.add()`. `SocialSecurityTaxCalculator` already deflates statutory thresholds by `1/(1+inflation)^yearsFromBase` — MIRROR for NIIT.
- `MarginalRateCalculator.compute(@Nullable FederalTaxCalculator taxCalculator, double[] taxableIncomeByYear, int retirementYear, int years, FilingStatus) → double[]` — MIRROR for a per-year LTCG rate.
- `PoolStrategy.MultiPool`: `private BigDecimal taxable` (field), `taxableContrib` (final), `applyContributions` (`taxable = taxable.add(taxableContrib)`), `applyGrowth` (`taxableGrowth = taxable.multiply(taxableReturn); taxable = taxable.add(taxableGrowth)`), `depositToTaxable(amount)` (`taxable = taxable.add(amount)`), `executeWithdrawals` (draws `fromTaxable`, currently taxes only the traditional portion). `SinglePool` is all-taxable but does NO tax tracking (leave it scalar — capital gains applies to `MultiPool` only, consistent with the audit's existing simplification; a pure-taxable single-pool retiree is out of scope for this pass, note it).
- `TrialSimulator`: `pools[0]` (double) is taxable; grows `pools[0] *= (1+taxableReturn)`; `applyTrialWithdrawals` draws taxable; RMD/surplus deposit to `pools[0]`.
- `ScenarioParams` record (core dto) — add `dividendYield`. `ProjectionInputBuilder.toAccountInput` builds account inputs from `ProjectionAccountEntity` + live holdings.

## File structure

**Create:** `TaxableLots.java` (projection — FIFO lot structure, one for each money type or a shared double impl — see Task 1), `V071__create_ltcg_brackets.sql` + `R__seed_ltcg_brackets.sql` + `LtcgBracketEntity.java` + `LtcgBracketRepository.java` (persistence), `CapitalGainsTaxCalculator.java` (core/projection/tax), `LtcgRateCalculator.java` (projection — MC per-year LTCG rate precompute).
**Modify:** `ScenarioParams` + parser (dividendYield), `ProjectionAccountEntity` + request DTO (cost_basis) + `ProjectionInputBuilder` (initial basis), `PoolStrategy.MultiPool` (taxable→lots, dividend, realized-gain tax), `TrialSimulator` (pools[0]→lots, dividend, LTCG rate), `OptimizationContextBuilder` (LTCG rate precompute + dividend yield), tax test fixtures + goldens.

---

## Task 1: `TaxableLots` FIFO lot structure

**Files:**
- Create: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TaxableLots.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/TaxableLotsTest.java`

**Interfaces:**
- Produces: a `double`-based FIFO lot structure (the MC uses it directly; the deterministic engine wraps its BigDecimal values through `doubleValue()` at the pool boundary OR a parallel BigDecimal method set — Task 5 decides; keep the core math here in `double` and expose both a mutating `double` API and BigDecimal-friendly helpers). Methods: `void addLot(double amount)`; `void grow(double appreciationRate)`; `double totalValue()`; `double totalBasis()`; `double sellFifo(double amount)` returning the realized gain and mutating the lots (removes `amount` of value, oldest-first, proportional basis); `void consolidateIfNeeded(int cap)` (merge oldest lots into one when count > cap). Backed by an `ArrayDeque<double[]>` of `[basis, value]` or two parallel arrays.

- [ ] **Step 1: Write the failing test**
```java
package com.wealthview.projection;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TaxableLotsTest {

    @Test
    void sellFifo_partialOldestLot_realizesProportionalGain() {
        var lots = new TaxableLots();
        lots.addLot(100);            // lot A: basis 100, value 100
        lots.grow(1.0);              // A now value 200 (gain 100); basis 100
        lots.addLot(50);             // lot B: basis 50, value 50 (no gain)
        // total value 250. Sell 100 -> all from oldest lot A (value 200).
        // gain = 100 * (200-100)/200 = 50.
        double gain = lots.sellFifo(100);
        assertThat(gain).isEqualTo(50.0, within(1e-9));
        assertThat(lots.totalValue()).isEqualTo(150.0, within(1e-9)); // 100 left in A + 50 in B
        assertThat(lots.totalBasis()).isEqualTo(100.0, within(1e-9)); // A basis 50 remaining + B basis 50
    }

    @Test
    void sellFifo_spanningLots_drawsOldestFirst() {
        var lots = new TaxableLots();
        lots.addLot(100); lots.grow(1.0);   // A: basis 100, value 200 (gain 100)
        lots.addLot(100);                    // B: basis 100, value 100 (no gain)
        // sell 300 (all): gain = 100 (from A) + 0 (from B) = 100
        assertThat(lots.sellFifo(300)).isEqualTo(100.0, within(1e-9));
        assertThat(lots.totalValue()).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void grow_appreciatesValueNotBasis() {
        var lots = new TaxableLots();
        lots.addLot(1000);
        lots.grow(0.10);
        assertThat(lots.totalValue()).isEqualTo(1100.0, within(1e-9));
        assertThat(lots.totalBasis()).isEqualTo(1000.0, within(1e-9));
    }

    @Test
    void consolidateIfNeeded_overCap_mergesOldestPreservingTotals() {
        var lots = new TaxableLots();
        for (int i = 0; i < 10; i++) { lots.addLot(100); lots.grow(0.0); }
        double vBefore = lots.totalValue(), bBefore = lots.totalBasis();
        lots.consolidateIfNeeded(3);
        assertThat(lots.totalValue()).isEqualTo(vBefore, within(1e-6));
        assertThat(lots.totalBasis()).isEqualTo(bBefore, within(1e-6));
    }
}
```

- [ ] **Step 2: Run to verify it fails.** `cd /home/jakefear/source/wealthview/backend && mvn -q -pl wealthview-projection test -Dtest=TaxableLotsTest` → FAIL.

- [ ] **Step 3: Implement**
```java
package com.wealthview.projection;

import java.util.ArrayDeque;
import java.util.Deque;

/** FIFO cost-basis lots for a taxable pool. Each lot is [basis, value]; oldest first. */
final class TaxableLots {

    private final Deque<double[]> lots = new ArrayDeque<>();

    void addLot(double amount) {
        if (amount > 0) {
            lots.addLast(new double[]{amount, amount});
        }
    }

    void grow(double appreciationRate) {
        double factor = 1 + appreciationRate;
        for (double[] lot : lots) {
            lot[1] *= factor;
        }
    }

    double totalValue() {
        double v = 0;
        for (double[] lot : lots) {
            v += lot[1];
        }
        return v;
    }

    double totalBasis() {
        double b = 0;
        for (double[] lot : lots) {
            b += lot[0];
        }
        return b;
    }

    /** Sells {@code amount} of value oldest-first; returns the realized long-term gain. */
    double sellFifo(double amount) {
        double remaining = Math.min(amount, totalValue());
        double gain = 0;
        while (remaining > 1e-12 && !lots.isEmpty()) {
            double[] lot = lots.peekFirst();
            double basis = lot[0];
            double value = lot[1];
            if (value <= remaining + 1e-12) {
                gain += value - basis;   // whole lot sold
                remaining -= value;
                lots.removeFirst();
            } else {
                double sold = remaining;
                double soldBasis = basis * (sold / value);
                gain += sold - soldBasis;
                lot[0] = basis - soldBasis;
                lot[1] = value - sold;
                remaining = 0;
            }
        }
        return gain;
    }

    void consolidateIfNeeded(int cap) {
        if (lots.size() <= cap) {
            return;
        }
        int toMerge = lots.size() - cap + 1;
        double basis = 0;
        double value = 0;
        for (int i = 0; i < toMerge; i++) {
            double[] lot = lots.removeFirst();
            basis += lot[0];
            value += lot[1];
        }
        lots.addFirst(new double[]{basis, value});
    }
}
```

- [ ] **Step 4: Run to verify it passes.** Same command → PASS.
- [ ] **Step 5: Gate + commit**
```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/TaxableLots.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/TaxableLotsTest.java
git commit -m "feat(projection): add TaxableLots FIFO cost-basis structure"
```
(Run `mvn -q -pl wealthview-projection -am verify -DskipITs -B` first.)

---

## Task 2: `ltcg_brackets` table + entity + repository + seed

**Files:**
- Create: `V071__create_ltcg_brackets.sql`, `R__seed_ltcg_brackets.sql`, `LtcgBracketEntity.java`, `LtcgBracketRepository.java` (persistence)
- Test: `LtcgBracketRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `LtcgBracketRepository extends JpaRepository<LtcgBracketEntity, UUID>` with `List<LtcgBracketEntity> findByTaxYearAndFilingStatusOrderByBracketFloorAsc(int taxYear, String filingStatus)` (mirror `TaxBracketRepository`). Entity: `getRate()/getBracketFloor()/getBracketCeiling()` (ceiling nullable = top). Seed = 2025 0%/15%/20% total-taxable-income thresholds for `single` + `married_filing_jointly`.

- [ ] **Step 1: Migration** (mirror `V013__create_tax_brackets_table.sql`)
```sql
-- V071: long-term capital gains brackets (0/15/20), stacked on ordinary income.
CREATE TABLE IF NOT EXISTS ltcg_brackets (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tax_year        integer NOT NULL,
    filing_status   text NOT NULL,
    rate            numeric(6,4) NOT NULL,
    bracket_floor   numeric(19,4) NOT NULL,
    bracket_ceiling numeric(19,4),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ltcg_brackets_year_status_floor UNIQUE (tax_year, filing_status, bracket_floor)
);
```

- [ ] **Step 2: Seed** (`R__seed_ltcg_brackets.sql`, 2025 thresholds — the LTCG bracket is on TOTAL taxable income):
```sql
TRUNCATE TABLE ltcg_brackets;
INSERT INTO ltcg_brackets (tax_year, filing_status, rate, bracket_floor, bracket_ceiling) VALUES
  (2025, 'single', 0.0000, 0, 48350),
  (2025, 'single', 0.1500, 48350, 533400),
  (2025, 'single', 0.2000, 533400, NULL),
  (2025, 'married_filing_jointly', 0.0000, 0, 96700),
  (2025, 'married_filing_jointly', 0.1500, 96700, 600050),
  (2025, 'married_filing_jointly', 0.2000, 600050, NULL);
```

- [ ] **Step 3: Entity + repository** — mirror `TaxBracketEntity`/`TaxBracketRepository` field-for-field (uuid id, int taxYear, String filingStatus, BigDecimal rate/bracketFloor/bracketCeiling; extends the same audit base).

- [ ] **Step 4: Failing IT** (mirror an existing repo IT; assert `findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025,"single")` returns the 3 rows floor-ascending with rates 0/0.15/0.20). Run `mvn -q -pl wealthview-persistence verify -B` → PASS.
- [ ] **Step 5: Commit** `db(persistence): add ltcg_brackets table + seed (0/15/20 LTCG brackets)`.

---

## Task 3: `CapitalGainsTaxCalculator` (0/15/20 stacking + NIIT, real-terms)

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/CapitalGainsTaxCalculator.java`
- Test: `CapitalGainsTaxCalculatorTest.java`

**Interfaces:**
- Consumes: `LtcgBracketRepository`, `FilingStatus`.
- Produces: `@Component CapitalGainsTaxCalculator` (constructor-injected repo, cached like `FederalTaxCalculator`). `BigDecimal computeLtcgTax(BigDecimal ordinaryTaxableIncome, BigDecimal ltcgIncome, int taxYear, FilingStatus status, int yearsFromBase, BigDecimal inflationRate, BigDecimal magi)`. LTCG stacks on ordinary income (0% up to `ceiling − ordinary`, etc., using base-year constant-real brackets with a latest-year fallback). Adds NIIT `0.038 × min(ltcgIncome, magi − deflatedThreshold).max(0)`, threshold 200000 single / 250000 MFJ deflated by `1/(1+inflation)^yearsFromBase`.

- [ ] **Step 1: Write the failing test** (mock the repo to return the 2025 brackets)
```java
// low-income retiree pays 0% on gains that fit under the 0% ceiling
@Test
void computeLtcgTax_lowOrdinaryIncome_gainFitsInZeroBracket_taxIsZero() {
    // ordinary 20000, ltcg 20000, single 2025: 0% ceiling 48350; 20000+20000=40000 < 48350 -> 0 tax, no NIIT.
    assertThat(calc.computeLtcgTax(bd("20000"), bd("20000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"), bd("40000")))
            .isEqualByComparingTo("0");
}

@Test
void computeLtcgTax_gainStraddlesZeroAnd15Ceiling_taxesOnlyThePortionAbove() {
    // ordinary 40000, ltcg 20000: 8350 fits under 48350 at 0%, 11650 at 15% = 1747.50
    assertThat(calc.computeLtcgTax(bd("40000"), bd("20000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"), bd("60000")))
            .isEqualByComparingTo("1747.5000");
}

@Test
void computeLtcgTax_niitThresholdDeflatesOverHorizon_taxesMoreLater() {
    // high MAGI so NIIT applies; y20 deflated threshold is lower -> more NIIT than y0.
    var y0 = calc.computeLtcgTax(bd("250000"), bd("50000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"), bd("300000"));
    var y20 = calc.computeLtcgTax(bd("250000"), bd("50000"), 2025, FilingStatus.SINGLE, 20, bd("0.025"), bd("300000"));
    assertThat(y20).isGreaterThan(y0);
}
```

- [ ] **Step 2: Run → FAIL.** `mvn -q -pl wealthview-core test -Dtest=CapitalGainsTaxCalculatorTest`.
- [ ] **Step 3: Implement** — load LTCG brackets (cached, latest-year fallback like `FederalTaxCalculator.loadBracketsWithFallback`); walk them stacking the LTCG amount above `ordinaryTaxableIncome` (for each bracket, `taxable-in-bracket = overlap of [max(floor, ordinary+taxedSoFar), ceiling] with the remaining gain`, times rate); add NIIT `0.038 × max(0, min(ltcgIncome, magi − threshold/(1+inflation)^yearsFromBase))`. Threshold constants 200000/250000. All `BigDecimal`, SCALE=4 HALF_UP.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Gate + commit** `feat(core): add CapitalGainsTaxCalculator (0/15/20 stacking + deflated NIIT)`.

---

## Task 4: Inputs — dividend yield + cost basis + initial basis wiring

**Files:**
- Modify: `ScenarioParams.java` + `ScenarioParamsParser`; `ProjectionAccountEntity.java` (+ request DTO) for `cost_basis`; `ProjectionInputBuilder.java` (initial basis); `ProjectionAccountInput`/subtypes (carry `costBasis`); migration `V072__add_cost_basis_to_projection_accounts.sql`.
- Test: parser test (dividendYield default), input-builder test (linked basis from holdings; hypothetical basis default = value).

**Interfaces:**
- Produces: `ScenarioParams.dividendYield()` (BigDecimal, default `0.018` when absent). `ProjectionAccountInput.costBasis()` (`BigDecimal` — linked: `Σ HoldingEntity.costBasis`; hypothetical: `cost_basis` field or default = `initialBalance`). `ProjectionAccountEntity.costBasis` (`numeric(19,4)` nullable jsonb-free column, `V072`).

- [ ] **Step 1–5 (bundled TDD):** add `dividendYield` to `ScenarioParams` + parse with default 0.018; add the `cost_basis` column (`V072`) + entity field + request wiring; add `costBasis()` to `ProjectionAccountInput` + subtypes (default via convenience ctor = `initialBalance` when absent, so existing call sites compile — mirror the Phase-1 `expectedReturnOverride` convenience-constructor approach); in `ProjectionInputBuilder.toAccountInput`, set `costBasis` = `Σ holdings.costBasis` for linked accounts (open `HoldingRepository`/`AccountService` for the holdings accessor) and `entity.getCostBasis() != null ? entity.getCostBasis() : initialBalance` for hypothetical. Tests: parser default; linked basis sums holdings; hypothetical default = value. Gate green each. Commit `feat: dividend-yield param + taxable cost-basis inputs (V072)`.
> Confirm `ProjectionInput` reaches the account entities' holdings; if the basis must come through `AccountService`, add a `computeCostBasis(account, tenantId)` mirroring `computeBalance`.

---

## Task 5: Deterministic engine — taxable lots + LTCG tax + dividend (golden regen)

**Files:**
- Modify: `PoolStrategy.java` (`MultiPool`: `taxable` → `TaxableLots`; dividend split in `applyGrowth`; realized-gain LTCG tax in `executeWithdrawals`; `addLot` in contributions/deposit), `TaxStrategyFactory`/`DeterministicProjectionEngine` (inject `CapitalGainsTaxCalculator` + dividend yield)
- Test: `MultiPoolDeepTest`/`DeterministicProjectionEngineTaxTest` + golden regen.

**Interfaces:**
- Consumes: `TaxableLots` (Task 1), `CapitalGainsTaxCalculator` (Task 3), `costBasis`/`dividendYield` (Task 4).
- Produces: `MultiPool` holds a `TaxableLots`; `getTotal`/taxable value = `lots.totalValue()`. `applyContributions`/`depositToTaxable` → `lots.addLot`. `applyGrowth` grows lots at `(taxableReturn − dividendYield)` and adds a dividend lot `value × dividendYield`, accumulating the year's `qualifiedDividendIncome`. `executeWithdrawals` draws taxable via `lots.sellFifo(fromTaxable)` → realized gain; the LTCG tax = `CapitalGainsTaxCalculator.computeLtcgTax(ordinaryIncome, realizedGain + qualifiedDividendIncome, year, filing, yearsFromBase, inflation, magi)` is added to the withdrawal tax and deducted via the cascade. Initial lots seeded from `costBasis`/value at construction.

- [ ] **Step 1: Write failing tests** — (a) a taxable-heavy retiree with an embedded gain (basis < value) now pays LTCG tax on a taxable withdrawal (was zero); (b) a low-income retiree pays 0% on the realized gain; (c) the dividend drag reduces the taxable pool each year by `value × yield × ltcgRate` and total return is preserved. Mirror `MultiPoolDeepTest` construction. Compute expected values explicitly.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** the `MultiPool` changes above; seed initial lots from each taxable account's `(costBasis, value)`; thread `dividendYield` + `CapitalGainsTaxCalculator` from `DeterministicProjectionEngine`/`TaxStrategyFactory`. Accumulate `qualifiedDividendIncome` per year (reset each year), fold into the LTCG tax at withdrawal time. `magi` ≈ ordinary income + LTCG income (reuse the existing effective-income plumbing).
- [ ] **Step 4: Run + regenerate goldens** — most deterministic goldens/characterization fixtures with a taxable balance move (LTCG tax + dividend drag). Regenerate deliberately; for each changed value verify direction (more tax, lower after-tax balance) and that basis/gain arithmetic explains it. A fixture whose taxable account has basis = value AND no appreciation before withdrawal realizes no gain — should be near-unchanged (only the dividend drag moves it). Document each.
- [ ] **Step 5: Gate + commit** `feat(projection): taxable cost-basis lots, LTCG tax + dividend drag in the deterministic engine` (regen rationale in body).

---

## Task 6: Monte Carlo — taxable lots + per-year LTCG rate + dividend (MC char regen)

**Files:**
- Create: `LtcgRateCalculator.java` (projection — per-year LTCG marginal rate precompute)
- Modify: `TrialSimulator.java` (`pools[0]` → `TaxableLots`; dividend; realized-gain LTCG tax via the precomputed rate; `consolidateIfNeeded`), `OptimizationContextBuilder.java` (precompute the LTCG rate array + pass dividend yield; seed initial lots from account `costBasis`), `SimulationConfig`/`SearchContext` (carry the LTCG rate array + dividend yield + initial lots)
- Test: `TrialSimulatorReturnTest` + MC characterization regen.

**Interfaces:**
- Consumes: `TaxableLots`, `CapitalGainsTaxCalculator` (for the precompute) / `LtcgRateCalculator`.
- Produces: `LtcgRateCalculator.compute(...)` → `double[]` per-year LTCG rate (0/15/20+NIIT-adjusted from each year's expected ordinary income), mirroring `MarginalRateCalculator`. `TrialSimulator` replaces the `pools[0]` scalar with a `TaxableLots`: grows at `(taxableReturn − yield)` + dividend lot; taxable withdrawals `sellFifo` → realized gain taxed at `ltcgRateByYear[y]`; dividend taxed at `ltcgRateByYear[y]`; `consolidateIfNeeded(200)` per year. Initial lots seeded from the taxable pool's `(basis, value)`.

- [ ] **Step 1–5 (TDD):** Precompute `LtcgRateCalculator` (test: low-income year → 0.0, high → 0.15/0.20). Convert `pools[0]` to `TaxableLots` in `simulateTrial` (this is the big change — every `pools[0] +=`/`*=`/withdrawal site becomes a lot op; the RMD/surplus reinvest → `addLot`; the taxable spend draw → `sellFifo` with the realized gain taxed). Thread the LTCG rate array + dividend yield + initial lots through `SimulationConfig`/`SearchContext`/`OptimizationContextBuilder`. New `TrialSimulatorReturnTest` case: a taxable lot with an embedded gain realizes LTCG tax on withdrawal. Regenerate MC characterization deliberately (direction: more tax, lower balances). Gate green. Commit `feat(projection): taxable cost-basis lots + LTCG tax + dividend drag in the Monte Carlo`.
> This is the largest single task — `pools[0]` as a scalar is threaded through many spots in `simulateTrial`/`applyTrialWithdrawals`. Work carefully; if the lot conversion forces an ambiguous change to the cash-reserve or surplus logic, STOP and report NEEDS_CONTEXT.

---

## Task 7: Full verify (units + gates + Testcontainers ITs)

- [ ] **Step 1: Chunked full verify.** `mvn -f backend/pom.xml clean install -DskipITs -B` then `mvn -f backend/pom.xml verify -pl wealthview-app -B`. Update any app IT whose projection/guardrail numbers move (taxable now taxed) — direction-verified. Coverage floors met.
- [ ] **Step 2: Commit** any IT updates `test: update ITs for taxable capital-gains taxation`.

---

## Self-review notes (author)

- **Spec coverage:** per-lot FIFO → Task 1 (both engines via Tasks 5/6); `ltcg_brackets` + 0/15/20 stacking → Tasks 2–3; NIIT deflation → Task 3; dividend drag → Tasks 5/6 (+ yield input Task 4); initial basis (linked/hypothetical) → Task 4; both-engine integration → Tasks 5/6; MC precomputed LTCG rate → Task 6; large golden regen → Tasks 5/6; full verify → Task 7.
- **Scope note carried from spec:** `SinglePool` (pure-taxable) is left scalar/untaxed this pass (consistent with the audit's existing simplification) — flagged in Task 1 context; a pure-taxable retiree's capital-gains tax is a follow-up.
- **Type consistency:** `TaxableLots.addLot/grow/sellFifo/totalValue/totalBasis/consolidateIfNeeded`; `CapitalGainsTaxCalculator.computeLtcgTax(...)`; `LtcgRateCalculator.compute(...)`; `ScenarioParams.dividendYield()`; `ProjectionAccountInput.costBasis()` — used consistently.
- **Verify-before-coding seams:** `TaxBracketEntity`/`TaxBracketRepository` to mirror; `MarginalRateCalculator` to mirror; the holdings→basis accessor (`HoldingRepository`/`AccountService`); every `pools[0]` site in `TrialSimulator`; the `MultiPool.taxable` sites; `magi` plumbing for NIIT.
```
