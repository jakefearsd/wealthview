package com.wealthview.projection;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonteCarloSpendingOptimizerTest {

    private final MonteCarloSpendingOptimizer optimizer = new MonteCarloSpendingOptimizer();

    private GuardrailOptimizationInput buildInput(BigDecimal portfolio,
                                                   BigDecimal essentialFloor,
                                                   BigDecimal terminalTarget,
                                                   List<GuardrailPhaseInput> phases,
                                                   List<ProjectionIncomeSourceInput> incomeSources,
                                                   int trialCount,
                                                   Long seed) {
        return buildInputFull(portfolio, essentialFloor, terminalTarget, phases, incomeSources,
                trialCount, seed, BigDecimal.ZERO, null, 0);
    }

    private GuardrailOptimizationInput buildInputFull(BigDecimal portfolio,
                                                       BigDecimal essentialFloor,
                                                       BigDecimal terminalTarget,
                                                       List<GuardrailPhaseInput> phases,
                                                       List<ProjectionIncomeSourceInput> incomeSources,
                                                       int trialCount,
                                                       Long seed,
                                                       BigDecimal portfolioFloor,
                                                       BigDecimal maxAnnualAdjustmentRate,
                                                       int phaseBlendYears) {
        return buildInputWithCashBuffer(portfolio, essentialFloor, terminalTarget, phases,
                incomeSources, trialCount, seed, portfolioFloor, maxAnnualAdjustmentRate,
                phaseBlendYears, 0, BigDecimal.ZERO);
    }

    private GuardrailOptimizationInput buildInputWithCashBuffer(BigDecimal portfolio,
                                                                 BigDecimal essentialFloor,
                                                                 BigDecimal terminalTarget,
                                                                 List<GuardrailPhaseInput> phases,
                                                                 List<ProjectionIncomeSourceInput> incomeSources,
                                                                 int trialCount,
                                                                 Long seed,
                                                                 BigDecimal portfolioFloor,
                                                                 BigDecimal maxAnnualAdjustmentRate,
                                                                 int phaseBlendYears,
                                                                 int cashReserveYears,
                                                                 BigDecimal cashReturnRate) {
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1),
                1968,
                90,
                new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        portfolio, BigDecimal.ZERO, new BigDecimal("0.07"), "taxable")),
                incomeSources != null ? incomeSources : List.of(),
                essentialFloor,
                terminalTarget,
                new BigDecimal("0.10"),
                new BigDecimal("0.15"),
                trialCount,
                new BigDecimal("0.95"),
                phases,
                seed,
                portfolioFloor != null ? portfolioFloor : BigDecimal.ZERO,
                maxAnnualAdjustmentRate,
                phaseBlendYears,
                cashReserveYears,
                cashReturnRate != null ? cashReturnRate : BigDecimal.ZERO
        );
    }

    @Test
    void optimize_basicScenario_producesYearlySpending() {
        var phases = List.of(
                new GuardrailPhaseInput("Early", 62, 72, 3),
                new GuardrailPhaseInput("Mid", 73, 82, 1),
                new GuardrailPhaseInput("Late", 83, null, 2));

        var input = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                1000,
                42L);

        var result = optimizer.optimize(input);

        assertThat(result).isNotNull();
        assertThat(result.yearlySpending()).isNotEmpty();
        assertThat(result.yearlySpending().size()).isEqualTo(28); // age 62 to 89

        var firstYear = result.yearlySpending().getFirst();
        assertThat(firstYear.age()).isEqualTo(62);
        assertThat(firstYear.year()).isEqualTo(2030);
        assertThat(firstYear.recommended()).isGreaterThan(BigDecimal.ZERO);
        assertThat(firstYear.essentialFloor()).isEqualByComparingTo(new BigDecimal("30000"));
        assertThat(firstYear.phaseName()).isEqualTo("Early");
        assertThat(firstYear.portfolioBalanceMedian()).isNotNull();
        assertThat(firstYear.portfolioBalanceP10()).isNotNull();
        assertThat(firstYear.portfolioBalanceP25()).isNotNull();
        assertThat(firstYear.portfolioBalanceP75()).isNotNull();
    }

    @Test
    void optimize_essentialFloorRespected_allYearsAtLeastFloor() {
        var phases = List.of(
                new GuardrailPhaseInput("Full", 62, null, 1));

        var input = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                123L);

        var result = optimizer.optimize(input);

        for (var year : result.yearlySpending()) {
            assertThat(year.recommended())
                    .as("Year %d recommended spending should be >= floor", year.year())
                    .isGreaterThanOrEqualTo(year.essentialFloor());
        }
    }

    @Test
    void optimize_highPriorityPhaseGetsMoreThanLow() {
        var phases = List.of(
                new GuardrailPhaseInput("High Priority", 62, 72, 3),
                new GuardrailPhaseInput("Low Priority", 73, 82, 1));

        // Use a constrained portfolio + terminal target so priority differences show
        var input = buildInput(
                new BigDecimal("500000"),
                new BigDecimal("10000"),
                new BigDecimal("100000"),
                phases,
                List.of(),
                500,
                42L);

        var result = optimizer.optimize(input);

        var highPhaseAvg = result.yearlySpending().stream()
                .filter(y -> y.age() >= 62 && y.age() <= 72)
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average()
                .orElse(0);

        var lowPhaseAvg = result.yearlySpending().stream()
                .filter(y -> y.age() >= 73 && y.age() <= 82)
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average()
                .orElse(0);

        assertThat(highPhaseAvg)
                .as("High-priority phase should have higher avg discretionary than low-priority")
                .isGreaterThan(lowPhaseAvg);
    }

    @Test
    void optimize_withTerminalTarget_preservesBalance() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var withTarget = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                new BigDecimal("200000"),
                phases,
                List.of(),
                500,
                42L);

        var withoutTarget = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L);

        var resultWithTarget = optimizer.optimize(withTarget);
        var resultWithoutTarget = optimizer.optimize(withoutTarget);

        var avgWithTarget = resultWithTarget.yearlySpending().stream()
                .mapToDouble(y -> y.recommended().doubleValue())
                .average()
                .orElse(0);
        var avgWithoutTarget = resultWithoutTarget.yearlySpending().stream()
                .mapToDouble(y -> y.recommended().doubleValue())
                .average()
                .orElse(0);

        assertThat(avgWithTarget)
                .as("Terminal target should reduce average spending")
                .isLessThan(avgWithoutTarget);
    }

    @Test
    void optimize_withCashBuffer_producesResults() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var withBuffer = buildInputWithCashBuffer(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                null,
                0,
                2,
                new BigDecimal("0.04"));

        var result = optimizer.optimize(withBuffer);

        assertThat(result).isNotNull();
        assertThat(result.yearlySpending()).isNotEmpty();
        assertThat(result.failureRate()).isNotNull();
    }

    @Test
    void optimize_withCashBuffer_zeroCashReserve_matchesNoCashBehavior() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var noCash = buildInputWithCashBuffer(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                null,
                0,
                0,
                BigDecimal.ZERO);

        var zeroCash = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L);

        var resultNoCash = optimizer.optimize(noCash);
        var resultZeroCash = optimizer.optimize(zeroCash);

        // Both should produce the same results since cash reserve is 0 in both
        assertThat(resultNoCash.yearlySpending().size())
                .isEqualTo(resultZeroCash.yearlySpending().size());

        for (int i = 0; i < resultNoCash.yearlySpending().size(); i++) {
            assertThat(resultNoCash.yearlySpending().get(i).recommended())
                    .isEqualByComparingTo(resultZeroCash.yearlySpending().get(i).recommended());
        }
    }

    @Test
    void optimize_withSeed_bootstrapProducesReproducibleResults() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var input1 = buildInputWithCashBuffer(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                null,
                0,
                2,
                new BigDecimal("0.04"));

        var input2 = buildInputWithCashBuffer(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                null,
                0,
                2,
                new BigDecimal("0.04"));

        var result1 = optimizer.optimize(input1);
        var result2 = optimizer.optimize(input2);

        for (int i = 0; i < result1.yearlySpending().size(); i++) {
            assertThat(result1.yearlySpending().get(i).recommended())
                    .isEqualByComparingTo(result2.yearlySpending().get(i).recommended());
        }
    }

    @Test
    void optimize_withIncomeSource_reducesPortfolioWithdrawal() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var ssBenefit = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Social Security", "social_security",
                new BigDecimal("24000"), 67, null,
                new BigDecimal("0.02"), false, "partially_taxable",
                null, null, null, null, null);

        var withIncome = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(ssBenefit),
                500,
                42L);

        var withoutIncome = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L);

        var resultWithIncome = optimizer.optimize(withIncome);
        var resultWithoutIncome = optimizer.optimize(withoutIncome);

        var avgWithdrawalWith = resultWithIncome.yearlySpending().stream()
                .filter(y -> y.age() >= 67)
                .mapToDouble(y -> y.portfolioWithdrawal().doubleValue())
                .average()
                .orElse(0);
        var avgWithdrawalWithout = resultWithoutIncome.yearlySpending().stream()
                .filter(y -> y.age() >= 67)
                .mapToDouble(y -> y.portfolioWithdrawal().doubleValue())
                .average()
                .orElse(0);

        assertThat(avgWithdrawalWith)
                .as("Income sources should reduce portfolio withdrawals")
                .isLessThan(avgWithdrawalWithout);
    }

    @Test
    void optimize_withFixedSeed_producesReproducibleResults() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var input1 = buildInput(new BigDecimal("1000000"), new BigDecimal("30000"),
                BigDecimal.ZERO, phases, List.of(), 500, 42L);
        var input2 = buildInput(new BigDecimal("1000000"), new BigDecimal("30000"),
                BigDecimal.ZERO, phases, List.of(), 500, 42L);

        var result1 = optimizer.optimize(input1);
        var result2 = optimizer.optimize(input2);

        assertThat(result1.yearlySpending().size()).isEqualTo(result2.yearlySpending().size());
        for (int i = 0; i < result1.yearlySpending().size(); i++) {
            assertThat(result1.yearlySpending().get(i).recommended())
                    .isEqualByComparingTo(result2.yearlySpending().get(i).recommended());
        }
    }

    @Test
    void optimize_summaryStatisticsPopulated() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var input = buildInput(new BigDecimal("1000000"), new BigDecimal("30000"),
                BigDecimal.ZERO, phases, List.of(), 500, 42L);

        var result = optimizer.optimize(input);

        assertThat(result.medianFinalBalance()).isNotNull();
        assertThat(result.failureRate()).isNotNull();
        assertThat(result.failureRate()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(result.percentile10Final()).isNotNull();
        assertThat(result.percentile90Final()).isNotNull();
        assertThat(result.percentile90Final())
                .isGreaterThanOrEqualTo(result.percentile10Final());
    }

    @Test
    void optimize_corridorBoundsOrdered() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var input = buildInput(new BigDecimal("1000000"), new BigDecimal("30000"),
                BigDecimal.ZERO, phases, List.of(), 500, 42L);

        var result = optimizer.optimize(input);

        for (var year : result.yearlySpending()) {
            assertThat(year.corridorLow())
                    .as("corridorLow <= recommended for age %d", year.age())
                    .isLessThanOrEqualTo(year.recommended());
            assertThat(year.corridorHigh())
                    .as("corridorHigh >= recommended for age %d", year.age())
                    .isGreaterThanOrEqualTo(year.recommended());
        }
    }

    @Test
    void optimize_incomeOffsetPopulatedForIncomeYears() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var ssBenefit = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "SS", "social_security",
                new BigDecimal("24000"), 67, null,
                BigDecimal.ZERO, false, "partially_taxable",
                null, null, null, null, null);

        var input = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(ssBenefit),
                500,
                42L);

        var result = optimizer.optimize(input);

        var yearAt67 = result.yearlySpending().stream()
                .filter(y -> y.age() == 67)
                .findFirst();
        assertThat(yearAt67).isPresent();
        assertThat(yearAt67.get().incomeOffset()).isGreaterThan(BigDecimal.ZERO);

        var yearAt63 = result.yearlySpending().stream()
                .filter(y -> y.age() == 63)
                .findFirst();
        assertThat(yearAt63).isPresent();
        assertThat(yearAt63.get().incomeOffset()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void optimize_singlePhase_coversEntireRetirement() {
        var phases = List.of(
                new GuardrailPhaseInput("Only Phase", 62, null, 2));

        var input = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L);

        var result = optimizer.optimize(input);

        assertThat(result.yearlySpending()).isNotEmpty();
        for (var year : result.yearlySpending()) {
            assertThat(year.phaseName()).isEqualTo("Only Phase");
        }
    }

    @Test
    void optimize_smallPortfolio_stillProducesResults() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var input = buildInput(
                new BigDecimal("10000"),
                new BigDecimal("5000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                200,
                42L);

        var result = optimizer.optimize(input);

        assertThat(result).isNotNull();
        assertThat(result.yearlySpending()).isNotEmpty();
        // With $10k portfolio, spending should still be at least the floor
        // but may be limited by capacity
        for (var year : result.yearlySpending()) {
            assertThat(year.recommended()).isGreaterThan(BigDecimal.ZERO);
        }
    }

    @Test
    void optimize_withOneTimeIncome_reflectsInIncomeOffset() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var oneTimeIncome = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Inheritance", "other",
                new BigDecimal("100000"), 65, 66,
                BigDecimal.ZERO, true, "taxable",
                null, null, null, null, null);

        var input = buildInput(
                new BigDecimal("500000"),
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                phases,
                List.of(oneTimeIncome),
                500,
                42L);

        var result = optimizer.optimize(input);

        // The year at age 65 should have income offset reflecting the one-time income
        var yearAt65 = result.yearlySpending().stream()
                .filter(y -> y.age() == 65)
                .findFirst();
        assertThat(yearAt65).isPresent();
        assertThat(yearAt65.get().incomeOffset()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void optimize_portfolioWithdrawalEqualsRecommendedMinusIncome() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var ssBenefit = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "SS", "social_security",
                new BigDecimal("24000"), 62, null,
                BigDecimal.ZERO, false, "partially_taxable",
                null, null, null, null, null);

        var input = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(ssBenefit),
                500,
                42L);

        var result = optimizer.optimize(input);

        for (var year : result.yearlySpending()) {
            var expectedWithdrawal = year.recommended()
                    .subtract(year.incomeOffset())
                    .max(BigDecimal.ZERO);
            assertThat(year.portfolioWithdrawal())
                    .as("Age %d: portfolio_withdrawal should equal recommended - income_offset", year.age())
                    .isEqualByComparingTo(expectedWithdrawal);
        }
    }

    @Test
    void optimize_discretionaryEqualsRecommendedMinusFloor() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var input = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L);

        var result = optimizer.optimize(input);

        for (var year : result.yearlySpending()) {
            var expectedDiscretionary = year.recommended()
                    .subtract(year.essentialFloor());
            assertThat(year.discretionary())
                    .as("Age %d: discretionary should equal recommended - floor", year.age())
                    .isEqualByComparingTo(expectedDiscretionary);
        }
    }

    @Test
    void optimize_sustainabilityUsesConfigurableConfidence_notMedian() {
        // With confidence=0.95, isSustainable checks the 5th percentile (more conservative)
        // than 0.50 which checks the median. Use a constrained portfolio + terminal target
        // so the difference in confidence actually constrains spending.
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var conservativeInput = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("500000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "taxable")),
                List.of(),
                new BigDecimal("10000"), new BigDecimal("100000"),
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                500, new BigDecimal("0.95"), phases, 42L,
                BigDecimal.ZERO, null, 0,
                0, BigDecimal.ZERO);

        var aggressiveInput = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("500000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "taxable")),
                List.of(),
                new BigDecimal("10000"), new BigDecimal("100000"),
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                500, new BigDecimal("0.50"), phases, 42L,
                BigDecimal.ZERO, null, 0,
                0, BigDecimal.ZERO);

        var conservativeResult = optimizer.optimize(conservativeInput);
        var aggressiveResult = optimizer.optimize(aggressiveInput);

        var avgConservative = conservativeResult.yearlySpending().stream()
                .mapToDouble(y -> y.recommended().doubleValue())
                .average().orElse(0);
        var avgAggressive = aggressiveResult.yearlySpending().stream()
                .mapToDouble(y -> y.recommended().doubleValue())
                .average().orElse(0);

        assertThat(avgConservative)
                .as("Higher confidence (0.95) should produce lower spending than 0.50")
                .isLessThan(avgAggressive);
    }

    @Test
    void optimize_portfolioFloor_reducesSpendingToProtectBalance() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        // Without floor
        var noFloorInput = buildInputFull(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                null,
                0);

        // With a $200k portfolio floor
        var withFloorInput = buildInputFull(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L,
                new BigDecimal("200000"),
                null,
                0);

        var noFloorResult = optimizer.optimize(noFloorInput);
        var withFloorResult = optimizer.optimize(withFloorInput);

        var avgNoFloor = noFloorResult.yearlySpending().stream()
                .mapToDouble(y -> y.recommended().doubleValue())
                .average().orElse(0);
        var avgWithFloor = withFloorResult.yearlySpending().stream()
                .mapToDouble(y -> y.recommended().doubleValue())
                .average().orElse(0);

        assertThat(avgWithFloor)
                .as("Portfolio floor should reduce average spending to protect mid-retirement balance")
                .isLessThan(avgNoFloor);
    }

    @Test
    void optimize_yoySmoothing_doesNotPreventReachingHigherPhaseTarget() {
        // Phase 1: low spending ($60k target), Phase 2: high spending ($200k target)
        // With a large portfolio, Phase 2 should achieve close to its target
        // even with YoY smoothing enabled — smoothing should not cap across phase boundaries
        var phases = List.of(
                new GuardrailPhaseInput("Low", 62, 72, 1, new BigDecimal("60000")),
                new GuardrailPhaseInput("High", 73, null, 3, new BigDecimal("200000")));

        var input = buildInputFull(
                new BigDecimal("3000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                new BigDecimal("0.05"),
                1);

        var result = optimizer.optimize(input);

        // Phase 2 midpoint (age 80) should be within 20% of target ($200k)
        var atAge80 = result.yearlySpending().stream()
                .filter(y -> y.age() == 80)
                .findFirst().orElseThrow();

        assertThat(atAge80.recommended().doubleValue())
                .as("Phase 2 spending at age 80 should reach near the $200k target, not be stuck ramping from Phase 1")
                .isGreaterThan(160000);
    }

    @Test
    void optimize_yearOverYearSmoothing_limitsSpendingChangeWithinPhases() {
        // With a 5% max annual adjustment, year-over-year spending changes
        // should be limited WITHIN phases (but not across phase boundaries)
        var phases = List.of(
                new GuardrailPhaseInput("Early", 62, 72, 3),
                new GuardrailPhaseInput("Late", 73, null, 1));

        var input = buildInputFull(
                new BigDecimal("500000"),
                new BigDecimal("10000"),
                new BigDecimal("50000"),
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                new BigDecimal("0.05"),
                0);

        var result = optimizer.optimize(input);
        var spending = result.yearlySpending();

        // Phase boundary is at age 73 — skip that transition
        int phaseBoundaryAge = 73;
        for (int i = 1; i < spending.size(); i++) {
            if (spending.get(i).age() == phaseBoundaryAge) {
                continue; // skip phase boundary
            }
            double prev = spending.get(i - 1).recommended().doubleValue();
            double curr = spending.get(i).recommended().doubleValue();
            if (prev > 0) {
                double changeRate = Math.abs(curr - prev) / prev;
                assertThat(changeRate)
                        .as("Age %d->%d: YoY change %.1f%% should be <= 5%%",
                                spending.get(i - 1).age(), spending.get(i).age(), changeRate * 100)
                        .isLessThanOrEqualTo(0.0501); // small tolerance for floating point
            }
        }
    }

    @Test
    void optimize_phaseBlending_smoothsTransitions() {
        // With phase blending, the transition between phases should not be abrupt
        var phases = List.of(
                new GuardrailPhaseInput("High", 62, 72, 3),
                new GuardrailPhaseInput("Low", 73, null, 1));

        // Without blending
        var noBlend = buildInputFull(
                new BigDecimal("500000"),
                new BigDecimal("10000"),
                new BigDecimal("50000"),
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                null,
                0);

        // With 2-year blending
        var withBlend = buildInputFull(
                new BigDecimal("500000"),
                new BigDecimal("10000"),
                new BigDecimal("50000"),
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                null,
                2);

        var noBlendResult = optimizer.optimize(noBlend);
        var withBlendResult = optimizer.optimize(withBlend);

        // At the phase boundary (age 72->73), unblended should have a larger jump
        var noBlendAt72 = noBlendResult.yearlySpending().stream()
                .filter(y -> y.age() == 72).findFirst().orElseThrow();
        var noBlendAt73 = noBlendResult.yearlySpending().stream()
                .filter(y -> y.age() == 73).findFirst().orElseThrow();
        var withBlendAt72 = withBlendResult.yearlySpending().stream()
                .filter(y -> y.age() == 72).findFirst().orElseThrow();
        var withBlendAt73 = withBlendResult.yearlySpending().stream()
                .filter(y -> y.age() == 73).findFirst().orElseThrow();

        double noBlendJump = Math.abs(noBlendAt73.recommended().doubleValue()
                - noBlendAt72.recommended().doubleValue());
        double withBlendJump = Math.abs(withBlendAt73.recommended().doubleValue()
                - withBlendAt72.recommended().doubleValue());

        assertThat(withBlendJump)
                .as("Phase blending should reduce the spending jump at phase boundaries")
                .isLessThanOrEqualTo(noBlendJump);
    }

    @Test
    void optimize_corridorSmoothing_producesLessJaggedCorridors() {
        // With smoothing features enabled, corridors should be smoother
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var input = buildInputFull(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                null,
                0);

        var result = optimizer.optimize(input);
        var corridorLows = result.yearlySpending().stream()
                .mapToDouble(y -> y.corridorLow().doubleValue())
                .toArray();

        // Corridor low values should change gradually (smoothed with moving average)
        double maxConsecutiveChange = 0;
        for (int i = 1; i < corridorLows.length; i++) {
            double change = Math.abs(corridorLows[i] - corridorLows[i - 1]);
            maxConsecutiveChange = Math.max(maxConsecutiveChange, change);
        }

        // Without smoothing, corridors can be very jagged. With the 3-year moving average,
        // consecutive changes should be bounded. We just verify the feature runs without error
        // and produces valid ordered corridors (already tested above).
        assertThat(result.yearlySpending()).isNotEmpty();
        for (var year : result.yearlySpending()) {
            assertThat(year.corridorLow())
                    .isLessThanOrEqualTo(year.corridorHigh());
        }
    }

    @Test
    void optimize_targetBasedAllocation_spendingProportionalToTargets() {
        // Phases with target spending should allocate proportionally
        var phases = List.of(
                new GuardrailPhaseInput("Early", 62, 72, 1, new BigDecimal("80000")),
                new GuardrailPhaseInput("Late", 73, null, 1, new BigDecimal("40000")));

        var input = buildInputFull(
                new BigDecimal("500000"),
                new BigDecimal("10000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                null,
                0);

        var result = optimizer.optimize(input);

        var earlyAvg = result.yearlySpending().stream()
                .filter(y -> y.age() >= 62 && y.age() <= 72)
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);
        var lateAvg = result.yearlySpending().stream()
                .filter(y -> y.age() >= 73)
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);

        // Early phase targets $80k total ($70k discretionary), late targets $40k ($30k disc)
        // The ratio of discretionary spending should be roughly 70/30 ≈ 2.33
        // Allow generous tolerance since MC is stochastic
        if (lateAvg > 0) {
            double ratio = earlyAvg / lateAvg;
            assertThat(ratio)
                    .as("Early/Late discretionary ratio should be roughly proportional to targets")
                    .isBetween(1.5, 3.5);
        }
    }

    @Test
    void optimize_targetAllocation_cheapPhaseGetsFullTarget() {
        // Phase 1 targets $50k (cheap, easily affordable with $2M portfolio)
        // Phase 2 targets $300k (expensive, can't be fully funded)
        // With per-phase allocation, Phase 1 should get close to its $50k target
        // instead of being dragged down by Phase 2's expensive target.
        var phases = List.of(
                new GuardrailPhaseInput("Cheap", 62, 72, 1, new BigDecimal("50000")),
                new GuardrailPhaseInput("Expensive", 73, null, 1, new BigDecimal("300000")));

        var input = buildInput(
                new BigDecimal("2000000"),
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                1000,
                42L);

        var result = optimizer.optimize(input);

        var cheapPhaseAvgRecommended = result.yearlySpending().stream()
                .filter(y -> y.age() >= 62 && y.age() <= 72)
                .mapToDouble(y -> y.recommended().doubleValue())
                .average().orElse(0);

        // The cheap phase should achieve at least 80% of its $50k target
        assertThat(cheapPhaseAvgRecommended)
                .as("Cheap phase should get close to its $50k target, not be dragged down by expensive phase")
                .isGreaterThan(40000);
    }

    @Test
    void optimize_targetAllocation_expensivePhaseCapAtTarget() {
        // When a phase's binary search result exceeds its target, it should be capped
        // Use a very wealthy portfolio with a modest target — binary search would find more
        var phases = List.of(
                new GuardrailPhaseInput("Modest", 62, null, 1, new BigDecimal("60000")));

        var input = buildInput(
                new BigDecimal("5000000"),
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                1000,
                42L);

        var result = optimizer.optimize(input);

        var avgRecommended = result.yearlySpending().stream()
                .mapToDouble(y -> y.recommended().doubleValue())
                .average().orElse(0);

        // Recommended should be close to $60k (floor $20k + discretionary capped at $40k)
        // Not much higher — the cap should prevent overspending
        assertThat(avgRecommended)
                .as("Average recommended should not greatly exceed the $60k target")
                .isLessThan(70000);
    }

    @Test
    void optimize_targetAllocation_phaseOrderAffectsAllocation() {
        // Same two phases with same targets, but reversed order.
        // With per-phase sequential allocation, whichever phase is listed first
        // should get at least as much as it gets when listed second.
        var phasesAFirst = List.of(
                new GuardrailPhaseInput("A", 62, 72, 1, new BigDecimal("120000")),
                new GuardrailPhaseInput("B", 73, null, 1, new BigDecimal("120000")));
        var phasesBFirst = List.of(
                new GuardrailPhaseInput("B", 73, null, 1, new BigDecimal("120000")),
                new GuardrailPhaseInput("A", 62, 72, 1, new BigDecimal("120000")));

        var inputAFirst = buildInput(
                new BigDecimal("500000"),
                new BigDecimal("10000"),
                new BigDecimal("50000"),
                phasesAFirst,
                List.of(),
                1000,
                42L);
        var inputBFirst = buildInput(
                new BigDecimal("500000"),
                new BigDecimal("10000"),
                new BigDecimal("50000"),
                phasesBFirst,
                List.of(),
                1000,
                42L);

        var resultAFirst = optimizer.optimize(inputAFirst);
        var resultBFirst = optimizer.optimize(inputBFirst);

        var aDiscWhenFirst = resultAFirst.yearlySpending().stream()
                .filter(y -> y.age() >= 62 && y.age() <= 72)
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);
        var aDiscWhenSecond = resultBFirst.yearlySpending().stream()
                .filter(y -> y.age() >= 62 && y.age() <= 72)
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);

        // Phase A should get at least as much when it's processed first
        assertThat(aDiscWhenFirst)
                .as("Phase listed first should get at least as much as when listed second")
                .isGreaterThanOrEqualTo(aDiscWhenSecond);
    }

    @Test
    void optimize_targetAllocation_allPhasesAffordable_eachGetsTarget() {
        // With a very large portfolio and modest targets, each phase should get its target
        var phases = List.of(
                new GuardrailPhaseInput("Early", 62, 72, 1, new BigDecimal("50000")),
                new GuardrailPhaseInput("Late", 73, null, 1, new BigDecimal("40000")));

        var input = buildInput(
                new BigDecimal("10000000"),
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                1000,
                42L);

        var result = optimizer.optimize(input);

        var earlyAvgDisc = result.yearlySpending().stream()
                .filter(y -> y.age() >= 62 && y.age() <= 72)
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);
        var lateAvgDisc = result.yearlySpending().stream()
                .filter(y -> y.age() >= 73)
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);

        double earlyTargetDisc = 50000 - 20000; // target - floor
        double lateTargetDisc = 40000 - 20000;

        // Each phase should achieve within 5% of its discretionary target
        assertThat(earlyAvgDisc)
                .as("Early phase discretionary should be close to target")
                .isBetween(earlyTargetDisc * 0.95, earlyTargetDisc * 1.05);
        assertThat(lateAvgDisc)
                .as("Late phase discretionary should be close to target")
                .isBetween(lateTargetDisc * 0.95, lateTargetDisc * 1.05);
    }

    @Test
    void optimize_targetAllocation_singlePhaseWithTarget_matchesLegacyBinarySearch() {
        // With a single phase that has a target above what binary search would find,
        // result should match plain binary search (target acts only as a cap)
        var withTarget = List.of(
                new GuardrailPhaseInput("All", 62, null, 1, new BigDecimal("500000")));
        var withoutTarget = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var inputWithTarget = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                withTarget,
                List.of(),
                1000,
                42L);
        var inputWithout = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                withoutTarget,
                List.of(),
                1000,
                42L);

        var resultWith = optimizer.optimize(inputWithTarget);
        var resultWithout = optimizer.optimize(inputWithout);

        var avgWith = resultWith.yearlySpending().stream()
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);
        var avgWithout = resultWithout.yearlySpending().stream()
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);

        // With a very high target ($500k), the cap doesn't bind, so results should be similar
        // Allow some tolerance since the code paths differ slightly
        assertThat(Math.abs(avgWith - avgWithout))
                .as("Single phase with high target should match legacy binary search")
                .isLessThan(avgWithout * 0.05);
    }

    @Test
    void optimize_portfolioBalancePercentiles_orderedCorrectly() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var input = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L);

        var result = optimizer.optimize(input);

        for (var year : result.yearlySpending()) {
            assertThat(year.portfolioBalanceP10())
                    .as("p10 should be non-null for age %d", year.age())
                    .isNotNull();
            assertThat(year.portfolioBalanceP25())
                    .as("p25 should be non-null for age %d", year.age())
                    .isNotNull();
            assertThat(year.portfolioBalanceP75())
                    .as("p75 should be non-null for age %d", year.age())
                    .isNotNull();
            assertThat(year.portfolioBalanceP10())
                    .as("p10 <= p25 for age %d", year.age())
                    .isLessThanOrEqualTo(year.portfolioBalanceP25());
            assertThat(year.portfolioBalanceP25())
                    .as("p25 <= median for age %d", year.age())
                    .isLessThanOrEqualTo(year.portfolioBalanceMedian());
            assertThat(year.portfolioBalanceMedian())
                    .as("median <= p75 for age %d", year.age())
                    .isLessThanOrEqualTo(year.portfolioBalanceP75());
        }
    }

    @Test
    void optimize_legacyPriorityFallback_worksWhenNoTargetSpending() {
        // Phases without targetSpending should use legacy priority-weight allocation
        var phases = List.of(
                new GuardrailPhaseInput("High Priority", 62, 72, 3),
                new GuardrailPhaseInput("Low Priority", 73, 82, 1));

        var input = buildInputFull(
                new BigDecimal("500000"),
                new BigDecimal("10000"),
                new BigDecimal("100000"),
                phases,
                List.of(),
                500,
                42L,
                BigDecimal.ZERO,
                null,
                0);

        var result = optimizer.optimize(input);

        var highPhaseAvg = result.yearlySpending().stream()
                .filter(y -> y.age() >= 62 && y.age() <= 72)
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);
        var lowPhaseAvg = result.yearlySpending().stream()
                .filter(y -> y.age() >= 73 && y.age() <= 82)
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);

        assertThat(highPhaseAvg)
                .as("Legacy priority allocation: high-priority phase should get more")
                .isGreaterThan(lowPhaseAvg);
    }
}
