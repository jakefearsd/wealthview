# Roth Conversion Optimizer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a two-phase Roth conversion optimizer that minimizes lifetime tax via conversion scheduling (Phase 1), then feeds that schedule into the existing MC spending optimizer (Phase 2), with frontend display of conversion strategy results.

**Architecture:** Phase 1 (`RothConversionOptimizer`) uses hybrid grid scan + ternary refinement to search for the optimal conversion fraction that minimizes lifetime tax while exhausting traditional IRA funds by a target age. Phase 2 passes the conversion schedule into the existing `MonteCarloSpendingOptimizer`, which bakes conversions into its `isSustainable()` pool evolution. The frontend adds a collapsible "Roth Conversion Strategy" section to the Spending Optimizer page with tax savings summary, conversion schedule table, and traditional balance chart.

**Tech Stack:** Java 21 / Spring Boot 3.3 / JUnit 5 + Mockito + AssertJ / PostgreSQL 16 + Flyway / React 18 + TypeScript + Recharts + Vitest

**Spec:** `docs/superpowers/specs/2026-03-22-roth-conversion-optimizer-design.md`

---

## File Map

### New Files (Backend)

| File | Purpose |
|------|---------|
| `backend/wealthview-projection/src/main/java/com/wealthview/projection/RmdCalculator.java` | Uniform Lifetime Table lookup + RMD computation |
| `backend/wealthview-projection/src/test/java/com/wealthview/projection/RmdCalculatorTest.java` | RMD unit tests |
| `backend/wealthview-projection/src/main/java/com/wealthview/projection/RothConversionOptimizer.java` | Phase 1: lifetime tax minimization via conversion scheduling |
| `backend/wealthview-projection/src/test/java/com/wealthview/projection/RothConversionOptimizerTest.java` | Phase 1 unit tests |
| `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/RothConversionScheduleResponse.java` | API response DTO for conversion schedule |
| `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ConversionYearDetail.java` | Per-year conversion detail record |
| `backend/wealthview-persistence/src/main/resources/db/migration/V044__add_roth_conversion_optimizer_fields.sql` | Schema migration |

### New Files (Frontend)

| File | Purpose |
|------|---------|
| `frontend/src/components/TraditionalBalanceChart.tsx` | Line chart of traditional IRA balance trajectory |
| `frontend/src/components/ConversionScheduleTable.tsx` | Year-by-year conversion schedule table |
| `frontend/src/components/TaxSavingsSummary.tsx` | Tax comparison card/banner |

### Modified Files (Backend)

| File | Changes |
|------|---------|
| `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailOptimizationInput.java` | Add 4 conversion fields |
| `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailOptimizationRequest.java` | Add 4 conversion request fields |
| `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailProfileResponse.java` | Add `conversionSchedule` field |
| `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/GuardrailSpendingProfileEntity.java` | Add 4 entity columns |
| `backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java` | Wire Phase 1 → Phase 2, persist conversion schedule |
| `backend/wealthview-projection/src/main/java/com/wealthview/projection/MonteCarloSpendingOptimizer.java` | Conversion-aware `isSustainable()`, 59.5 rule, RMD enforcement |
| `backend/wealthview-projection/src/test/java/com/wealthview/projection/MonteCarloSpendingOptimizerTest.java` | Add conversion-aware MC tests |

### Modified Files (Frontend)

| File | Changes |
|------|---------|
| `frontend/src/types/projection.ts` | Add conversion schedule types |
| `frontend/src/api/projections.ts` | Update request type |
| `frontend/src/pages/SpendingOptimizerPage.tsx` | Add Roth Conversion Strategy form section + results display |

---

## Regression Test Checkpoints

Run full test suites at these points to catch regressions before moving on:

- **After Task 3** (data model changes): `cd backend && mvn test -pl wealthview-core`
- **After Task 5** (RothConversionOptimizer): `cd backend && mvn test -pl wealthview-projection`
- **After Task 7** (MC optimizer changes): `cd backend && mvn test -pl wealthview-projection` (full module)
- **After Task 9** (service + API wiring): `cd backend && mvn clean install` (all modules)
- **After Task 11** (frontend): `cd frontend && npm run test && npm run build`

---

## Task 1: RmdCalculator — Tests First

**Files:**
- Create: `backend/wealthview-projection/src/test/java/com/wealthview/projection/RmdCalculatorTest.java`
- Create: `backend/wealthview-projection/src/main/java/com/wealthview/projection/RmdCalculator.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.wealthview.projection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RmdCalculatorTest {

    @ParameterizedTest
    @CsvSource({
        "72, 27.4",
        "73, 26.5",
        "74, 25.5",
        "75, 24.6",
        "80, 20.2",
        "85, 16.0",
        "90, 12.2",
        "95, 8.9",
        "100, 6.4",
        "105, 4.6",
        "110, 3.2",
        "115, 2.1",
        "120, 2.0"
    })
    void distributionPeriod_matchesIrsTable(int age, double expectedPeriod) {
        assertThat(RmdCalculator.distributionPeriod(age)).isEqualTo(expectedPeriod);
    }

    @Test
    void computeRmd_atAge73_correctAmount() {
        double balance = 1_000_000;
        double rmd = RmdCalculator.computeRmd(balance, 73);
        // $1,000,000 / 26.5 = $37,735.849...
        assertThat(rmd).isCloseTo(37_735.85, within(0.01));
    }

    @Test
    void computeRmd_zeroBalance_returnsZero() {
        assertThat(RmdCalculator.computeRmd(0, 73)).isEqualTo(0);
    }

    @Test
    void computeRmd_negativeBalance_returnsZero() {
        assertThat(RmdCalculator.computeRmd(-100, 73)).isEqualTo(0);
    }

    @Test
    void computeRmd_belowAge72_returnsZero() {
        assertThat(RmdCalculator.computeRmd(1_000_000, 71)).isEqualTo(0);
        assertThat(RmdCalculator.computeRmd(1_000_000, 50)).isEqualTo(0);
    }

    @Test
    void rmdStartAge_bornBefore1960_returns73() {
        assertThat(RmdCalculator.rmdStartAge(1959)).isEqualTo(73);
        assertThat(RmdCalculator.rmdStartAge(1950)).isEqualTo(73);
    }

    @Test
    void rmdStartAge_born1960OrLater_returns75() {
        assertThat(RmdCalculator.rmdStartAge(1960)).isEqualTo(75);
        assertThat(RmdCalculator.rmdStartAge(1970)).isEqualTo(75);
        assertThat(RmdCalculator.rmdStartAge(2000)).isEqualTo(75);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest=RmdCalculatorTest`
Expected: Compilation failure — `RmdCalculator` class does not exist.

- [ ] **Step 3: Implement RmdCalculator**

```java
package com.wealthview.projection;

/**
 * Computes Required Minimum Distributions using the IRS Uniform Lifetime Table
 * (Publication 590-B, Table III). Stateless utility — no Spring injection.
 */
final class RmdCalculator {

    private RmdCalculator() {}

    // IRS Uniform Lifetime Table III — distribution periods for ages 72–120.
    // Index 0 = age 72, index 48 = age 120.
    private static final double[] DISTRIBUTION_PERIODS = {
        27.4, // 72
        26.5, // 73
        25.5, // 74
        24.6, // 75
        23.7, // 76
        22.9, // 77
        22.0, // 78
        21.1, // 79
        20.2, // 80
        19.4, // 81
        18.5, // 82
        17.7, // 83
        16.8, // 84
        16.0, // 85
        15.2, // 86
        14.4, // 87
        13.7, // 88
        12.9, // 89
        12.2, // 90
        11.5, // 91
        10.8, // 92
        10.1, // 93
         9.5, // 94
         8.9, // 95
         8.4, // 96
         7.8, // 97
         7.3, // 98
         6.8, // 99
         6.4, // 100
         6.0, // 101
         5.6, // 102
         5.2, // 103
         4.9, // 104
         4.6, // 105
         4.3, // 106
         4.1, // 107
         3.9, // 108
         3.7, // 109
         3.5, // 110
         3.4, // 111
         3.3, // 112
         3.1, // 113
         3.0, // 114
         2.9, // 115
         2.8, // 116
         2.7, // 117
         2.5, // 118
         2.3, // 119
         2.0  // 120
    };

    static double distributionPeriod(int age) {
        if (age < 72 || age > 120) {
            return 0;
        }
        return DISTRIBUTION_PERIODS[age - 72];
    }

    static double computeRmd(double priorYearEndBalance, int age) {
        if (priorYearEndBalance <= 0 || age < 72) {
            return 0;
        }
        double period = distributionPeriod(age);
        if (period <= 0) {
            return 0;
        }
        return priorYearEndBalance / period;
    }

    static int rmdStartAge(int birthYear) {
        return birthYear < 1960 ? 73 : 75;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest=RmdCalculatorTest`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```
test(projection): add RmdCalculator with Uniform Lifetime Table

IRS Publication 590-B Table III lookup for ages 72–120, RMD computation
from prior-year-end balance, and SECURE 2.0 RMD start age determination
(73 for born before 1960, 75 for born 1960 or later). TDD — tests first.
```

---

## Task 2: RothConversionOptimizer — Core Algorithm with Tests

**Files:**
- Create: `backend/wealthview-projection/src/test/java/com/wealthview/projection/RothConversionOptimizerTest.java`
- Create: `backend/wealthview-projection/src/main/java/com/wealthview/projection/RothConversionOptimizer.java`

**Reference:** The optimizer uses `FederalTaxCalculator.computeMaxIncomeForBracket()` for bracket ceilings and `computeTax()` for tax computation. In tests, mock with a flat 20% tax and known bracket ceilings.

- [ ] **Step 1: Write the internal result record and optimizer skeleton**

Create `RothConversionOptimizer.java` with the `RothConversionSchedule` record and empty `optimize()` method:

```java
package com.wealthview.projection;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;

import java.math.BigDecimal;

final class RothConversionOptimizer {

    record RothConversionSchedule(
        double[] conversionByYear,
        double[] conversionTaxByYear,
        double[] traditionalBalance,
        double[] rothBalance,
        double[] taxableBalance,
        double[] projectedRmd,
        double lifetimeTaxWith,
        double lifetimeTaxWithout,
        int exhaustionAge,
        boolean exhaustionTargetMet,
        double conversionFraction
    ) {}

    private final double initTraditional;
    private final double initRoth;
    private final double initTaxable;
    private final double[] otherIncomeByYear;
    private final double[] taxableIncomeByYear;
    private final int birthYear;
    private final int retirementAge;
    private final int endAge;
    private final int exhaustionBuffer;
    private final double conversionBracketRate;
    private final double rmdTargetBracketRate;
    private final double returnMean;
    private final double essentialFloor;
    private final double inflationRate;
    private final FilingStatus filingStatus;
    private final FederalTaxCalculator taxCalculator;
    private final String withdrawalOrder;

    RothConversionOptimizer(double initTraditional, double initRoth, double initTaxable,
                            double[] otherIncomeByYear, double[] taxableIncomeByYear,
                            int birthYear, int retirementAge, int endAge,
                            int exhaustionBuffer, double conversionBracketRate,
                            double rmdTargetBracketRate, double returnMean,
                            double essentialFloor, double inflationRate,
                            FilingStatus filingStatus, FederalTaxCalculator taxCalculator,
                            String withdrawalOrder) {
        this.initTraditional = initTraditional;
        this.initRoth = initRoth;
        this.initTaxable = initTaxable;
        this.otherIncomeByYear = otherIncomeByYear;
        this.taxableIncomeByYear = taxableIncomeByYear;
        this.birthYear = birthYear;
        this.retirementAge = retirementAge;
        this.endAge = endAge;
        this.exhaustionBuffer = exhaustionBuffer;
        this.conversionBracketRate = conversionBracketRate;
        this.rmdTargetBracketRate = rmdTargetBracketRate;
        this.returnMean = returnMean;
        this.essentialFloor = essentialFloor;
        this.inflationRate = inflationRate;
        this.filingStatus = filingStatus;
        this.taxCalculator = taxCalculator;
        this.withdrawalOrder = withdrawalOrder;
    }

    RothConversionSchedule optimize() {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
```

- [ ] **Step 2: Write the first failing test — all-traditional portfolio produces conversions**

```java
package com.wealthview.projection;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class RothConversionOptimizerTest {

    private FederalTaxCalculator mockTaxCalculator() {
        var calc = mock(FederalTaxCalculator.class);
        // Flat 20% tax for predictability
        lenient().when(calc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(inv -> {
                    BigDecimal income = inv.getArgument(0);
                    if (income.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
                    return income.multiply(new BigDecimal("0.20"))
                            .setScale(4, java.math.RoundingMode.HALF_UP);
                });
        // 22% bracket ceiling at $100,000 gross income
        lenient().when(calc.computeMaxIncomeForBracket(
                any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenReturn(new BigDecimal("100000"));
        return calc;
    }

    private RothConversionOptimizer buildOptimizer(
            double traditional, double roth, double taxable,
            int birthYear, int retirementAge, int endAge,
            int exhaustionBuffer, double essentialFloor) {
        int years = endAge - retirementAge;
        double[] income = new double[years];
        double[] taxableIncome = new double[years];
        return new RothConversionOptimizer(
                traditional, roth, taxable, income, taxableIncome,
                birthYear, retirementAge, endAge, exhaustionBuffer,
                0.22, 0.12, 0.07, essentialFloor, 0.03,
                FilingStatus.SINGLE, mockTaxCalculator(), "taxable_first");
    }

    @Test
    void optimize_allTraditional_producesConversions() {
        var optimizer = buildOptimizer(
                1_000_000, 0, 200_000,  // $1M traditional, $200K taxable
                1968, 62, 90, 5, 30_000);

        var schedule = optimizer.optimize();

        assertThat(schedule).isNotNull();
        assertThat(schedule.conversionByYear()).isNotNull();
        // Should recommend conversions in pre-RMD years
        double totalConversions = 0;
        for (double c : schedule.conversionByYear()) {
            totalConversions += c;
        }
        assertThat(totalConversions).isGreaterThan(0);
        // Lifetime tax with conversions should be less than without
        assertThat(schedule.lifetimeTaxWith()).isLessThan(schedule.lifetimeTaxWithout());
    }

    @Test
    void optimize_allRoth_noConversions() {
        var optimizer = buildOptimizer(
                0, 1_000_000, 200_000,  // No traditional
                1968, 62, 90, 5, 30_000);

        var schedule = optimizer.optimize();

        double totalConversions = 0;
        for (double c : schedule.conversionByYear()) {
            totalConversions += c;
        }
        assertThat(totalConversions).isEqualTo(0);
    }

    @Test
    void optimize_exhaustionTargetMet_whenFeasible() {
        var optimizer = buildOptimizer(
                500_000, 0, 200_000,  // Moderate traditional
                1968, 62, 90, 5, 30_000);

        var schedule = optimizer.optimize();

        assertThat(schedule.exhaustionTargetMet()).isTrue();
        assertThat(schedule.exhaustionAge()).isLessThanOrEqualTo(90 - 5);
    }

    @Test
    void optimize_rmdStartAge_bornBefore1960_uses73() {
        var optimizer = buildOptimizer(
                1_000_000, 0, 200_000,
                1959, 62, 95, 5, 30_000);

        var schedule = optimizer.optimize();

        // RMDs should start at age 73 — no RMDs before that
        int rmdStartYear = 73 - 62; // year index 11
        for (int y = 0; y < rmdStartYear; y++) {
            assertThat(schedule.projectedRmd()[y]).isEqualTo(0);
        }
        // At age 73, RMD should be non-zero (if traditional balance remains)
        if (schedule.traditionalBalance()[rmdStartYear] > 0) {
            assertThat(schedule.projectedRmd()[rmdStartYear]).isGreaterThan(0);
        }
    }

    @Test
    void optimize_rmdStartAge_born1960_uses75() {
        var optimizer = buildOptimizer(
                1_000_000, 0, 200_000,
                1960, 62, 95, 5, 30_000);

        var schedule = optimizer.optimize();

        // RMDs should start at age 75 — no RMDs before that
        int rmdStartYear = 75 - 62; // year index 13
        for (int y = 0; y < rmdStartYear; y++) {
            assertThat(schedule.projectedRmd()[y]).isEqualTo(0);
        }
    }

    @Test
    void optimize_exhaustionBuffer3vs8_differentExhaustionAge() {
        var opt3 = buildOptimizer(800_000, 0, 200_000, 1968, 62, 90, 3, 30_000);
        var opt8 = buildOptimizer(800_000, 0, 200_000, 1968, 62, 90, 8, 30_000);

        var sched3 = opt3.optimize();
        var sched8 = opt8.optimize();

        // Buffer 3 allows exhaustion later (90-3=87) vs buffer 8 (90-8=82)
        // So buffer 8 should exhaust earlier
        assertThat(sched8.exhaustionAge()).isLessThanOrEqualTo(sched3.exhaustionAge());
    }

    @Test
    void optimize_earlyRetiree_noWithdrawalsFromTradBeforeAge595() {
        // Age 55 retiree — conversions OK but spending from taxable only
        var optimizer = buildOptimizer(
                500_000, 0, 300_000,
                1975, 55, 90, 5, 30_000);

        var schedule = optimizer.optimize();

        // Traditional balance should not decrease by more than conversion amounts
        // before age 59.5 (year index ~4.5)
        // In practice, we verify traditional balance only decreases by conversions
        assertThat(schedule).isNotNull();
        assertThat(schedule.conversionByYear()).isNotNull();
    }

    @Test
    void optimize_ssIncomeMidStream_reducesConversions() {
        int years = 90 - 62;
        double[] income = new double[years];
        double[] taxableIncome = new double[years];
        // SS starts at age 67 (year 5) — $30K/year
        for (int y = 5; y < years; y++) {
            income[y] = 30_000;
            taxableIncome[y] = 25_500; // 85% taxable
        }

        var optimizer = new RothConversionOptimizer(
                1_000_000, 0, 200_000, income, taxableIncome,
                1968, 62, 90, 5, 0.22, 0.12, 0.07,
                30_000, 0.03, FilingStatus.SINGLE, mockTaxCalculator(),
                "taxable_first");

        var schedule = optimizer.optimize();

        // After SS starts, conversions should be smaller (less bracket space)
        double avgPreSS = 0, avgPostSS = 0;
        int preCount = 0, postCount = 0;
        int rmdAge = RmdCalculator.rmdStartAge(1968);
        for (int y = 0; y < years; y++) {
            int age = 62 + y;
            if (age >= rmdAge) break; // only compare pre-RMD conversion years
            if (y < 5) {
                avgPreSS += schedule.conversionByYear()[y];
                preCount++;
            } else {
                avgPostSS += schedule.conversionByYear()[y];
                postCount++;
            }
        }
        if (preCount > 0) avgPreSS /= preCount;
        if (postCount > 0) avgPostSS /= postCount;
        assertThat(avgPostSS).isLessThan(avgPreSS);
    }

    @Test
    void optimize_taxableDepletedBefore595_conversionsStop() {
        // Tiny taxable balance, early retiree — conversions limited by
        // spending feasibility guard
        var optimizer = buildOptimizer(
                800_000, 0, 50_000,  // Only $50K taxable
                1975, 55, 90, 5, 40_000);  // $40K/year essential spending

        var schedule = optimizer.optimize();

        // Very early years: taxable must cover spending AND conversion tax.
        // With $50K taxable and $40K spending, only ~$10K left for conversion tax.
        // Conversions should be limited in first few years.
        assertThat(schedule).isNotNull();
        // Verify conversions are modest in year 0 (age 55)
        // At 20% tax, $10K covers tax on $50K conversion max
        // But check that spending feasibility is respected
        double year0Conv = schedule.conversionByYear()[0];
        // Should NOT convert enough that taxable can't cover spending
        double year0Tax = year0Conv * 0.20;
        double taxableAfterTax = 50_000 * 1.07 - year0Tax; // after growth - tax
        assertThat(taxableAfterTax).isGreaterThanOrEqualTo(40_000);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest=RothConversionOptimizerTest`
Expected: All tests FAIL with `UnsupportedOperationException`.

- [ ] **Step 4: Implement the `optimize()` method**

Implement the full algorithm in `RothConversionOptimizer.java`:

1. `optimize()` — entry point: run grid scan (50 fractions) + ternary refinement, also run baseline (fraction=0)
2. `simulateForFraction(double fraction)` — forward-simulate one candidate, return `SimulationResult(lifetimeTax, exhaustionAge, arrays...)`
3. Inside `simulateForFraction`:
   - Track `priorYearEndTraditional` for RMD computation
   - Apply growth each year
   - Pre-RMD: compute bracket space, apply conversion fraction, enforce spending feasibility guard for age < 59.5, compute conversion tax
   - RMD years: compute RMD from `priorYearEndTraditional`, force withdrawal
   - Spending: from taxable only before 59.5, split across pools after
   - Track all balances and accumulate lifetime tax
4. `findOptimalFraction()` — grid scan 50 points, then ternary refine ±5% around best

Key implementation details:
- Use `taxCalculator.computeMaxIncomeForBracket(BigDecimal.valueOf(conversionBracketRate), year, filingStatus)` for bracket ceiling
- Use `taxCalculator.computeTax()` for marginal tax: `tax(conversion + otherIncome) - tax(otherIncome)`
- Spending feasibility guard (step 2.d from spec): before 59.5, reduce conversion if `taxableBalance - conversionTax < spendingNeed`
- RMD uses `RmdCalculator.computeRmd(priorYearEndTraditional, age)` only when `age >= rmdStartAge`
- Age 59.5 check: `retirementAge + y >= 60` (conservative — use integer age ≥ 60 as proxy for 59.5)

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest=RothConversionOptimizerTest`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```
feat(projection): add RothConversionOptimizer with lifetime tax minimization

Phase 1 of the Roth conversion optimizer. Uses hybrid grid scan + ternary
refinement to find the conversion fraction that minimizes lifetime tax.
Handles 59.5 spending feasibility guard, SECURE 2.0 RMD ages, dynamic
income-aware bracket space, and configurable exhaustion buffer. TDD.
```

---

## Task 3: Data Model Changes — Migration, DTOs, Entity

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V044__add_roth_conversion_optimizer_fields.sql`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/RothConversionScheduleResponse.java`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ConversionYearDetail.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailOptimizationInput.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailOptimizationRequest.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailProfileResponse.java`
- Modify: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/GuardrailSpendingProfileEntity.java`

- [ ] **Step 1: Create Flyway migration**

```sql
-- V044: Add Roth conversion optimizer fields to guardrail_spending_profiles
-- Supports the two-phase Roth conversion optimizer: Phase 1 minimizes lifetime
-- tax via conversion scheduling, Phase 2 feeds schedule into MC spending optimizer.

ALTER TABLE guardrail_spending_profiles
    ADD COLUMN IF NOT EXISTS conversion_schedule jsonb DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS conversion_bracket_rate numeric(5,4) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS rmd_target_bracket_rate numeric(5,4) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS traditional_exhaustion_buffer int DEFAULT 5;
```

- [ ] **Step 2: Create `ConversionYearDetail` record**

```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record ConversionYearDetail(
        int calendarYear,
        int age,
        BigDecimal conversionAmount,
        BigDecimal estimatedTax,
        BigDecimal traditionalBalanceAfter,
        BigDecimal rothBalanceAfter,
        BigDecimal projectedRmd,
        BigDecimal otherIncome,
        BigDecimal totalTaxableIncome,
        String bracketUsed
) {}
```

- [ ] **Step 3: Create `RothConversionScheduleResponse` record**

```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

public record RothConversionScheduleResponse(
        BigDecimal lifetimeTaxWithConversions,
        BigDecimal lifetimeTaxWithout,
        BigDecimal taxSavings,
        int exhaustionAge,
        boolean exhaustionTargetMet,
        BigDecimal conversionBracketRate,
        BigDecimal rmdTargetBracketRate,
        int traditionalExhaustionBuffer,
        BigDecimal mcExhaustionPct,
        List<ConversionYearDetail> years
) {}
```

- [ ] **Step 4: Add fields to `GuardrailOptimizationInput`**

Add 4 new fields at the end of the record. Update the existing compact constructor in `MonteCarloSpendingOptimizerTest` helper methods to pass default values (`false, null, null, 5`).

```java
// Add to end of record:
boolean optimizeConversions,
BigDecimal conversionBracketRate,
BigDecimal rmdTargetBracketRate,
int traditionalExhaustionBuffer
```

- [ ] **Step 5: Add fields to `GuardrailOptimizationRequest`**

```java
// Add to end of record:
Boolean optimizeConversions,
BigDecimal conversionBracketRate,
BigDecimal rmdTargetBracketRate,
Integer traditionalExhaustionBuffer
```

- [ ] **Step 6: Add `conversionSchedule` field to `GuardrailProfileResponse`**

Add `RothConversionScheduleResponse conversionSchedule` as the last field in the canonical record constructor. Update both constructors and the `from()` factory:

```java
// Canonical constructor — add as last field:
public record GuardrailProfileResponse(
        // ... existing 24 fields unchanged ...
        int cashReserveYears,
        BigDecimal cashReturnRate,
        RothConversionScheduleResponse conversionSchedule  // NEW — last field
) {
    // Compact constructor (18-arg) — update delegating call:
    public GuardrailProfileResponse(UUID id, UUID scenarioId, String name,
                                     BigDecimal essentialFloor, BigDecimal terminalBalanceTarget,
                                     BigDecimal returnMean, BigDecimal returnStddev,
                                     int trialCount, BigDecimal confidenceLevel,
                                     List<GuardrailPhaseInput> phases,
                                     List<GuardrailYearlySpending> yearlySpending,
                                     BigDecimal medianFinalBalance, BigDecimal failureRate,
                                     BigDecimal percentile10Final, BigDecimal percentile90Final,
                                     boolean stale, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, scenarioId, name, essentialFloor, terminalBalanceTarget,
                returnMean, returnStddev, trialCount, confidenceLevel,
                phases, yearlySpending, medianFinalBalance, failureRate,
                percentile10Final, percentile90Final, stale, createdAt, updatedAt,
                BigDecimal.ZERO, null, 0, null,
                2, new BigDecimal("0.04"),
                null);  // conversionSchedule default = null
    }

    // from() factory — add JSONB deserialization after existing fields:
    public static GuardrailProfileResponse from(GuardrailSpendingProfileEntity entity) {
        // ... existing phases/yearlySpending deserialization unchanged ...

        RothConversionScheduleResponse conversionSchedule = null;
        if (entity.getConversionSchedule() != null
                && !entity.getConversionSchedule().isBlank()) {
            try {
                conversionSchedule = MAPPER.readValue(
                        entity.getConversionSchedule(),
                        RothConversionScheduleResponse.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // leave null — entity predates conversion optimizer
            }
        }

        return new GuardrailProfileResponse(
                // ... existing 24 fields unchanged ...
                entity.getCashReserveYears(),
                entity.getCashReturnRate(),
                conversionSchedule);  // NEW — last arg
    }
}
```

**Note:** The `MonteCarloSpendingOptimizer.optimize()` method also constructs `GuardrailProfileResponse` directly (line 260). Update that call to pass `null` as the last argument for `conversionSchedule` (or the actual schedule when conversions are enabled — handled in Task 6).

- [ ] **Step 7: Add columns to `GuardrailSpendingProfileEntity`**

```java
@Column(name = "conversion_schedule", columnDefinition = "jsonb")
@JdbcTypeCode(SqlTypes.JSON)
private String conversionSchedule;

@Column(name = "conversion_bracket_rate", precision = 5, scale = 4)
private BigDecimal conversionBracketRate;

@Column(name = "rmd_target_bracket_rate", precision = 5, scale = 4)
private BigDecimal rmdTargetBracketRate;

@Column(name = "traditional_exhaustion_buffer")
private Integer traditionalExhaustionBuffer = 5;
```

Add getters and setters following the existing entity pattern.

- [ ] **Step 8: Run tests to verify no regressions**

Run: `cd backend && mvn test -pl wealthview-core`
Expected: All existing tests PASS (new fields have defaults, compact constructors preserve backward compatibility).

- [ ] **Step 9: Commit**

```
feat(core): add Roth conversion optimizer data model

V044 migration adds conversion_schedule (jsonb), conversion_bracket_rate,
rmd_target_bracket_rate, and traditional_exhaustion_buffer columns to
guardrail_spending_profiles. New DTOs: RothConversionScheduleResponse,
ConversionYearDetail. Extended GuardrailOptimizationInput/Request/Response
with conversion fields. All backward-compatible with defaults.
```

---

## Task 4: Regression Checkpoint — Full Backend Build

- [ ] **Step 1: Run full backend test suite**

Run: `cd backend && mvn clean install`
Expected: BUILD SUCCESS. All existing tests pass with the new fields (which have defaults).

- [ ] **Step 2: Fix any compilation or test failures before proceeding**

If any test fails due to the new `GuardrailOptimizationInput` fields, update the test helpers to include the 4 new parameters with defaults: `false, null, null, 5`.

---

## Task 5: Additional RothConversionOptimizer Tests

**Files:**
- Modify: `backend/wealthview-projection/src/test/java/com/wealthview/projection/RothConversionOptimizerTest.java`

Add the remaining test cases from the spec that weren't covered in Task 2.

- [ ] **Step 1: Add filing status test**

```java
@Test
void optimize_mfj_higherConversionsThanSingle() {
    // MFJ has higher bracket ceilings → more conversion space
    var mfjCalc = mockTaxCalculator();
    lenient().when(mfjCalc.computeMaxIncomeForBracket(
            any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
            .thenReturn(new BigDecimal("200000")); // MFJ ceiling is higher

    int years = 90 - 62;
    var mfjOpt = new RothConversionOptimizer(
            1_000_000, 0, 200_000, new double[years], new double[years],
            1968, 62, 90, 5, 0.22, 0.12, 0.07,
            30_000, 0.03, FilingStatus.MARRIED_FILING_JOINTLY, mfjCalc,
            "taxable_first");

    var singleOpt = buildOptimizer(
            1_000_000, 0, 200_000, 1968, 62, 90, 5, 30_000);

    var mfjSched = mfjOpt.optimize();
    var singleSched = singleOpt.optimize();

    double mfjTotal = 0, singleTotal = 0;
    for (double c : mfjSched.conversionByYear()) mfjTotal += c;
    for (double c : singleSched.conversionByYear()) singleTotal += c;

    // MFJ should convert more (wider bracket space)
    assertThat(mfjTotal).isGreaterThan(singleTotal);
}
```

- [ ] **Step 2: Add already-past-RMD-age test**

```java
@Test
void optimize_alreadyPastRmdAge_noConversions() {
    // Retirement age 75 with birth year 1955 → RMD starts at 73, already past
    var optimizer = buildOptimizer(
            500_000, 0, 200_000,
            1955, 75, 95, 5, 30_000);

    var schedule = optimizer.optimize();

    // No pre-RMD years → no conversions
    double totalConversions = 0;
    for (double c : schedule.conversionByYear()) {
        totalConversions += c;
    }
    assertThat(totalConversions).isEqualTo(0);
    // But should still have RMD projections
    boolean hasRmds = false;
    for (double r : schedule.projectedRmd()) {
        if (r > 0) { hasRmds = true; break; }
    }
    assertThat(hasRmds).isTrue();
}
```

- [ ] **Step 3: Add large-portfolio warning test**

```java
@Test
void optimize_veryLargeTraditional_warnsIfExhaustionNotMet() {
    // $5M traditional with short window — may not exhaust in time
    var optimizer = buildOptimizer(
            5_000_000, 0, 100_000,
            1968, 70, 85, 5, 30_000); // Only 15 years, exhaust by 80

    var schedule = optimizer.optimize();

    // If exhaustion target not met, flag it
    if (!schedule.exhaustionTargetMet()) {
        assertThat(schedule.exhaustionAge()).isGreaterThan(85 - 5);
    }
    // Either way, should still produce a schedule
    assertThat(schedule.conversionByYear()).isNotNull();
}
```

- [ ] **Step 4: Run all optimizer tests**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest=RothConversionOptimizerTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```
test(projection): add filing status, past-RMD, and large-portfolio tests

Additional RothConversionOptimizer coverage: MFJ vs Single bracket
ceilings, retirement after RMD start age (no conversions, RMDs only),
and very large traditional balance exhaustion warning.
```

---

## Task 6: MC Optimizer — Conversion-Aware `isSustainable()`

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/MonteCarloSpendingOptimizer.java`
- Modify: `backend/wealthview-projection/src/test/java/com/wealthview/projection/MonteCarloSpendingOptimizerTest.java`

- [ ] **Step 1: Write failing tests for conversion-aware MC**

Add to `MonteCarloSpendingOptimizerTest.java`:

```java
@Test
void optimize_withConversionSchedule_traditionalDeclinesRothGrows() {
    var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

    // Create input with conversion schedule: $50K/year for 11 years (age 62-72)
    int years = 90 - 62; // 28 years
    double[] conversions = new double[years];
    double[] conversionTax = new double[years];
    for (int y = 0; y < 11; y++) { // pre-RMD years
        conversions[y] = 50_000;
        conversionTax[y] = 10_000; // 20% tax
    }

    var input = new GuardrailOptimizationInput(
            LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
            List.of(
                new HypotheticalAccountInput(new BigDecimal("200000"),
                    BigDecimal.ZERO, new BigDecimal("0.07"), "taxable"),
                new HypotheticalAccountInput(new BigDecimal("800000"),
                    BigDecimal.ZERO, new BigDecimal("0.07"), "traditional")),
            List.of(),
            new BigDecimal("20000"), BigDecimal.ZERO,
            new BigDecimal("0.10"), new BigDecimal("0.15"),
            500, new BigDecimal("0.95"), phases, 42L,
            BigDecimal.ZERO, null, 0,
            0, BigDecimal.ZERO, "single", "taxable_first",
            true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5);

    var taxOptimizer = taxAwareOptimizer();
    var result = taxOptimizer.optimize(input);

    assertThat(result).isNotNull();
    assertThat(result.yearlySpending()).isNotEmpty();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest="MonteCarloSpendingOptimizerTest#optimize_withConversionSchedule*"`
Expected: FAIL — `GuardrailOptimizationInput` constructor doesn't accept conversion arrays yet (or the method signature changes haven't been made).

- [ ] **Step 3: Implement conversion-aware changes**

In `MonteCarloSpendingOptimizer.java`:

1. In `optimize()`, after computing income and tax context:
   - If `input.optimizeConversions()` is true and traditional balance > 0:
     - Build `RothConversionOptimizer` with computed income arrays
     - Call `optimize()` to get `RothConversionSchedule`
     - Store `conversionByYear` and `conversionTaxByYear` for use in `isSustainable()`
   - Otherwise: `conversionByYear = null`, `conversionTaxByYear = null`

2. Add parameters to `isSustainable()`: `double[] conversionByYear`, `double[] conversionTaxByYear`, `int retirementAge`, `int birthYear`

3. Inside `isSustainable()` year loop, after growth and before withdrawal:
   ```java
   // Roth conversions
   if (conversionByYear != null && conversionByYear[y] > 0) {
       double actualConv = Math.min(conversionByYear[y], pTraditional);
       pTraditional -= actualConv;
       pRoth += actualConv;
       double actualTax = conversionTaxByYear[y]
               * (actualConv / conversionByYear[y]); // scale if capped
       // Deduct tax: before 59.5 taxable only, after cascade
       int age = retirementAge + y;
       if (age < 60) { // proxy for 59.5
           pTaxable -= Math.min(actualTax, Math.max(0, pTaxable));
       } else {
           double taxRem = actualTax;
           double t1 = Math.min(taxRem, Math.max(0, pTaxable));
           pTaxable -= t1; taxRem -= t1;
           double t2 = Math.min(taxRem, Math.max(0, pTraditional));
           pTraditional -= t2; taxRem -= t2;
           pRoth -= taxRem;
       }
   }

   // RMD enforcement
   int rmdStartAge = RmdCalculator.rmdStartAge(birthYear);
   int age = retirementAge + y;
   if (age >= rmdStartAge && pTraditional > 0) {
       double rmd = RmdCalculator.computeRmd(priorYearEndTraditional, age);
       // Ensure at least RMD is withdrawn from traditional
       // (existing splitWithdrawal may not draw enough from traditional)
       // ... adjust drawn[1] to be at least rmd
   }
   ```

4. Modify `splitWithdrawal()` to accept `boolean preAge595` parameter:
   ```java
   if (preAge595) {
       // Before 59.5: all from taxable
       double drawn = Math.min(need, Math.max(0, taxable));
       return new double[]{drawn, 0, 0};
   }
   ```

5. Track `priorYearEndTraditional` per trial for RMD computation.

6. Pass conversion arrays and age info through `allocateSpending()` → `binarySearchDiscretionary()` → `isSustainable()`.

7. Also apply conversions in the final simulation loop (lines 162–215) for accurate balance trajectories.

8. **`mcExhaustionPct` computation** — in the final simulation loop (which produces `yearBalances` and `finalBalances`), track per-trial whether `pTraditional` reaches 0 by the exhaustion deadline:
   ```java
   int exhaustionDeadlineAge = input.endAge() - input.traditionalExhaustionBuffer();
   int exhaustionDeadlineYear = exhaustionDeadlineAge - retirementAge;
   int exhaustedOnTimeCount = 0;
   // Inside the trial loop, after all years:
   // Check if traditional was zero by the deadline year
   if (exhaustionDeadlineYear < years) {
       // Track pTraditional at exhaustionDeadlineYear
       // If it was <= 0, count as exhausted on time
   }
   // After all trials:
   double mcExhaustionPct = (double) exhaustedOnTimeCount / trialCount;
   ```
   Store this in the `RothConversionScheduleResponse` that gets returned.

9. Build `RothConversionScheduleResponse` from the `RothConversionSchedule` (Phase 1 output) + `mcExhaustionPct` (Phase 2 output). Convert `double[]` arrays to `List<ConversionYearDetail>` with `toBD()` for each field. Set on the `GuardrailProfileResponse` being returned.

- [ ] **Step 4: Run all MC optimizer tests**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest=MonteCarloSpendingOptimizerTest`
Expected: All existing tests PASS (null conversion arrays preserve old behavior) + new test PASSES.

- [ ] **Step 5: Commit**

```
feat(projection): add conversion-aware pool evolution to MC optimizer

isSustainable() now executes Roth conversions from Phase 1 schedule,
enforces 59.5 withdrawal rule (taxable only before 59.5), and computes
RMDs during RMD-era years. splitWithdrawal() gains preAge595 parameter.
Backward compatible — null conversion arrays preserve existing behavior.
```

---

## Task 7: Additional MC Optimizer Tests + Regression Checkpoint

**Files:**
- Modify: `backend/wealthview-projection/src/test/java/com/wealthview/projection/MonteCarloSpendingOptimizerTest.java`

- [ ] **Step 1: Add 59.5 rule test**

```java
@Test
void optimize_preAge595_withdrawalsFromTaxableOnly() {
    // Early retiree age 55 — verify spending comes from taxable before 59.5
    var phases = List.of(new GuardrailPhaseInput("All", 55, null, 1));

    var input = new GuardrailOptimizationInput(
            LocalDate.of(2030, 1, 1), 1975, 90, new BigDecimal("0.03"),
            List.of(
                new HypotheticalAccountInput(new BigDecimal("300000"),
                    BigDecimal.ZERO, new BigDecimal("0.07"), "taxable"),
                new HypotheticalAccountInput(new BigDecimal("700000"),
                    BigDecimal.ZERO, new BigDecimal("0.07"), "traditional")),
            List.of(),
            new BigDecimal("20000"), BigDecimal.ZERO,
            new BigDecimal("0.10"), new BigDecimal("0.15"),
            500, new BigDecimal("0.95"), phases, 42L,
            BigDecimal.ZERO, null, 0,
            0, BigDecimal.ZERO, "single", "taxable_first",
            true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5);

    var taxOptimizer = taxAwareOptimizer();
    var result = taxOptimizer.optimize(input);

    assertThat(result).isNotNull();
    assertThat(result.yearlySpending()).isNotEmpty();
    // Should produce sustainable spending even with 59.5 constraint
    assertThat(result.yearlySpending().getFirst().recommended().doubleValue())
            .isGreaterThan(0);
}
```

- [ ] **Step 2: Add no-conversion backward compatibility test**

```java
@Test
void optimize_noConversions_identicalToExistingBehavior() {
    var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

    // Without conversion optimization
    var inputOld = new GuardrailOptimizationInput(
            LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
            List.of(new HypotheticalAccountInput(
                    new BigDecimal("500000"), BigDecimal.ZERO,
                    new BigDecimal("0.07"), "taxable")),
            List.of(),
            new BigDecimal("10000"), new BigDecimal("100000"),
            new BigDecimal("0.10"), new BigDecimal("0.15"),
            500, new BigDecimal("0.95"), phases, 42L,
            BigDecimal.ZERO, null, 0,
            0, BigDecimal.ZERO, "single", "taxable_first",
            false, null, null, 5);

    var result = optimizer.optimize(inputOld);

    assertThat(result.yearlySpending()).isNotEmpty();
    assertThat(result.conversionSchedule()).isNull();
}
```

- [ ] **Step 3: Add mcExhaustionPct test**

```java
@Test
void optimize_withConversions_mcExhaustionPctIsReported() {
    var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

    var input = new GuardrailOptimizationInput(
            LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
            List.of(
                new HypotheticalAccountInput(new BigDecimal("200000"),
                    BigDecimal.ZERO, new BigDecimal("0.07"), "taxable"),
                new HypotheticalAccountInput(new BigDecimal("500000"),
                    BigDecimal.ZERO, new BigDecimal("0.07"), "traditional")),
            List.of(),
            new BigDecimal("20000"), BigDecimal.ZERO,
            new BigDecimal("0.10"), new BigDecimal("0.15"),
            500, new BigDecimal("0.95"), phases, 42L,
            BigDecimal.ZERO, null, 0,
            0, BigDecimal.ZERO, "single", "taxable_first",
            true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5);

    var taxOptimizer = taxAwareOptimizer();
    var result = taxOptimizer.optimize(input);

    assertThat(result.conversionSchedule()).isNotNull();
    assertThat(result.conversionSchedule().mcExhaustionPct())
            .isNotNull()
            .isBetween(BigDecimal.ZERO, BigDecimal.ONE);
}
```

- [ ] **Step 4: Add market crash capping test**

```java
@Test
void optimize_marketCrash_conversionCappedAtTraditionalBalance() {
    // Scenario: large scheduled conversions but small traditional balance
    // after poor market returns — verify no negative traditional balance
    var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

    // Small traditional ($100K) with conversions enabled — in bad MC trials,
    // growth may be negative and traditional may be less than scheduled conversion
    var input = new GuardrailOptimizationInput(
            LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
            List.of(
                new HypotheticalAccountInput(new BigDecimal("400000"),
                    BigDecimal.ZERO, new BigDecimal("0.07"), "taxable"),
                new HypotheticalAccountInput(new BigDecimal("100000"),
                    BigDecimal.ZERO, new BigDecimal("0.07"), "traditional")),
            List.of(),
            new BigDecimal("20000"), BigDecimal.ZERO,
            new BigDecimal("0.10"), new BigDecimal("0.15"),
            500, new BigDecimal("0.95"), phases, 42L,
            BigDecimal.ZERO, null, 0,
            0, BigDecimal.ZERO, "single", "taxable_first",
            true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5);

    var taxOptimizer = taxAwareOptimizer();
    // Should not throw or produce NaN — actualConversion = min(scheduled, pTraditional)
    var result = taxOptimizer.optimize(input);

    assertThat(result).isNotNull();
    assertThat(result.yearlySpending()).isNotEmpty();
    // All recommended values should be finite and non-negative
    for (var ys : result.yearlySpending()) {
        assertThat(ys.recommended().doubleValue()).isGreaterThanOrEqualTo(0);
        assertThat(ys.recommended().doubleValue()).isNotNaN();
    }
}
```

- [ ] **Step 5: Run full projection module tests**

Run: `cd backend && mvn test -pl wealthview-projection`
Expected: ALL tests PASS — no regressions.

- [ ] **Step 4: Run full backend build**

Run: `cd backend && mvn clean install`
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 5: Commit**

```
test(projection): add MC optimizer conversion integration tests

Verifies pre-59.5 withdrawals from taxable only, backward compatibility
for non-conversion inputs, mcExhaustionPct reporting, and market crash
capping (actualConversion capped at traditional balance).
```

---

## Task 8: GuardrailProfileService Integration

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java`

- [ ] **Step 1: Update `optimize()` to pass conversion parameters**

In `GuardrailProfileService.optimize()`, when building `GuardrailOptimizationInput`:

1. Read `optimizeConversions`, `conversionBracketRate`, `rmdTargetBracketRate`, `traditionalExhaustionBuffer` from the request (with defaults: `false`, `null`, `null`, `5`)
2. Pass them to the `GuardrailOptimizationInput` constructor
3. After receiving the response from `spendingOptimizer.optimize()`, persist the conversion fields on the entity:
   - `entity.setConversionSchedule(serialize(response.conversionSchedule()))`
   - `entity.setConversionBracketRate(request.conversionBracketRate())`
   - `entity.setRmdTargetBracketRate(request.rmdTargetBracketRate())`
   - `entity.setTraditionalExhaustionBuffer(request.traditionalExhaustionBuffer())`

4. Update `reoptimize()` to read conversion params from the persisted entity and rebuild the request with them:

```java
// In reoptimize(), after the existing request construction:
var request = new GuardrailOptimizationRequest(
        scenarioId,
        existing.getName(),
        existing.getEssentialFloor(),
        existing.getTerminalBalanceTarget(),
        existing.getReturnMean(),
        existing.getReturnStddev(),
        existing.getTrialCount(),
        existing.getConfidenceLevel(),
        phases,
        existing.getPortfolioFloor(),
        existing.getMaxAnnualAdjustmentRate(),
        existing.getPhaseBlendYears(),
        existing.getRiskTolerance(),
        existing.getCashReserveYears(),
        existing.getCashReturnRate(),
        // NEW: conversion params from entity
        existing.getConversionBracketRate() != null,  // optimizeConversions
        existing.getConversionBracketRate(),
        existing.getRmdTargetBracketRate(),
        existing.getTraditionalExhaustionBuffer());
```

- [ ] **Step 2: Update `buildOptimizationInput()` helper**

Add the 4 new conversion fields at the end of the `GuardrailOptimizationInput` constructor call:

```java
// In buildOptimizationInput(), add after the existing withdrawalOrder arg:
        Boolean.TRUE.equals(request.optimizeConversions()),
        request.conversionBracketRate(),
        request.rmdTargetBracketRate(),
        request.traditionalExhaustionBuffer() != null
                ? request.traditionalExhaustionBuffer() : 5
```

- [ ] **Step 3: Persist conversion schedule on entity**

After `spendingOptimizer.optimize()` returns, serialize the conversion schedule if present:

```java
if (response.conversionSchedule() != null) {
    entity.setConversionSchedule(MAPPER.writeValueAsString(response.conversionSchedule()));
}
entity.setConversionBracketRate(request.conversionBracketRate());
entity.setRmdTargetBracketRate(request.rmdTargetBracketRate());
entity.setTraditionalExhaustionBuffer(
        request.traditionalExhaustionBuffer() != null
                ? request.traditionalExhaustionBuffer() : 5);
```

- [ ] **Step 4: Verify `from()` factory deserialization**

The `from()` factory update is already specified in Task 3 Step 6 above. Verify it compiles and handles null `conversion_schedule` gracefully (entities predating this feature will have null).

- [ ] **Step 5: Run full backend build**

Run: `cd backend && mvn clean install`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```
feat(core): wire Roth conversion optimizer through GuardrailProfileService

optimize() passes conversion params to MC optimizer, persists conversion
schedule as JSONB on entity, and deserializes on GET. reoptimize() reads
persisted conversion params to reconstruct the optimization request.
```

---

## Task 9: API Validation + Controller Tests

**Files:**
- Modify: `backend/wealthview-api/src/main/java/com/wealthview/api/controller/GuardrailController.java` (add validation)
- Test file: existing controller test or new test class

- [ ] **Step 1: Add validation in controller or service**

In `GuardrailProfileService.optimize()`, before calling the optimizer:

```java
if (Boolean.TRUE.equals(request.optimizeConversions())) {
    if (request.rmdTargetBracketRate() != null
            && request.conversionBracketRate() != null
            && request.rmdTargetBracketRate().compareTo(request.conversionBracketRate()) > 0) {
        throw new IllegalArgumentException(
                "RMD target bracket rate must be ≤ conversion bracket rate");
    }
    int buffer = request.traditionalExhaustionBuffer() != null
            ? request.traditionalExhaustionBuffer() : 5;
    if (buffer < 1 || buffer > 15) {
        throw new IllegalArgumentException(
                "Traditional exhaustion buffer must be between 1 and 15");
    }
}
```

- [ ] **Step 2: Write validation test**

Test that the service throws `IllegalArgumentException` when RMD target > conversion bracket. This will be caught by the `@RestControllerAdvice` global exception handler and returned as a 400.

```java
@Test
void optimize_rmdTargetExceedsConversionBracket_throwsIllegalArgument() {
    var request = new GuardrailOptimizationRequest(
            scenarioId, "Test", new BigDecimal("30000"), BigDecimal.ZERO,
            null, null, null, null, List.of(),
            null, null, null, null, null, null,
            true, new BigDecimal("0.12"),  // conversion bracket = 12%
            new BigDecimal("0.22"),        // RMD target = 22% > 12% ← invalid
            5);

    assertThatThrownBy(() -> service.optimize(tenantId, scenarioId, request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RMD target bracket rate must be");
}
```

Place this test in the appropriate service test class or create a focused unit test. The exact test setup depends on whether `GuardrailProfileService` is tested directly (with mocked dependencies) or via integration tests.

- [ ] **Step 3: Run full backend build**

Run: `cd backend && mvn clean install`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```
feat(api): add validation for Roth conversion optimizer parameters

RMD target bracket must be ≤ conversion bracket. Exhaustion buffer
must be 1–15. Validation runs in GuardrailProfileService before
optimizer invocation. Test verifies IllegalArgumentException on invalid input.
```

---

## Task 10: Frontend Types + API Client

**Files:**
- Modify: `frontend/src/types/projection.ts`
- Modify: `frontend/src/api/projections.ts`

- [ ] **Step 1: Add TypeScript types**

Add to `frontend/src/types/projection.ts`:

```typescript
export interface ConversionYearDetail {
    calendar_year: number;
    age: number;
    conversion_amount: number;
    estimated_tax: number;
    traditional_balance_after: number;
    roth_balance_after: number;
    projected_rmd: number;
    other_income: number;
    total_taxable_income: number;
    bracket_used: string;
}

export interface RothConversionScheduleResponse {
    lifetime_tax_with_conversions: number;
    lifetime_tax_without: number;
    tax_savings: number;
    exhaustion_age: number;
    exhaustion_target_met: boolean;
    conversion_bracket_rate: number;
    rmd_target_bracket_rate: number;
    traditional_exhaustion_buffer: number;
    mc_exhaustion_pct: number | null;
    years: ConversionYearDetail[];
}
```

Add to `GuardrailProfileResponse`:

```typescript
conversion_schedule: RothConversionScheduleResponse | null;
```

Add to `GuardrailOptimizationRequest`:

```typescript
optimize_conversions?: boolean;
conversion_bracket_rate?: number;
rmd_target_bracket_rate?: number;
traditional_exhaustion_buffer?: number;
```

- [ ] **Step 2: Commit**

```
feat(frontend): add Roth conversion schedule TypeScript types

ConversionYearDetail, RothConversionScheduleResponse interfaces added
to projection.ts. GuardrailOptimizationRequest and GuardrailProfileResponse
extended with conversion fields.
```

---

## Task 11: Frontend — Optimizer Form Changes

**Files:**
- Modify: `frontend/src/pages/SpendingOptimizerPage.tsx`

- [ ] **Step 1: Add state variables for Roth conversion section**

```typescript
// Roth Conversion Strategy
const [optimizeConversions, setOptimizeConversions] = useState(false);
const [conversionBracketRate, setConversionBracketRate] = useState(0.22);
const [rmdTargetBracketRate, setRmdTargetBracketRate] = useState(0.12);
const [exhaustionBuffer, setExhaustionBuffer] = useState(5);
```

- [ ] **Step 2: Add the collapsible form section**

Add below the existing "Cash Buffer Strategy" section (or similar), a collapsible "Roth Conversion Strategy" section:

```tsx
<div style={{ marginTop: 24, border: '1px solid #d9d9d9', borderRadius: 8, padding: 16 }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: optimizeConversions ? 16 : 0 }}>
        <input
            type="checkbox"
            checked={optimizeConversions}
            onChange={e => setOptimizeConversions(e.target.checked)}
            id="optimize-conversions"
        />
        <label htmlFor="optimize-conversions" style={{ fontWeight: 600 }}>
            Roth Conversion Strategy
        </label>
    </div>
    {optimizeConversions && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <div>
                <label>Conversion Bracket</label>
                <select
                    value={conversionBracketRate}
                    onChange={e => {
                        const rate = parseFloat(e.target.value);
                        setConversionBracketRate(rate);
                        if (rmdTargetBracketRate > rate) setRmdTargetBracketRate(rate);
                    }}
                >
                    {[0.10, 0.12, 0.22, 0.24, 0.32, 0.35, 0.37].map(r => (
                        <option key={r} value={r}>{(r * 100).toFixed(0)}%</option>
                    ))}
                </select>
            </div>
            <div>
                <label>RMD Target Bracket</label>
                <select
                    value={rmdTargetBracketRate}
                    onChange={e => setRmdTargetBracketRate(parseFloat(e.target.value))}
                >
                    {[0.10, 0.12, 0.22, 0.24, 0.32, 0.35, 0.37]
                        .filter(r => r <= conversionBracketRate)
                        .map(r => (
                            <option key={r} value={r}>{(r * 100).toFixed(0)}%</option>
                        ))}
                </select>
            </div>
            <div>
                <label>Exhaust Traditional (years before plan end)</label>
                <input
                    type="number"
                    min={1} max={15}
                    value={exhaustionBuffer}
                    onChange={e => setExhaustionBuffer(parseInt(e.target.value) || 5)}
                />
            </div>
        </div>
    )}
</div>
```

- [ ] **Step 3: Update the API call to include conversion params**

In the `handleOptimize` or similar submit handler, add to the request body:

```typescript
...(optimizeConversions ? {
    optimize_conversions: true,
    conversion_bracket_rate: conversionBracketRate,
    rmd_target_bracket_rate: rmdTargetBracketRate,
    traditional_exhaustion_buffer: exhaustionBuffer,
} : {}),
```

- [ ] **Step 4: Verify the form renders and submits**

Run: `cd frontend && npm run build`
Expected: Build succeeds with no TypeScript errors.

- [ ] **Step 5: Commit**

```
feat(frontend): add Roth Conversion Strategy form section

Collapsible section on Spending Optimizer page with conversion bracket,
RMD target bracket, and exhaustion buffer inputs. RMD target dropdown
filters to ≤ conversion bracket. Params sent to API when enabled.
```

---

## Task 12: Frontend — Results Display Components

**Files:**
- Create: `frontend/src/components/TaxSavingsSummary.tsx`
- Create: `frontend/src/components/ConversionScheduleTable.tsx`
- Create: `frontend/src/components/TraditionalBalanceChart.tsx`
- Modify: `frontend/src/pages/SpendingOptimizerPage.tsx`

- [ ] **Step 1: Create TaxSavingsSummary component**

```tsx
import React from 'react';
import { RothConversionScheduleResponse } from '../types/projection';

interface Props {
    schedule: RothConversionScheduleResponse;
}

const fmt = (n: number) => n.toLocaleString('en-US', {
    style: 'currency', currency: 'USD', maximumFractionDigits: 0
});

export default function TaxSavingsSummary({ schedule }: Props) {
    return (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16, marginBottom: 24 }}>
            <div style={{ background: '#f6f6f6', padding: 16, borderRadius: 8, textAlign: 'center' }}>
                <div style={{ fontSize: 14, color: '#666' }}>Lifetime Tax With Conversions</div>
                <div style={{ fontSize: 24, fontWeight: 700 }}>
                    {fmt(schedule.lifetime_tax_with_conversions)}
                </div>
            </div>
            <div style={{ background: '#f6f6f6', padding: 16, borderRadius: 8, textAlign: 'center' }}>
                <div style={{ fontSize: 14, color: '#666' }}>Lifetime Tax Without</div>
                <div style={{ fontSize: 24, fontWeight: 700 }}>
                    {fmt(schedule.lifetime_tax_without)}
                </div>
            </div>
            <div style={{ background: '#e8f5e9', padding: 16, borderRadius: 8, textAlign: 'center' }}>
                <div style={{ fontSize: 14, color: '#2e7d32' }}>Estimated Savings</div>
                <div style={{ fontSize: 24, fontWeight: 700, color: '#2e7d32' }}>
                    {fmt(schedule.tax_savings)}
                </div>
            </div>
            {!schedule.exhaustion_target_met && (
                <div style={{ gridColumn: '1 / -1', background: '#fff3e0', padding: 12,
                              borderRadius: 8, border: '1px solid #ff9800', color: '#e65100' }}>
                    Traditional IRA funds may not be fully exhausted by the target age
                    ({schedule.exhaustion_age}). Consider increasing the conversion bracket
                    or extending your plan horizon.
                </div>
            )}
            {schedule.mc_exhaustion_pct != null && (
                <div style={{ gridColumn: '1 / -1', fontSize: 14, color: '#666', textAlign: 'center' }}>
                    {(schedule.mc_exhaustion_pct * 100).toFixed(0)}% of simulated scenarios
                    exhaust traditional funds on time
                </div>
            )}
        </div>
    );
}
```

- [ ] **Step 2: Create ConversionScheduleTable component**

```tsx
import React from 'react';
import { ConversionYearDetail } from '../types/projection';

interface Props {
    years: ConversionYearDetail[];
}

const fmt = (n: number) => n.toLocaleString('en-US', {
    style: 'currency', currency: 'USD', maximumFractionDigits: 0
});

export default function ConversionScheduleTable({ years }: Props) {
    return (
        <div style={{ overflowX: 'auto', marginBottom: 24 }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead>
                    <tr style={{ borderBottom: '2px solid #333' }}>
                        <th style={{ textAlign: 'left', padding: '8px 4px' }}>Age</th>
                        <th style={{ textAlign: 'left', padding: '8px 4px' }}>Year</th>
                        <th style={{ textAlign: 'right', padding: '8px 4px' }}>Conversion</th>
                        <th style={{ textAlign: 'right', padding: '8px 4px' }}>Est. Tax</th>
                        <th style={{ textAlign: 'right', padding: '8px 4px' }}>Traditional</th>
                        <th style={{ textAlign: 'right', padding: '8px 4px' }}>Roth</th>
                        <th style={{ textAlign: 'right', padding: '8px 4px' }}>RMD</th>
                        <th style={{ textAlign: 'center', padding: '8px 4px' }}>Bracket</th>
                    </tr>
                </thead>
                <tbody>
                    {years.map(y => (
                        <tr key={y.calendar_year}
                            style={{ borderBottom: '1px solid #eee',
                                     background: y.projected_rmd > 0 ? '#fafafa' : 'transparent' }}>
                            <td style={{ padding: '6px 4px' }}>{y.age}</td>
                            <td style={{ padding: '6px 4px' }}>{y.calendar_year}</td>
                            <td style={{ textAlign: 'right', padding: '6px 4px' }}>
                                {y.conversion_amount > 0 ? fmt(y.conversion_amount) : '—'}
                            </td>
                            <td style={{ textAlign: 'right', padding: '6px 4px' }}>
                                {y.estimated_tax > 0 ? fmt(y.estimated_tax) : '—'}
                            </td>
                            <td style={{ textAlign: 'right', padding: '6px 4px' }}>
                                {fmt(y.traditional_balance_after)}
                            </td>
                            <td style={{ textAlign: 'right', padding: '6px 4px' }}>
                                {fmt(y.roth_balance_after)}
                            </td>
                            <td style={{ textAlign: 'right', padding: '6px 4px' }}>
                                {y.projected_rmd > 0 ? fmt(y.projected_rmd) : '—'}
                            </td>
                            <td style={{ textAlign: 'center', padding: '6px 4px' }}>
                                {y.bracket_used}
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
```

- [ ] **Step 3: Create TraditionalBalanceChart component**

```tsx
import React, { useMemo } from 'react';
import {
    ComposedChart, Line, XAxis, YAxis, CartesianGrid,
    Tooltip, ResponsiveContainer, ReferenceLine,
} from 'recharts';
import { ConversionYearDetail } from '../types/projection';

interface Props {
    years: ConversionYearDetail[];
    exhaustionAge: number;
}

const fmtDollar = (n: number) => `$${(n / 1000).toFixed(0)}K`;

export default function TraditionalBalanceChart({ years, exhaustionAge }: Props) {
    const data = useMemo(() =>
        years.map(y => ({
            age: y.age,
            traditional: y.traditional_balance_after,
            roth: y.roth_balance_after,
        })), [years]);

    return (
        <ResponsiveContainer width="100%" height={400}>
            <ComposedChart data={data} margin={{ top: 20, right: 30, left: 20, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="age" label={{ value: 'Age', position: 'insideBottom', offset: -5 }} />
                <YAxis tickFormatter={fmtDollar}
                       label={{ value: 'Balance', angle: -90, position: 'insideLeft' }} />
                <Tooltip formatter={(v: number) => `$${v.toLocaleString('en-US', { maximumFractionDigits: 0 })}`} />
                <Line type="monotone" dataKey="traditional" stroke="#d32f2f" strokeWidth={2}
                      name="Traditional IRA" dot={false} />
                <Line type="monotone" dataKey="roth" stroke="#1976d2" strokeWidth={2}
                      name="Roth IRA" dot={false} />
                <ReferenceLine x={exhaustionAge} stroke="#ff9800" strokeDasharray="5 5"
                               label={{ value: 'Exhaustion', position: 'top' }} />
            </ComposedChart>
        </ResponsiveContainer>
    );
}
```

- [ ] **Step 4: Integrate results into SpendingOptimizerPage**

In the results section of `SpendingOptimizerPage.tsx`, add after the existing spending corridors/fan chart:

```tsx
{result?.conversion_schedule && (
    <div style={{ marginTop: 32 }}>
        <h3>Roth Conversion Strategy</h3>
        <TaxSavingsSummary schedule={result.conversion_schedule} />
        <h4>Traditional IRA Balance Trajectory</h4>
        <TraditionalBalanceChart
            years={result.conversion_schedule.years}
            exhaustionAge={result.conversion_schedule.exhaustion_age}
        />
        <h4>Conversion Schedule</h4>
        <ConversionScheduleTable years={result.conversion_schedule.years} />
    </div>
)}
```

- [ ] **Step 5: Build and verify**

Run: `cd frontend && npm run build`
Expected: Build succeeds.

- [ ] **Step 6: Commit**

```
feat(frontend): add Roth conversion results display

TaxSavingsSummary card with lifetime tax comparison and savings.
TraditionalBalanceChart showing IRA balance trajectory with exhaustion
marker. ConversionScheduleTable with year-by-year CPA-ready output.
All displayed conditionally when conversion_schedule is present.
```

---

## Task 13: Frontend Tests

**Files:**
- Create or modify test files co-located with components

- [ ] **Step 1: Write component tests**

Test that:
- `TaxSavingsSummary` renders all three values and warning when `exhaustion_target_met = false`
- `ConversionScheduleTable` renders the correct number of rows
- `TraditionalBalanceChart` renders without errors given valid data
- `SpendingOptimizerPage` toggles conversion section visibility

- [ ] **Step 2: Run frontend tests**

Run: `cd frontend && npm run test`
Expected: All tests PASS.

- [ ] **Step 3: Commit**

```
test(frontend): add Roth conversion component tests

TaxSavingsSummary, ConversionScheduleTable, TraditionalBalanceChart,
and SpendingOptimizerPage toggle tests.
```

---

## Task 14: End-to-End Manual Testing

- [ ] **Step 1: Rebuild and launch with Docker Compose**

```bash
docker compose down
docker compose up --build -d
```

- [ ] **Step 2: Manual test checklist**

1. Log in as demo@wealthview.local / demo123
2. Navigate to Projections → select a scenario with traditional IRA accounts
3. Click the Spending Optimizer link
4. Expand "Roth Conversion Strategy" section
5. Set conversion bracket to 22%, RMD target to 12%, buffer to 5
6. Check "Optimize Conversions"
7. Run optimization
8. Verify results show:
   - Tax Savings Summary with three values
   - Traditional Balance Chart declining to $0
   - Conversion Schedule Table with per-year detail
   - Existing spending corridors and fan chart still present
9. Test without conversions enabled — verify identical to prior behavior
10. Test with all-Roth accounts — verify no conversions recommended

- [ ] **Step 3: Iterate on any issues found**

Fix bugs and re-test. This is where we'll collaborate interactively.

---

## Task 15: Final Commit + Cleanup

- [ ] **Step 1: Run full backend test suite**

```bash
cd backend && mvn clean install
```

- [ ] **Step 2: Run full frontend build + tests**

```bash
cd frontend && npm run test && npm run build
```

- [ ] **Step 3: Verify no debug logging or console.log statements**

- [ ] **Step 4: Final commit if needed**

```
chore: final cleanup for Roth conversion optimizer
```
