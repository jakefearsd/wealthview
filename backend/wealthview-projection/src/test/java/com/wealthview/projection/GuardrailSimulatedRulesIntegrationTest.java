package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the SIMULATED guardrail spending rule reported through
 * {@code success_probability_with_rules} (audit C9). Re-running the optimizer's own seeded trials
 * with the in-simulation adaptation active never lowers -- and on a stressed fixture strictly
 * raises -- the essential-floor success rate, deterministically. Because with-rules spending is
 * never above the planned schedule, the adaptation can only preserve portfolio and help floor
 * funding, so {@code successProbabilityWithRules >= successProbability} holds by construction; these
 * tests pin both rates on two fixtures and confirm reproducibility.
 */
class GuardrailSimulatedRulesIntegrationTest {

    private static final long SEED = 20260712L;

    /** Healthy, roomy fixture with the adjustment knob on: with-rules rescues the failing tail. */
    private GuardrailOptimizationInput healthyInput(BigDecimal maxAnnualAdjustmentRate) {
        var phases = List.of(new GuardrailPhaseInput("Retirement", 62, null, 1));
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("1500000"), BigDecimal.ZERO, null, "taxable")),
                List.of(), new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.06"), 1000, new BigDecimal("0.90"),
                phases, SEED, BigDecimal.ZERO, maxAnnualAdjustmentRate, 0, 0, BigDecimal.ZERO,
                null, null, false, null, null, 5, null, null,
                null, null);
    }

    /**
     * Stressed fixture: a modest all-taxable portfolio the optimizer must calibrate right to the
     * 80%-confidence edge, so a meaningful fraction of paths sit on the failure margin where an
     * in-simulation discretionary cut rescues them.
     */
    private GuardrailOptimizationInput stressedInput() {
        var phases = List.of(new GuardrailPhaseInput("Retirement", 62, null, 1));
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 92, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("700000"), BigDecimal.ZERO, null, "taxable")),
                List.of(), new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.06"), 2000, new BigDecimal("0.80"),
                phases, SEED, BigDecimal.ZERO, new BigDecimal("0.10"), 0, 0, BigDecimal.ZERO,
                null, null, false, null, null, 5, null, null,
                null, null);
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
