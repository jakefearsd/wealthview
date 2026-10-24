package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.BondMaturityReturnsProvider;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentMix;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonteCarloSpendingOptimizerTentTest {

    private static final double INFLATION = 0.03;

    private static BondMaturityValues values(String ultrashort, String shortTerm, String intermediate,
                                             String longTerm) {
        return new BondMaturityValues(new BigDecimal(ultrashort), new BigDecimal(shortTerm),
                new BigDecimal(intermediate), new BigDecimal(longTerm));
    }

    private static BondTentConfig balancedTent(int coverage, int buildUp) {
        return new BondTentConfig(new BigDecimal(coverage), buildUp,
                List.of(new BondTentMix("Balanced", values("0.2", "0.3", "0.5", "0"))),
                values("0.0394", "0.0465", "0.048", "0.0539"));
    }

    private static GuardrailOptimizationInputBuilder baseInput() {
        return GuardrailOptimizationInputBuilder.builder()
                .withInflationRate(new BigDecimal("0.03"))
                .withAccounts(List.of(new HypotheticalAccountInput(
                        new BigDecimal("800000"), BigDecimal.ZERO, null, "taxable")))
                .withTrialCount(300)
                .withConfidenceLevel(new BigDecimal("0.90"));
    }

    private static MonteCarloSpendingOptimizer optimizer() {
        return new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX,
                ProjectionTestFixtures.TEST_MATURITY_MATRIX);
    }

    @Test
    void optimize_noTent_hasNoTentTarget() {
        var response = optimizer().optimize(baseInput().build());

        assertThat(response.bondTentTarget()).isNull();
    }

    @Test
    void optimize_withTent_certifiesTheTargetOfTheRecommendedSchedule() {
        var input = baseInput().withBondTent(balancedTent(5, 0)).build();

        var response = optimizer().optimize(input);

        double r = BondTentSizer.mixRealYield(balancedTent(5, 0).yields(),
                balancedTent(5, 0).planMix().shares(), INFLATION);
        double expected = 0;
        for (int k = 0; k < 5; k++) {
            GuardrailYearlySpending year = response.yearlySpending().get(k);
            expected += year.recommended().doubleValue() / Math.pow(1 + r, k + 1);
        }
        assertThat(response.bondTentTarget()).isNotNull();
        assertThat(response.bondTentTarget().doubleValue()).isEqualTo(expected, within(1.00));
        assertThat(response.bondTentTarget().scale()).isEqualTo(2);
    }

    @Test
    void optimize_withTentAndPreRetirementYears_changesTheRecommendation() {
        // The build-up can only add what the accounts contribute, so the accounts contribute 40k a year.
        List<ProjectionAccountInput> contributing = List.of(new HypotheticalAccountInput(
                new BigDecimal("800000"), new BigDecimal("40000"), null, "taxable"));
        var withTent = optimizer().optimize(baseInput().withAccounts(contributing).withBaseYear(2027)
                .withBondTent(balancedTent(5, 3)).build());
        var withoutTent = optimizer().optimize(baseInput().withAccounts(contributing).withBaseYear(2027).build());

        assertThat(withTent.yearlySpending().getFirst().recommended())
                .isNotEqualByComparingTo(withoutTent.yearlySpending().getFirst().recommended());
    }

    @Test
    void optimize_tentAndDepressionYearsFlag_usesTheDefaultWindow() {
        var capm = mock(CapitalMarketAssumptionsProvider.class);
        when(capm.matrix(anyBoolean())).thenReturn(ProjectionTestFixtures.TEST_CMA_MATRIX);
        var maturities = mock(BondMaturityReturnsProvider.class);
        when(maturities.alignedTo(any())).thenReturn(ProjectionTestFixtures.TEST_MATURITY_MATRIX);
        var optimizer = new MonteCarloSpendingOptimizer(null, capm, null, null, maturities);
        var input = baseInput().withBondTent(balancedTent(5, 0)).build();
        var withFlag = GuardrailOptimizationInput.builder()
                .retirementDate(input.retirementDate()).birthYear(input.birthYear()).endAge(input.endAge())
                .inflationRate(input.inflationRate()).accounts(input.accounts())
                .incomeSources(input.incomeSources()).essentialFloor(input.essentialFloor())
                .terminalBalanceTarget(input.terminalBalanceTarget()).returnMean(input.returnMean())
                .trialCount(input.trialCount()).confidenceLevel(input.confidenceLevel()).phases(input.phases())
                .seed(input.seed()).portfolioFloor(input.portfolioFloor())
                .traditionalExhaustionBuffer(input.traditionalExhaustionBuffer())
                .includeDepressionYears(true).bondTent(input.bondTent()).build();

        optimizer.optimize(withFlag);

        verify(capm).matrix(false);
        verify(capm, never()).matrix(true);
    }

    @Test
    void optimize_tentWithoutMaturityProvider_throws() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);
        var input = baseInput().withBondTent(balancedTent(5, 0)).build();

        assertThatThrownBy(() -> optimizer.optimize(input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A bond tent is configured but bond return assumptions are not wired");
    }

    @Test
    void optimize_noTent_neverAlignsMaturities() {
        var capm = mock(CapitalMarketAssumptionsProvider.class);
        when(capm.matrix(anyBoolean())).thenReturn(ProjectionTestFixtures.TEST_CMA_MATRIX);
        var maturities = mock(BondMaturityReturnsProvider.class);
        var optimizer = new MonteCarloSpendingOptimizer(null, capm, null, null, maturities);

        optimizer.optimize(baseInput().build());

        verify(maturities, never()).alignedTo(any());
    }
}
