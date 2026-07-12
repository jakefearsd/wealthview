package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;
import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2025Brackets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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

    // Audit D (2026-07-11 audit, Tier-3): the age-65+ additional standard deduction must reach the
    // MC's exact-tax precompute (audit C5, OrdinaryTaxTable), not just the deterministic engine --
    // OptimizationContextBuilder threads GuardrailOptimizationInput#birthYear() into
    // OrdinaryTaxTable#computeAll/LtcgTaxTable#computeAll (both already unit-tested directly; this
    // proves the wiring end-to-end through build()).
    @Test
    void build_birthYearMakesRetireeAge65Plus_ordinaryTaxTableReflectsBoostedDeduction() {
        var builderWithTax = new OptimizationContextBuilder(federalTaxCalcWithAge65Addition());

        var under65 = inputWithBirthYear(1968); // age 62 at retirement (2030)
        var over65 = inputWithBirthYear(1959);  // age 71 at retirement (2030)

        var setupUnder65 = builderWithTax.build(under65, ProjectionTestFixtures.TEST_CMA_MATRIX);
        var setupOver65 = builderWithTax.build(over65, ProjectionTestFixtures.TEST_CMA_MATRIX);

        // No income sources -> base taxable income is 0 in year 0 for both; only the deduction used
        // to tax a $50,000 draw differs. incrementalTax(0, 50_000) = taxAt(50_000) - taxAt(0) =
        // taxAt(50_000) here, exactly reproducing computeTax(50_000, ...) since taxAt(0) = 0.
        // Under 65: deduction 15,750 -> taxable 34,250 -> 1,192.50 + 22,325*0.12 = 3,871.50
        // Age 71: deduction 17,750 -> taxable 32,250 -> 1,192.50 + 20,325*0.12 = 3,631.50
        double taxUnder65 = setupUnder65.taxIncome().ordinaryTaxTableByYear()[0].incrementalTax(0, 50_000);
        double taxOver65 = setupOver65.taxIncome().ordinaryTaxTableByYear()[0].incrementalTax(0, 50_000);
        assertThat(taxUnder65).isEqualTo(3871.50, within(1e-6));
        assertThat(taxOver65).isEqualTo(3631.50, within(1e-6));
        assertThat(taxOver65).isLessThan(taxUnder65);
    }

    /** Single-filer 2025 fixtures with a deduction carrying a nonzero age-65 addition. */
    private static FederalTaxCalculator federalTaxCalcWithAge65Addition() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        lenient().when(taxBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    private GuardrailOptimizationInput inputWithBirthYear(int birthYear) {
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), birthYear, 90, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("500000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "taxable")),
                List.of(),
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), 200, new BigDecimal("0.95"),
                List.of(), 42L,
                BigDecimal.ZERO, null, 0, 0, BigDecimal.ZERO,
                "single", null,
                false, null, null, 5, null, null,
                null, null);
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

    // T7-M3 (audit C7 follow-up): the pre-fix code deflated the SS threshold with the bare
    // retirement-anchored year index y; fixed to (retirementYear - baseYear) + y, matching the
    // deterministic engine's IncomeSourceProcessor (Math.max(0, taxYear - baseYear)).
    @Test
    void build_socialSecurityIncome_thresholdDeflatorAnchorsOnBaseYearNotRetirementYear() {
        // Retirement starts 10 calendar years after the base year (2020 -> 2030). At year index 5
        // (calendar year 2035, age 67, non-boundary) the correct deflator exponent is
        // (2030 - 2020) + 5 = 15 -- NOT the bare y = 5 the pre-fix code used. The SS source's OWN
        // COLA rate is set to match scenario inflation (3%) so its BENEFIT amount stays invariant
        // at exactly $40,000 (see the realAmount/realGrossForYear invariance pin elsewhere) --
        // isolating this test to ONLY the threshold-deflator anchor, not the benefit-amount clock.
        var ss = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "SS", IncomeSourceType.SOCIAL_SECURITY,
                new BigDecimal("40000"), 62, null, new BigDecimal("0.03"), false, "partially_taxable",
                null, null, null, null, null, null);
        var input = ssHeavyInputWithBaseYear(ss, new BigDecimal("0.03"), 2020);

        var setup = builder.build(input, ProjectionTestFixtures.TEST_CMA_MATRIX);

        double taxableAt5 = setup.taxIncome().taxableIncomeByYear()[5];

        // Independent oracle: the SAME worksheet (Pub. 915 two-tier formula), evaluated with the
        // base-year-anchored exponent for calendar year 2035 -- provisional = 30,000 draw + 50% *
        // 40,000 SS = 50,000 (mirrors build_socialSecurityIncome_taxableShareIsPartialNotFull's
        // provisional derivation, just at a different calendar year / deflator exponent).
        double oracle = new SocialSecurityTaxCalculator()
                .computeTaxableAmount(new BigDecimal("40000"), new BigDecimal("30000"), "single",
                        15, new BigDecimal("0.03"))
                .doubleValue();
        // The WRONG (pre-fix) retirement-anchored exponent would have used y = 5 instead of 15,
        // deflating the thresholds far less and understating taxable SS -- confirm the two
        // differ materially so this test cannot pass on the old anchor by coincidence.
        double wrongAnchorOracle = new SocialSecurityTaxCalculator()
                .computeTaxableAmount(new BigDecimal("40000"), new BigDecimal("30000"), "single",
                        5, new BigDecimal("0.03"))
                .doubleValue();

        assertThat(taxableAt5).isCloseTo(oracle, within(0.01));
        assertThat(oracle).isNotCloseTo(wrongAnchorOracle, within(1.0));
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

    // T7-M3 (audit C7 follow-up): the SS threshold deflator must anchor on the SAME calendar clock
    // (taxYear - baseYear) as the deterministic engine, not the MC's bare retirement-anchored year
    // index y -- see OptimizationContextBuilder#applySocialSecurityTaxableShare.
    private GuardrailOptimizationInput ssHeavyInputWithBaseYear(ProjectionIncomeSourceInput ss,
                                                                BigDecimal inflationRate, int baseYear) {
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, inflationRate,
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("1000000"), BigDecimal.ZERO, new BigDecimal("0.05"), "taxable")),
                List.of(ss),
                new BigDecimal("70000"), BigDecimal.ZERO,
                new BigDecimal("0.05"), 100, new BigDecimal("0.90"),
                List.of(), 42L,
                BigDecimal.ZERO, null, 0, 0, BigDecimal.ZERO,
                "single", null,
                false, null, null, 5, null, null,
                null, null, baseYear, false);
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
