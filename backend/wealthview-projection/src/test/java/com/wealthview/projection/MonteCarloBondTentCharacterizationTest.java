package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentMix;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Golden-master characterization test for the Monte Carlo optimizer with a bond tent. The fixture is a
 * 2027 base year, 2030 retirement, $600k taxable plus $400k traditional (both all US stock), a $40k
 * essential floor, and a five-year Balanced tent built up over three years, with no tax calculator (as
 * {@link MonteCarloSpendingOptimizerCharacterizationTest}). The seed pins the whole simulation.
 */
class MonteCarloBondTentCharacterizationTest {

    private static final long SEED = 20261024L;

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static BondTentConfig balancedTent() {
        var shares = new BondMaturityValues(bd("0.2"), bd("0.3"), bd("0.5"), bd("0"));
        var yields = new BondMaturityValues(bd("0.0394"), bd("0.0465"), bd("0.048"), bd("0.0539"));
        return new BondTentConfig(bd("5"), 3, List.of(new BondTentMix("Balanced", shares)), yields);
    }

    private static GuardrailOptimizationInput tentInput() {
        List<ProjectionAccountInput> accounts = List.of(
                new HypotheticalAccountInput(bd("600000"), BigDecimal.ZERO, AssetAllocation.ALL_US,
                        Optional.empty(), "taxable"),
                new HypotheticalAccountInput(bd("400000"), BigDecimal.ZERO, AssetAllocation.ALL_US,
                        Optional.empty(), "traditional"));
        return GuardrailOptimizationInputBuilder.builder()
                .withRetirementDate(LocalDate.of(2030, 1, 1))
                .withBaseYear(2027)
                .withAccounts(accounts)
                .withEssentialFloor(bd("40000"))
                .withTrialCount(1000)
                .withConfidenceLevel(bd("0.90"))
                .withSeed(SEED)
                .withBondTent(balancedTent())
                .build();
    }

    private static GuardrailProfileResponse run() {
        return new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX,
                ProjectionTestFixtures.TEST_MATURITY_MATRIX).optimize(tentInput());
    }

    @Test
    void optimize_bondTentFixture_matchesPinnedValues() {
        GuardrailProfileResponse r = run();

        // Recorded once on 2026-10-24 by running optimize on this fixture and reading the response. Each value
        // was then checked: the target against the independent present-value sum in the next test.
        assertThat(r.bondTentTarget()).isEqualByComparingTo(bd("331916.89"));
        assertThat(r.successProbability()).isEqualByComparingTo(bd("0.9000"));
        assertThat(r.medianFinalBalance()).isEqualByComparingTo(bd("1625235.9036"));
        assertThat(r.yearlySpending().getFirst().recommended()).isEqualByComparingTo(bd("69475.2211"));
    }

    @Test
    void optimize_bondTentFixture_targetIsThePresentValueOfTheFirstFiveRecommendedYears() {
        GuardrailProfileResponse r = run();

        // Hand check of the pinned target: with no tax calculator and no income, each covered withdrawal is the
        // year's recommended spending, discounted at the Balanced mix's real yield to the start of retirement.
        var tent = balancedTent();
        double realYield = BondTentSizer.mixRealYield(tent.yields(), tent.planMix().shares(), 0.03);
        double expected = 0;
        for (int k = 0; k < 5; k++) {
            expected += r.yearlySpending().get(k).recommended().doubleValue() / Math.pow(1 + realYield, k + 1);
        }
        assertThat(r.bondTentTarget().doubleValue()).isEqualTo(expected, within(1.00));
    }

    @Test
    void optimize_bondTentFixture_targetMatchesTrialTentSizerOnTheRecommendedSchedule() {
        GuardrailProfileResponse r = run();
        var input = tentInput();
        var setup = new OptimizationContextBuilder(null).build(input, input.accounts(),
                ProjectionTestFixtures.TEST_CMA_MATRIX, ProjectionTestFixtures.TEST_MATURITY_MATRIX);
        var plan = setup.sim().tentPlan();
        int years = setup.sim().years();
        double[] recommended = new double[years];
        for (int k = 0; k < years; k++) {
            recommended[k] = r.yearlySpending().get(k).recommended().doubleValue();
        }
        // No tax tables, conversions or income: the sizer's expected-path walk draws the whole withdrawal from
        // the 600k taxable pool (taxable first), so each covered withdrawal is that year's recommended spending.
        var sizerInputs = new TrialTentSizer.Inputs(null, null, null, null, null, 600_000, 400_000, 0,
                WithdrawalOrder.TAXABLE_FIRST, 0, 0);
        double[] zeros = new double[years];

        double sized = TrialTentSizer.target(plan, sizerInputs, recommended, zeros, zeros, zeros);

        // The response target is the pinned 331,916.89 above; the sizer reaches it by its own path (the plan's
        // sizing yield), not the test's closed-form present value.
        assertThat(sized).isEqualTo(r.bondTentTarget().doubleValue(), within(0.01));
        assertThat(sized).isEqualTo(331_916.89, within(0.01));
    }

    @Test
    void optimize_bondTentFixture_sameSeedIsDeterministic() {
        GuardrailProfileResponse first = run();
        GuardrailProfileResponse second = run();

        assertThat(second.bondTentTarget()).isEqualByComparingTo(first.bondTentTarget());
        assertThat(second.medianFinalBalance()).isEqualByComparingTo(first.medianFinalBalance());
        assertThat(second.yearlySpending()).isEqualTo(first.yearlySpending());
    }
}
