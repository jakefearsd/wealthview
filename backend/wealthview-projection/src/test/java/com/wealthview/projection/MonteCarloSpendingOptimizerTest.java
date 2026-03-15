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
                phaseBlendYears
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
    void optimize_corridorWidthIncreasesWithVolatility() {
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var lowVol = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("1000000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "taxable")),
                List.of(),
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.05"),
                500, new BigDecimal("0.95"), phases, 42L,
                BigDecimal.ZERO, null, 0);

        var highVol = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("1000000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "taxable")),
                List.of(),
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.25"),
                500, new BigDecimal("0.95"), phases, 42L,
                BigDecimal.ZERO, null, 0);

        var lowResult = optimizer.optimize(lowVol);
        var highResult = optimizer.optimize(highVol);

        var lowAvgWidth = lowResult.yearlySpending().stream()
                .mapToDouble(y -> y.corridorHigh().subtract(y.corridorLow()).doubleValue())
                .average()
                .orElse(0);
        var highAvgWidth = highResult.yearlySpending().stream()
                .mapToDouble(y -> y.corridorHigh().subtract(y.corridorLow()).doubleValue())
                .average()
                .orElse(0);

        assertThat(highAvgWidth)
                .as("Higher volatility should produce wider corridors")
                .isGreaterThan(lowAvgWidth);
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
                BigDecimal.ZERO, null, 0);

        var aggressiveInput = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("500000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "taxable")),
                List.of(),
                new BigDecimal("10000"), new BigDecimal("100000"),
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                500, new BigDecimal("0.50"), phases, 42L,
                BigDecimal.ZERO, null, 0);

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
}
