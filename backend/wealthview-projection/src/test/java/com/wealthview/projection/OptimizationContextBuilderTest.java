package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

    // B2 (2026-07-11 audit) MC alignment: the MC income base previously treated Social Security as
    // 100% taxable (the opposite bias from the deterministic engine's understatement). It must now use
    // the IRS two-tier taxable SHARE, computed via the same SocialSecurityTaxCalculator with expected
    // ordinary income = non-SS taxable income + the year's expected portfolio draw (essential floor -
    // total income, floored at 0). One pass suffices because the expected draw is a fixed deterministic
    // quantity independent of SS taxability.
    @Test
    void build_socialSecurityIncome_taxableShareIsPartialNotFull() {
        // Single filer, SS $40k from age 62, essential floor $70k, zero inflation (no threshold
        // deflation). At age 67 (year index 5, non-boundary) SS is a full $40k and the expected
        // portfolio draw is $70k - $40k = $30k.
        //   provisional = 0 non-SS + 30,000 draw + 50% * 40,000 = 50,000 (> single $34k tier-2)
        //   taxable SS = 4,500 + 0.85 * (50,000 - 34,000) = 4,500 + 13,600 = 18,100
        //   (strictly between $0 and the 85% cap of $34,000).
        var ss = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "SS", IncomeSourceType.SOCIAL_SECURITY,
                new BigDecimal("40000"), 62, null, BigDecimal.ZERO, false, "partially_taxable",
                null, null, null, null, null, null);
        var input = ssHeavyInput(ss);

        var setup = builder.build(input, ProjectionTestFixtures.TEST_CMA_MATRIX);

        double taxableAt67 = setup.taxIncome().taxableIncomeByYear()[5];
        double oracle = new SocialSecurityTaxCalculator()
                .computeTaxableAmount(new BigDecimal("40000"), new BigDecimal("30000"), "single")
                .doubleValue();

        assertThat(taxableAt67).isCloseTo(oracle, within(0.01));
        assertThat(taxableAt67).isCloseTo(18100.0, within(0.01));
        // The taxable SHARE is strictly inside (0, 0.85): neither the old 100% assumption nor untaxed.
        double share = taxableAt67 / 40000.0;
        assertThat(share).isGreaterThan(0.0).isLessThan(0.85);
    }

    private GuardrailOptimizationInput ssHeavyInput(ProjectionIncomeSourceInput ss) {
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, BigDecimal.ZERO,
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("1000000"), BigDecimal.ZERO, new BigDecimal("0.05"), "taxable")),
                List.of(ss),
                new BigDecimal("70000"), BigDecimal.ZERO,
                new BigDecimal("0.05"), 100, new BigDecimal("0.90"),
                List.of(), 42L,
                BigDecimal.ZERO, null, 0, 0, BigDecimal.ZERO,
                "single", null,
                false, null, null, 5, null, null,
                null, null);
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
