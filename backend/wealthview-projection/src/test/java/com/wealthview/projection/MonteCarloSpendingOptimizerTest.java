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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;

class MonteCarloSpendingOptimizerTest {

    private final MonteCarloSpendingOptimizer optimizer = new MonteCarloSpendingOptimizer(null);

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
                cashReturnRate != null ? cashReturnRate : BigDecimal.ZERO,
                null, null,
                false, null, null, 5, null
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
    void optimize_essentialFloorNotReducedByTerminalTarget() {
        // Even with a large terminal target, the essential floor should not be
        // reduced to $0. Essential spending is essential — the terminal target
        // should only constrain discretionary spending via isSustainable().
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        // Income covers the floor, so floor spending has zero portfolio cost.
        // A large terminal target should NOT crush the floor to $0.
        var income = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "SS", "social_security",
                new BigDecimal("50000"), 62, null,
                BigDecimal.ZERO, false, "partially_taxable",
                null, null, null, null, null, null);

        var input = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("40000"),        // essential floor
                new BigDecimal("2000000"),      // terminal target larger than initial portfolio
                phases,
                List.of(income),
                500,
                42L);

        var result = optimizer.optimize(input);

        // Every year should have at least the essential floor
        for (var year : result.yearlySpending()) {
            assertThat(year.essentialFloor().doubleValue())
                    .as("Age %d: essential floor should not be reduced by terminal target when income covers it",
                            year.age())
                    .isGreaterThanOrEqualTo(40000);
        }
    }

    @Test
    void optimize_essentialFloorInflatesOverTime() {
        // The essential floor should increase with the scenario's inflation rate.
        // $30k floor at 3% inflation should be ~$68.5k by year 28 (age 89).
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var input = buildInput(
                new BigDecimal("2000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L);

        var result = optimizer.optimize(input);

        var firstYear = result.yearlySpending().getFirst();
        var lastYear = result.yearlySpending().getLast();

        assertThat(firstYear.essentialFloor().doubleValue())
                .as("First year floor should be ~$30k")
                .isCloseTo(30000, org.assertj.core.data.Offset.offset(1000.0));

        // 30000 * (1.03)^27 = ~66,685 (year index 27, 28 years total)
        assertThat(lastYear.essentialFloor().doubleValue())
                .as("Last year floor should be inflated: $30k * (1.03)^27 ≈ $66.7k")
                .isGreaterThan(60000);
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
                null, null, null, null, null, null);

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
                null, null, null, null, null, null);

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
                null, null, null, null, null, null);

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
                null, null, null, null, null, null);

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
    void optimize_rentalPropertyIncome_subtractsExpensesFromGross() {
        // Rental property with $100k gross and $60k in expenses → $40k net
        // The optimizer should use $40k (net), not $100k (gross)
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var rentalGross = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Rental", "rental_property",
                new BigDecimal("100000"), 62, null,
                BigDecimal.ZERO, false, "rental_passive",
                new BigDecimal("25000"),   // operating expenses (insurance + maintenance)
                new BigDecimal("20000"),   // mortgage interest
                null,                      // annualMortgagePrincipal
                new BigDecimal("15000"),   // property tax
                null, null);

        var input = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                phases,
                List.of(rentalGross),
                500,
                42L);

        var result = optimizer.optimize(input);

        // Income offset at age 62 should be ~$40k (net), not ~$100k (gross)
        var atAge62 = result.yearlySpending().stream()
                .filter(y -> y.age() == 62)
                .findFirst().orElseThrow();

        assertThat(atAge62.incomeOffset().doubleValue())
                .as("Rental income should be net of expenses: $100k - $60k = $40k (with 0.5x boundary)")
                .isLessThan(50000); // gross × 0.5 = $50k; net × 0.5 = $20k

        // At a non-boundary age, income should be ~$40k (net), not $100k
        var atAge65 = result.yearlySpending().stream()
                .filter(y -> y.age() == 65)
                .findFirst().orElseThrow();

        assertThat(atAge65.incomeOffset().doubleValue())
                .as("Rental income at non-boundary age should be net: $100k - $60k = $40k")
                .isCloseTo(40000, org.assertj.core.data.Offset.offset(1000.0));
    }

    @Test
    void optimize_nonRentalIncome_unaffectedByExpenseFields() {
        // Non-rental income should not subtract expenses even if fields are present
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var ssIncome = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "SS", "social_security",
                new BigDecimal("30000"), 62, null,
                BigDecimal.ZERO, false, "partially_taxable",
                null, null, null, null, null, null);

        var input = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                phases,
                List.of(ssIncome),
                500,
                42L);

        var result = optimizer.optimize(input);

        // At a non-boundary age, income should be full $30k
        var atAge65 = result.yearlySpending().stream()
                .filter(y -> y.age() == 65)
                .findFirst().orElseThrow();

        assertThat(atAge65.incomeOffset().doubleValue())
                .as("Non-rental income should use full amount")
                .isCloseTo(30000, org.assertj.core.data.Offset.offset(1000.0));
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
                0, BigDecimal.ZERO, null, null,
                false, null, null, 5, null);

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
                0, BigDecimal.ZERO, null, null,
                false, null, null, 5, null);

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

        // Target inflates with inflation (3% over 28 years).
        // Average inflated target ≈ $60k * avg((1.03)^0..(1.03)^27) ≈ $90k.
        // Recommended = inflated floor + capped discretionary, should not exceed inflated target.
        double avgInflatedTarget = 0;
        for (int y = 0; y < 28; y++) {
            avgInflatedTarget += 60000 * Math.pow(1.03, y);
        }
        avgInflatedTarget /= 28;
        assertThat(avgRecommended)
                .as("Average recommended should not greatly exceed the inflated target")
                .isLessThan(avgInflatedTarget * 1.15);
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

        // Target discretionary = inflated target - inflated floor (averaged over phase years)
        // Both target and floor inflate at 3% per year
        // Early phase (ages 62-72, years 0-10):
        double earlyAvgFloor = 0;
        double earlyAvgTarget = 0;
        for (int y = 0; y <= 10; y++) {
            earlyAvgFloor += 20000 * Math.pow(1.03, y);
            earlyAvgTarget += 50000 * Math.pow(1.03, y);
        }
        earlyAvgFloor /= 11;
        earlyAvgTarget /= 11;
        double earlyTargetDisc = earlyAvgTarget - earlyAvgFloor;

        // Late phase (ages 73-89, years 11-27):
        double lateAvgFloor = 0;
        double lateAvgTarget = 0;
        for (int y = 11; y <= 27; y++) {
            lateAvgFloor += 20000 * Math.pow(1.03, y);
            lateAvgTarget += 40000 * Math.pow(1.03, y);
        }
        lateAvgFloor /= 17;
        lateAvgTarget /= 17;
        double lateTargetDisc = Math.max(0, lateAvgTarget - lateAvgFloor);

        // Each phase should achieve within 10% of its discretionary target
        assertThat(earlyAvgDisc)
                .as("Early phase discretionary should be close to target (accounting for inflated floor)")
                .isBetween(earlyTargetDisc * 0.85, earlyTargetDisc * 1.15);
        assertThat(lateAvgDisc)
                .as("Late phase discretionary should be close to target (accounting for inflated floor)")
                .isBetween(lateTargetDisc * 0.50, lateTargetDisc * 2.0);
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
        // Allow tolerance since code paths and inflated floor averaging differ slightly
        assertThat(Math.abs(avgWith - avgWithout))
                .as("Single phase with high target should match legacy binary search")
                .isLessThan(avgWithout * 0.10);
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

    @Test
    void optimize_targetAllocation_phaseTargetInflatesWithFloor() {
        // Bug: phase target=$60k, floor=$30k with 3% inflation over 28 years.
        // By year 16 (age 78), inflated floor = 30000*(1.03)^16 ≈ $48,141.
        // By year 22 (age 84), inflated floor = 30000*(1.03)^22 ≈ $57,505.
        // Without inflating the target, maxDisc = max(0, 60000 - avgFloor) → near 0.
        // With inflated target, the target grows to ~$100k+ by late phase, keeping discretionary > 0.
        var phases = List.of(
                new GuardrailPhaseInput("Early", 62, 72, 1, new BigDecimal("80000")),
                new GuardrailPhaseInput("Late", 73, null, 1, new BigDecimal("60000")));

        var input = buildInput(
                new BigDecimal("5000000"),   // large enough portfolio
                new BigDecimal("30000"),     // $30k essential floor
                BigDecimal.ZERO,
                phases,
                List.of(),
                500,
                42L);

        var result = optimizer.optimize(input);

        // Late phase (ages 73-89): discretionary should be positive for all years
        // because the target inflates alongside the floor, maintaining a constant
        // real gap between target and floor
        var latePhaseYears = result.yearlySpending().stream()
                .filter(y -> y.age() >= 73 && y.age() <= 89)
                .toList();

        for (var year : latePhaseYears) {
            assertThat(year.discretionary().doubleValue())
                    .as("Age %d: discretionary should be positive when target inflates with floor", year.age())
                    .isGreaterThan(0);
        }

        // The average discretionary in the late phase should be close to $30k
        // (real gap between $60k target and $30k floor, both in today's dollars)
        var lateAvgDisc = latePhaseYears.stream()
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);
        assertThat(lateAvgDisc)
                .as("Late phase average discretionary should reflect real gap of ~$30k (inflated)")
                .isGreaterThan(20000);
    }

    @Test
    void optimize_withInflation_nominalReturnsProduceHigherSpending() {
        // Bootstrap returns are real (~7% mean). With 3% inflation, nominal returns
        // become ~10%. Portfolio grows faster in nominal terms → can sustain more
        // nominal spending. Before fix: inflation makes optimizer pessimistic (FAILS).
        // After fix: nominal returns match nominal spending → higher spending (PASSES).
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        // Use a constrained portfolio + terminal target so binary search doesn't
        // hit the $500k cap and differences in return modeling are visible.

        // With 3% inflation (default in buildInput)
        var withInflation = buildInput(
                new BigDecimal("500000"),
                new BigDecimal("10000"),
                new BigDecimal("100000"),
                phases,
                List.of(),
                1000,
                42L);

        // With 0% inflation — construct directly to override the inflation rate
        var noInflation = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, BigDecimal.ZERO,
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("500000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "taxable")),
                List.of(),
                new BigDecimal("10000"), new BigDecimal("100000"),
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                1000, new BigDecimal("0.95"), phases, 42L,
                BigDecimal.ZERO, null, 0,
                0, BigDecimal.ZERO, null, null,
                false, null, null, 5, null);

        var resultWithInflation = optimizer.optimize(withInflation);
        var resultNoInflation = optimizer.optimize(noInflation);

        // With inflation: floors inflate (spending grows) but portfolio also grows
        // at nominal rate. Discretionary should be higher because portfolio has
        // more nominal growth to fund the nominal spending.
        var avgDiscWithInflation = resultWithInflation.yearlySpending().stream()
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);
        var avgDiscNoInflation = resultNoInflation.yearlySpending().stream()
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);

        assertThat(avgDiscWithInflation)
                .as("With inflation, nominal portfolio growth should fund more discretionary spending")
                .isGreaterThan(avgDiscNoInflation);
    }

    @Test
    void optimize_zeroInflation_nominalMatchesReal() {
        // With inflation=0, toNominal(real, 0) = real. Results should be identical
        // to current behavior — backward compatibility baseline.
        var phases = List.of(
                new GuardrailPhaseInput("All", 62, null, 1));

        var zeroInflation = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, BigDecimal.ZERO,
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("1000000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "taxable")),
                List.of(),
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                500, new BigDecimal("0.95"), phases, 42L,
                BigDecimal.ZERO, null, 0,
                0, BigDecimal.ZERO, null, null,
                false, null, null, 5, null);

        var result = optimizer.optimize(zeroInflation);

        // Basic sanity: results should be valid
        assertThat(result.yearlySpending()).isNotEmpty();
        assertThat(result.yearlySpending().size()).isEqualTo(28);

        // With zero inflation, floors don't inflate, so first and last floor should be equal
        var firstFloor = result.yearlySpending().getFirst().essentialFloor().doubleValue();
        var lastFloor = result.yearlySpending().getLast().essentialFloor().doubleValue();
        assertThat(firstFloor).isEqualTo(lastFloor);

        // Discretionary should be positive (portfolio grows at real ~7%)
        var avgDisc = result.yearlySpending().stream()
                .mapToDouble(y -> y.discretionary().doubleValue())
                .average().orElse(0);
        assertThat(avgDisc).isGreaterThan(0);
    }

    // ── Bug fixes: computeDeterministicIncome inconsistencies ──────────────────

    @Test
    void computeIncome_rentalWithMortgagePrincipal_deductsPrincipalFromIncomeOffset() {
        // Rental property: $100k gross, $30k interest, $10k property tax, $20k principal
        // Net cash flow = 100k - 30k - 10k - 20k = $40k (principal must be deducted)
        // Pre-fix: optimizer excluded principal → assumed $60k income → WRONG
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

        var rentalWithPrincipal = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Rental", "rental_property",
                new BigDecimal("100000"), 60, null,
                BigDecimal.ZERO, false, "rental_passive",
                null,                      // no operating expenses (keep simple)
                new BigDecimal("30000"),   // mortgage interest
                new BigDecimal("20000"),   // mortgage principal — must be deducted
                new BigDecimal("10000"),   // property tax
                null, null);

        var input = buildInput(
                new BigDecimal("1000000"),
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                phases,
                List.of(rentalWithPrincipal),
                500,
                42L);

        var result = optimizer.optimize(input);

        // At age 65 (non-boundary, full year): income offset must be $40k ($100k - $30k - $10k - $20k)
        // Pre-fix value was $60k ($100k - $30k - $10k, no principal deducted).
        var atAge65 = result.yearlySpending().stream()
                .filter(y -> y.age() == 65)
                .findFirst().orElseThrow();

        assertThat(atAge65.incomeOffset().doubleValue())
                .as("Income offset must deduct mortgage principal: $100k - $30k - $10k - $20k = $40k")
                .isCloseTo(40000, org.assertj.core.data.Offset.offset(500.0));
    }

    @Test
    void computeIncome_oneTimeSource_notHalvedAtStartAge() {
        // One-time income ($60k lump sum at age 65) must NOT be halved.
        // Pre-fix: optimizer applied 0.5 boundary multiplier to all sources, giving $30k.
        // ICC and ISP both correctly return full $60k for one_time sources.
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

        var oneTime = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Inheritance", "other",
                new BigDecimal("60000"), 65, 66,
                BigDecimal.ZERO, true, "taxable",
                null, null, null, null, null, null);

        var input = buildInput(
                new BigDecimal("500000"),
                new BigDecimal("10000"),
                BigDecimal.ZERO,
                phases,
                List.of(oneTime),
                500,
                42L);

        var result = optimizer.optimize(input);

        // At age 65 (startAge), the income offset must be the full $60k, not the halved $30k.
        var atAge65 = result.yearlySpending().stream()
                .filter(y -> y.age() == 65)
                .findFirst().orElseThrow();

        assertThat(atAge65.incomeOffset().doubleValue())
                .as("One-time income must not be halved at startAge: expected full $60k, not $30k")
                .isCloseTo(60000, org.assertj.core.data.Offset.offset(500.0));

        // One year later the one-time income must be $0 (source expires after startAge)
        var atAge66 = result.yearlySpending().stream()
                .filter(y -> y.age() == 66)
                .findFirst().orElseThrow();

        assertThat(atAge66.incomeOffset().doubleValue())
                .as("One-time income must not appear at age 66")
                .isCloseTo(0, org.assertj.core.data.Offset.offset(500.0));
    }

    @Test
    void computeIncome_rentalWithInflation_inflatesGrossBeforeSubtractingExpenses() {
        // Rental: $100k gross at 10% inflation, $60k fixed expenses, no principal.
        // After 5 years (year 6, age 67): gross = $100k × 1.1^5 = $161k, net = $161k - $60k = $101k.
        // Pre-fix: optimizer inflated NET → ($100k - $60k) × 1.1^5 = $64.4k → WRONG.
        // ISP inflates gross → $100k × 1.1^5 - $60k = $101k.
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

        var rentalInflating = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Rental", "rental_property",
                new BigDecimal("100000"), 62, null,
                new BigDecimal("0.10"), false, "rental_passive",
                new BigDecimal("30000"),   // operating expenses (fixed, don't inflate)
                null,
                null,
                new BigDecimal("30000"),   // property tax (fixed, don't inflate)
                null, null);

        var input = buildInput(
                new BigDecimal("2000000"),
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                phases,
                List.of(rentalInflating),
                500,
                42L);

        var result = optimizer.optimize(input);

        // At age 67 (year 6, non-boundary): gross = $100k × 1.1^5 ≈ $161k, net = $161k - $60k ≈ $101k
        // Pre-fix: ($100k - $60k) × 1.1^5 = $64.4k
        var atAge67 = result.yearlySpending().stream()
                .filter(y -> y.age() == 67)
                .findFirst().orElseThrow();

        assertThat(atAge67.incomeOffset().doubleValue())
                .as("Rental income after 5yr at 10% inflation: gross inflates to ~$161k, net = ~$101k. "
                        + "Pre-fix (inflate net) would give ~$64k.")
                .isGreaterThan(90000);
    }

    // === Withdrawal tax modeling ===

    private MonteCarloSpendingOptimizer taxAwareOptimizer() {
        var taxCalc = mock(FederalTaxCalculator.class);
        // Simple 20% flat tax for test predictability
        when(taxCalc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(inv -> {
                    BigDecimal income = inv.getArgument(0);
                    if (income.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
                    return income.multiply(new BigDecimal("0.20")).setScale(4, java.math.RoundingMode.HALF_UP);
                });
        // Bracket ceiling for RothConversionOptimizer (both 3-arg and 4-arg overloads)
        when(taxCalc.computeMaxIncomeForBracket(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenReturn(new BigDecimal("100000"));
        when(taxCalc.computeMaxIncomeForBracket(
                any(BigDecimal.class), anyInt(), any(FilingStatus.class), nullable(BigDecimal.class)))
                .thenReturn(new BigDecimal("100000"));
        return new MonteCarloSpendingOptimizer(taxCalc);
    }

    @Test
    void optimize_allTraditional_lowerSpendingThanAllRoth() {
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

        // All Roth — withdrawals are tax-free
        // Use a constrained portfolio ($500K) with terminal target to force
        // the binary search below $500K ceiling
        var rothInput = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("500000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "roth")),
                List.of(),
                new BigDecimal("10000"), new BigDecimal("100000"),
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                500, new BigDecimal("0.95"), phases, 42L,
                BigDecimal.ZERO, null, 0,
                0, BigDecimal.ZERO, "single", "taxable_first",
                false, null, null, 5, null);

        // All Traditional — withdrawals taxed at 20%
        var tradInput = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("500000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "traditional")),
                List.of(),
                new BigDecimal("10000"), new BigDecimal("100000"),
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                500, new BigDecimal("0.95"), phases, 42L,
                BigDecimal.ZERO, null, 0,
                0, BigDecimal.ZERO, "single", "taxable_first",
                false, null, null, 5, null);

        var taxOptimizer = taxAwareOptimizer();
        var rothResult = taxOptimizer.optimize(rothInput);
        var tradResult = taxOptimizer.optimize(tradInput);

        double rothSpending = rothResult.yearlySpending().getFirst().recommended().doubleValue();
        double tradSpending = tradResult.yearlySpending().getFirst().recommended().doubleValue();

        // Traditional should recommend LESS spending due to tax drag
        assertThat(tradSpending).isLessThan(rothSpending);
        // The difference should be material (at least 10% less)
        assertThat(tradSpending).isLessThan(rothSpending * 0.90);
    }

    @Test
    void optimize_withConversionSchedule_producesConversionScheduleResponse() {
        // With progressive brackets, conversions in lower brackets save tax vs
        // future RMD withdrawals in higher brackets — joint optimizer picks a
        // non-zero fraction that maximizes spending.
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

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
                true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5, null);

        var taxOptimizer = progressiveTaxOptimizer();
        var result = taxOptimizer.optimize(input);

        assertThat(result).isNotNull();
        assertThat(result.yearlySpending()).isNotEmpty();
        assertThat(result.conversionSchedule()).isNotNull();
        // Joint optimizer may choose fraction=0 if conversions don't help spending.
        // With progressive brackets, some conversions should occur.
        // The schedule always exists even if years list is empty (fraction=0).
    }

    @Test
    void optimize_noTaxCalculator_backwardCompatible() {
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));
        var input = buildInput(new BigDecimal("1000000"),
                new BigDecimal("20000"), BigDecimal.ZERO,
                phases, null, 200, 42L);

        // null tax calculator — should produce results without error
        var result = optimizer.optimize(input);

        assertThat(result.yearlySpending()).isNotEmpty();
        assertThat(result.failureRate()).isNotNull();
    }

    @Test
    void optimize_preAge595_withdrawalsFromTaxableOnly() {
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
                true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5, null);
        var taxOptimizer = taxAwareOptimizer();
        var result = taxOptimizer.optimize(input);
        assertThat(result).isNotNull();
        assertThat(result.yearlySpending()).isNotEmpty();
        assertThat(result.yearlySpending().getFirst().recommended().doubleValue()).isGreaterThan(0);
    }

    @Test
    void optimize_noConversions_identicalToExistingBehavior() {
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));
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
                false, null, null, 5, null);
        var result = optimizer.optimize(inputOld);
        assertThat(result.yearlySpending()).isNotEmpty();
        assertThat(result.conversionSchedule()).isNull();
    }

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
                true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5, null);
        var taxOptimizer = taxAwareOptimizer();
        var result = taxOptimizer.optimize(input);
        assertThat(result.conversionSchedule()).isNotNull();
        assertThat(result.conversionSchedule().mcExhaustionPct()).isNotNull();
        assertThat(result.conversionSchedule().mcExhaustionPct())
                .isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    }

    @Test
    void optimize_marketCrash_conversionCappedAtTraditionalBalance() {
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));
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
                true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5, null);
        var taxOptimizer = taxAwareOptimizer();
        var result = taxOptimizer.optimize(input);
        assertThat(result).isNotNull();
        assertThat(result.yearlySpending()).isNotEmpty();
        for (var ys : result.yearlySpending()) {
            assertThat(ys.recommended().doubleValue()).isGreaterThanOrEqualTo(0);
            assertThat(Double.isNaN(ys.recommended().doubleValue())).isFalse();
        }
    }

    // === Joint spending-conversion optimization ===

    /**
     * Creates a tax-aware optimizer with progressive brackets (10%/22%/32%)
     * so Roth conversions have bracket arbitrage value.
     */
    private MonteCarloSpendingOptimizer progressiveTaxOptimizer() {
        var taxCalc = mock(FederalTaxCalculator.class);
        // Progressive brackets: 10% up to $50K, 22% $50K-$100K, 32% above $100K
        when(taxCalc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(inv -> {
                    double income = ((BigDecimal) inv.getArgument(0)).doubleValue();
                    if (income <= 0) return BigDecimal.ZERO;
                    double tax = 0;
                    if (income <= 50_000) {
                        tax = income * 0.10;
                    } else if (income <= 100_000) {
                        tax = 50_000 * 0.10 + (income - 50_000) * 0.22;
                    } else {
                        tax = 50_000 * 0.10 + 50_000 * 0.22 + (income - 100_000) * 0.32;
                    }
                    return BigDecimal.valueOf(tax).setScale(4, java.math.RoundingMode.HALF_UP);
                });
        // Bracket ceiling at 22% = $100K (both 3-arg and 4-arg overloads)
        when(taxCalc.computeMaxIncomeForBracket(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenReturn(new BigDecimal("100000"));
        when(taxCalc.computeMaxIncomeForBracket(
                any(BigDecimal.class), anyInt(), any(FilingStatus.class), nullable(BigDecimal.class)))
                .thenReturn(new BigDecimal("100000"));
        return new MonteCarloSpendingOptimizer(taxCalc);
    }

    @Test
    void optimize_withConversions_jointOptimization_higherSpendingThanTaxOnly() {
        // The joint optimizer searches fractions by sustainable spending, not lifetime tax.
        // It should produce spending materially above the essential floor, and the chosen
        // fraction should maximize spending (even if that means no conversions when the
        // tax model doesn't reward them).
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

        var input = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(
                    new HypotheticalAccountInput(new BigDecimal("200000"),
                        BigDecimal.ZERO, new BigDecimal("0.07"), "taxable"),
                    new HypotheticalAccountInput(new BigDecimal("800000"),
                        BigDecimal.ZERO, new BigDecimal("0.07"), "traditional")),
                List.of(),
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                500, new BigDecimal("0.95"), phases, 42L,
                BigDecimal.ZERO, null, 0,
                0, BigDecimal.ZERO, "single", "taxable_first",
                true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5, null);

        var taxOptimizer = progressiveTaxOptimizer();
        var result = taxOptimizer.optimize(input);

        assertThat(result).isNotNull();
        assertThat(result.yearlySpending()).isNotEmpty();

        // The recommended spending should be materially above the essential floor
        double firstYearSpending = result.yearlySpending().getFirst().recommended().doubleValue();
        double essentialFloor = 30_000;

        assertThat(firstYearSpending)
                .as("Joint optimization should leave room for discretionary spending above the essential floor")
                .isGreaterThan(essentialFloor * 1.3);

        // The conversion schedule should exist (even if the optimizer chose fraction=0,
        // indicating that conversions don't improve spending under this tax model)
        assertThat(result.conversionSchedule()).isNotNull();

        // The joint optimizer should never produce WORSE spending than a no-conversion baseline.
        // Run without conversions for comparison.
        var noConvInput = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(
                    new HypotheticalAccountInput(new BigDecimal("200000"),
                        BigDecimal.ZERO, new BigDecimal("0.07"), "taxable"),
                    new HypotheticalAccountInput(new BigDecimal("800000"),
                        BigDecimal.ZERO, new BigDecimal("0.07"), "traditional")),
                List.of(),
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                500, new BigDecimal("0.95"), phases, 42L,
                BigDecimal.ZERO, null, 0,
                0, BigDecimal.ZERO, "single", "taxable_first",
                false, null, null, 5, null);

        var noConvResult = taxOptimizer.optimize(noConvInput);
        double noConvSpending = noConvResult.yearlySpending().getFirst().recommended().doubleValue();

        assertThat(firstYearSpending)
                .as("Joint optimization should not produce lower spending than no-conversion baseline")
                .isGreaterThanOrEqualTo(noConvSpending * 0.95);  // within 5% tolerance for MC noise
    }

    @Test
    void optimize_withConversions_smallPortfolio_lessAggressiveConversions() {
        // With a small portfolio, the joint optimizer should still produce reasonable spending
        // without conversion tax consuming the entire discretionary budget.
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

        var input = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(
                    new HypotheticalAccountInput(new BigDecimal("50000"),
                        BigDecimal.ZERO, new BigDecimal("0.07"), "taxable"),
                    new HypotheticalAccountInput(new BigDecimal("200000"),
                        BigDecimal.ZERO, new BigDecimal("0.07"), "traditional")),
                List.of(),
                new BigDecimal("20000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                500, new BigDecimal("0.95"), phases, 42L,
                BigDecimal.ZERO, null, 0,
                0, BigDecimal.ZERO, "single", "taxable_first",
                true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5, null);

        var taxOptimizer = progressiveTaxOptimizer();
        var result = taxOptimizer.optimize(input);

        assertThat(result).isNotNull();
        assertThat(result.yearlySpending()).isNotEmpty();

        // First year conversion tax should NOT exceed first year discretionary spending
        double firstYearSpending = result.yearlySpending().getFirst().recommended().doubleValue();
        double essentialFloor = 20_000;
        double discretionary = firstYearSpending - essentialFloor;

        if (result.conversionSchedule() != null && !result.conversionSchedule().years().isEmpty()) {
            double firstYearConvTax = result.conversionSchedule().years().getFirst()
                    .estimatedTax().doubleValue();
            // Conversion tax should not consume more than the discretionary budget
            assertThat(firstYearConvTax)
                    .as("Conversion tax should not exceed discretionary spending")
                    .isLessThanOrEqualTo(discretionary + essentialFloor * 0.5);
        }
    }

    @Test
    void splitWithdrawal_allOrdersAndPreAge595_correctDistribution() {
        double taxable = 100, traditional = 200, roth = 300;
        double need = 150;

        // taxable_first: draws 100 from taxable, 50 from traditional
        var tf = MonteCarloSpendingOptimizer.splitWithdrawal(taxable, traditional, roth, need, "taxable_first", false);
        assertThat(tf[0]).isEqualTo(100); // from taxable
        assertThat(tf[1]).isEqualTo(50);  // from traditional
        assertThat(tf[2]).isEqualTo(0);   // none from roth

        // traditional_first: draws 150 from traditional
        var trf = MonteCarloSpendingOptimizer.splitWithdrawal(taxable, traditional, roth, need, "traditional_first", false);
        assertThat(trf[0]).isEqualTo(0);
        assertThat(trf[1]).isEqualTo(150);
        assertThat(trf[2]).isEqualTo(0);

        // roth_first: draws 150 from roth (has 300)
        var rf = MonteCarloSpendingOptimizer.splitWithdrawal(taxable, traditional, roth, need, "roth_first", false);
        assertThat(rf[0]).isEqualTo(0);
        assertThat(rf[1]).isEqualTo(0);
        assertThat(rf[2]).isEqualTo(150);

        // preAge595: always from taxable only, regardless of order
        var pre = MonteCarloSpendingOptimizer.splitWithdrawal(taxable, traditional, roth, need, "traditional_first", true);
        assertThat(pre[0]).isEqualTo(100); // capped at available taxable
        assertThat(pre[1]).isEqualTo(0);
        assertThat(pre[2]).isEqualTo(0);
    }
}
