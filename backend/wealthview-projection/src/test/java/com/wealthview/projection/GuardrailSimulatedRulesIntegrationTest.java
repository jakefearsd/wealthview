package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the SIMULATED guardrail spending rule reported through
 * {@code success_probability_with_rules} (audit C9). Re-running the optimizer's own seeded trials
 * with the in-simulation adaptation active never lowers -- and on a stressed fixture strictly
 * raises -- the essential-floor success rate, deterministically. With-rules spending never exceeds
 * the planned schedule and floors are never cut, so within any single year the adaptation cannot
 * cause its own floor shortfall; the {@code successProbabilityWithRules >= successProbability}
 * monotonicity across full multi-year tax paths is an EMPIRICAL pin (these fixtures plus
 * {@link GuardrailRulesTaxDynamicsMonotonicityTest}'s RMD/tax pairwise pins and a 60,000-trial-pair
 * adversarial probe with zero regressions), not a formal proof.
 *
 * <p>NOTE: these fixtures are single-taxable-account (simPools=false), so the RMD/withdrawal-tax/
 * gross-up machinery never fires here -- {@link GuardrailRulesTaxDynamicsMonotonicityTest} covers
 * exactly that multi-pool regime.
 */
class GuardrailSimulatedRulesIntegrationTest {

    private static final long SEED = 20260712L;

    /** Healthy, roomy fixture with the adjustment knob on: with-rules rescues the failing tail. */
    private GuardrailOptimizationInput healthyInput(BigDecimal maxAnnualAdjustmentRate) {
        var phases = List.of(new GuardrailPhaseInput("Retirement", 62, null, 1));
        return GuardrailOptimizationInputBuilder.builder()
                .withAccounts(List.of(new HypotheticalAccountInput(
                        new BigDecimal("1500000"), BigDecimal.ZERO, null, "taxable")))
                .withReturnMean(new BigDecimal("0.06"))
                .withTrialCount(1000)
                .withConfidenceLevel(new BigDecimal("0.90"))
                .withPhases(phases)
                .withSeed(SEED)
                .withMaxAnnualAdjustmentRate(maxAnnualAdjustmentRate)
                .build();
    }

    /**
     * Stressed fixture: a modest all-taxable portfolio the optimizer must calibrate right to the
     * 80%-confidence edge, so a meaningful fraction of paths sit on the failure margin where an
     * in-simulation discretionary cut rescues them.
     */
    private GuardrailOptimizationInput stressedInput() {
        var phases = List.of(new GuardrailPhaseInput("Retirement", 62, null, 1));
        return GuardrailOptimizationInputBuilder.builder()
                .withEndAge(92)
                .withAccounts(List.of(new HypotheticalAccountInput(
                        new BigDecimal("700000"), BigDecimal.ZERO, null, "taxable")))
                .withReturnMean(new BigDecimal("0.06"))
                .withTrialCount(2000)
                .withConfidenceLevel(new BigDecimal("0.80"))
                .withPhases(phases)
                .withSeed(SEED)
                .withMaxAnnualAdjustmentRate(new BigDecimal("0.10"))
                .build();
    }

    @Test
    void optimize_healthyFixtureRuleOn_withRulesMeetsOrExceedsNoRules() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse r = optimizer.optimize(healthyInput(new BigDecimal("0.10")));

        assertThat(r.successProbability()).isEqualByComparingTo(new BigDecimal("0.9000"));
        assertThat(r.successProbabilityWithRules()).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(r.successProbabilityWithRules()).isGreaterThanOrEqualTo(r.successProbability());
    }

    @Test
    void optimize_stressedFixtureRuleOn_withRulesStrictlyExceedsNoRules() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse r = optimizer.optimize(stressedInput());

        assertThat(r.successProbability()).isEqualByComparingTo(new BigDecimal("0.8000"));
        assertThat(r.successProbabilityWithRules()).isEqualByComparingTo(new BigDecimal("0.9785"));
        assertThat(r.successProbabilityWithRules()).isGreaterThan(r.successProbability());
    }

    @Test
    void optimize_sameSeed_reproducesBothSuccessRates() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse a = optimizer.optimize(stressedInput());
        GuardrailProfileResponse b = optimizer.optimize(stressedInput());

        assertThat(a.successProbability()).isEqualByComparingTo(b.successProbability());
        assertThat(a.successProbabilityWithRules()).isEqualByComparingTo(b.successProbabilityWithRules());
    }

    @Test
    void optimize_noAdjustmentRate_withRulesFieldIsNullAndNoRulesUnaffected() {
        // Additive-field invariance: with no positive max_annual_adjustment_rate the guardrail rule
        // cannot move spending, so the extra pass is skipped and the field is null -- while the
        // no-adaptation success probability is still reported exactly as before.
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse r = optimizer.optimize(healthyInput(null));

        assertThat(r.successProbabilityWithRules()).isNull();
        assertThat(r.successProbability()).isNotNull();
    }
}
