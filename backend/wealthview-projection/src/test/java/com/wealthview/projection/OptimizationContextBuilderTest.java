package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A5 (2026-07-11 audit): the guardrail/MC flow hardcoded a 1.8% dividend yield instead of reading
 * the scenario's {@code dividend_yield} param, so the UI knob only affected the deterministic
 * engine. {@link OptimizationContextBuilder} must thread {@link GuardrailOptimizationInput#dividendYield()}
 * into {@link SimulationParameters#dividendYield()} — the same field {@link TrialSimulator} taxes
 * dividends against — falling back to {@code ScenarioParamsParser.DEFAULT_DIVIDEND_YIELD} (0.018)
 * when the scenario doesn't set one, exactly like the deterministic engine's
 * {@code ScenarioParamsParser.dividendYield}.
 */
class OptimizationContextBuilderTest {

    private final OptimizationContextBuilder builder = new OptimizationContextBuilder(null);

    @Test
    void build_scenarioDividendYieldSet_flowsIntoSimulationConfig() {
        var input = inputWithDividendYield(new BigDecimal("0.03"));

        var setup = builder.build(input, ProjectionTestFixtures.TEST_CMA_MATRIX);

        assertThat(setup.sim().dividendYield()).isEqualTo(0.03);
    }

    @Test
    void build_scenarioDividendYieldAbsent_defaultsToPoint018() {
        var input = inputWithDividendYield(null);

        var setup = builder.build(input, ProjectionTestFixtures.TEST_CMA_MATRIX);

        assertThat(setup.sim().dividendYield()).isEqualTo(0.018);
    }

    // B1 (2026-07-11 audit): fee_rate must thread into SimulationParameters.feeRate() the same way
    // dividend_yield does -- resolveFeeRate falls back to ScenarioParamsParser.DEFAULT_FEE_RATE
    // (0.0025) when the scenario's params_json doesn't set one.
    @Test
    void build_scenarioFeeRateSet_flowsIntoSimulationConfig() {
        var input = inputWithFeeRate(new BigDecimal("0.01"));

        var setup = builder.build(input, ProjectionTestFixtures.TEST_CMA_MATRIX);

        assertThat(setup.sim().feeRate()).isEqualTo(0.01);
    }

    @Test
    void build_scenarioFeeRateAbsent_defaultsToPoint0025() {
        var input = inputWithFeeRate(null);

        var setup = builder.build(input, ProjectionTestFixtures.TEST_CMA_MATRIX);

        assertThat(setup.sim().feeRate()).isEqualTo(0.0025);
    }

    @Test
    void build_scenarioFeeRateExplicitZero_isNotTreatedAsAbsent() {
        var input = inputWithFeeRate(BigDecimal.ZERO);

        var setup = builder.build(input, ProjectionTestFixtures.TEST_CMA_MATRIX);

        assertThat(setup.sim().feeRate()).isEqualTo(0.0);
    }

    private GuardrailOptimizationInput inputWithDividendYield(BigDecimal dividendYield) {
        return inputWith(dividendYield, null);
    }

    private GuardrailOptimizationInput inputWithFeeRate(BigDecimal feeRate) {
        return inputWith(null, feeRate);
    }

    private GuardrailOptimizationInput inputWith(BigDecimal dividendYield, BigDecimal feeRate) {
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("500000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "taxable")),
                List.of(),
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), 200, new BigDecimal("0.95"),
                List.of(), 42L,
                BigDecimal.ZERO, null, 0, 0, BigDecimal.ZERO,
                null, null,
                false, null, null, 5, null, null,
                dividendYield, feeRate);
    }
}
