package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTax;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.socialSecuritySource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit B2 (2026-07-11): Social Security taxation must respond to ACTUAL portfolio income —
 * traditional withdrawals, RMDs, Roth conversions, and realized gains all raise provisional
 * income and drag Social Security into taxation. The deterministic engine converges the taxable
 * Social Security amount on the realized ordinary income via a two-pass fixed-point loop.
 *
 * <p>Every expected value is hand-computed from the IRS Social Security worksheet (Pub. 915 two-tier
 * formula) and the 2025 single/MFJ federal brackets, cross-checked against independent
 * {@link SocialSecurityTaxCalculator} / {@link FederalTaxCalculator} oracles. Scenarios use
 * a taxable-pool balance of $0 so there is no LTCG/dividend income — the only portfolio ordinary
 * income is the traditional draw and any Roth conversion, which keeps the worksheet arithmetic exact.
 */
class DeterministicProjectionEngineSocialSecurityConvergenceTest
        extends DeterministicProjectionEngineTestSupport {

    private final SocialSecurityTaxCalculator ssOracle = new SocialSecurityTaxCalculator();

    // birth_year 1957 -> RMD start age 73; reference year 2025 -> age 68 (below RMD, so no RMD noise).
    private static final String SINGLE_PARAMS = """
            {"birth_year": 1957, "filing_status": "single", "withdrawal_order": "traditional_first",
             "fee_rate": 0, "dividend_yield": 0}
            """;
    private static final String MFJ_PARAMS = """
            {"birth_year": 1957, "filing_status": "married_filing_jointly",
             "withdrawal_order": "traditional_first", "fee_rate": 0, "dividend_yield": 0}
            """;

    // === Test 1a: SS + traditional draw — converged taxable SS matches the IRS worksheet ===

    @Test
    void run_ssPlusTraditionalDraw_convergesTaxableSocialSecurityToWorksheetValue() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var taxEngine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Single filer, SS benefit $40k, spending $90k -> portfolio need $50k, all from traditional.
        // IRS worksheet (single): provisional = $50,000 draw + 50% * $40,000 SS = $70,000.
        //   tier-1 amount = min(50% benefits $20,000, 50% * ($34k-$25k) $4,500) = $4,500
        //   tier-2 amount = 85% * ($70,000 - $34,000) = 85% * $36,000 = $30,600
        //   total $35,100, capped at 85% * $40,000 = $34,000 -> taxable SS = $34,000.
        // Ordinary taxable income = $50,000 draw + $34,000 SS = $84,000; std deduction $15,000 -> $69,000.
        //   tax = 10%*11,925 + 12%*(48,475-11,925) + 22%*(69,000-48,475) = 1,192.50 + 4,386 + 4,515.50 = 10,094.00
        var input = createInput(
                LocalDate.of(2024, 1, 1), 80, BigDecimal.ZERO, SINGLE_PARAMS,
                List.of(acct("1000000", "0", "0", "traditional"), acct("100000", "0", "0", "roth")),
                new SpendingProfileInput(bd("60000"), bd("30000"), null),
                2025, List.of(socialSecuritySource("40000", 62)));

        var year1 = taxEngine.run(input).yearlyData().getFirst();

        assertThat(year1.socialSecurityTaxable()).isEqualByComparingTo(bd("34000"));
        assertThat(year1.socialSecurityTaxable()).isEqualByComparingTo(
                ssOracle.computeTaxableAmount(bd("40000"), bd("50000"), "single"));
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("10094.00"));
    }

    // === Test 1b: tax torpedo — marginal cost of an extra $1k draw exceeds the bracket rate ===

    @Test
    void run_ssPlusTraditionalDraw_extraDrawTriggersTaxTorpedo() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var taxEngine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Single filer, SS $30k, taxable pool $0. Draw $30k (spending $60k) then $31k (spending $61k).
        // $30k draw: provisional = 30,000 + 15,000 = 45,000 -> taxable SS
        //   = 4,500 + 85%*(45,000-34,000) = 4,500 + 9,350 = 13,850 (below 85% cap 25,500).
        //   ordinary = 30,000 + 13,850 = 43,850; deduct 15,000 -> 28,850; tax = 1,192.50 + 12%*16,925 = 3,223.50.
        // $31k draw: provisional = 31,000 + 15,000 = 46,000 -> taxable SS
        //   = 4,500 + 85%*(46,000-34,000) = 4,500 + 10,200 = 14,700.
        //   ordinary = 31,000 + 14,700 = 45,700; deduct 15,000 -> 30,700; tax = 1,192.50 + 12%*18,775 = 3,445.50.
        // Marginal tax on the extra $1,000 draw = 222.00 = 22.2% >> the 12% bracket rate ($120): the torpedo.
        var base = createInput(
                LocalDate.of(2024, 1, 1), 80, BigDecimal.ZERO, SINGLE_PARAMS,
                List.of(acct("1000000", "0", "0", "traditional"), acct("100000", "0", "0", "roth")),
                new SpendingProfileInput(bd("40000"), bd("20000"), null),
                2025, List.of(socialSecuritySource("30000", 62)));
        var bigger = createInput(
                LocalDate.of(2024, 1, 1), 80, BigDecimal.ZERO, SINGLE_PARAMS,
                List.of(acct("1000000", "0", "0", "traditional"), acct("100000", "0", "0", "roth")),
                new SpendingProfileInput(bd("40000"), bd("21000"), null),
                2025, List.of(socialSecuritySource("30000", 62)));

        var y30 = taxEngine.run(base).yearlyData().getFirst();
        var y31 = taxEngine.run(bigger).yearlyData().getFirst();

        assertThat(y30.socialSecurityTaxable()).isEqualByComparingTo(bd("13850"));
        assertThat(y30.taxLiability()).isEqualByComparingTo(bd("3223.50"));
        assertThat(y31.taxLiability()).isEqualByComparingTo(bd("3445.50"));

        BigDecimal marginalTax = y31.taxLiability().subtract(y30.taxLiability());
        BigDecimal bracketRateOnly = bd("1000").multiply(bd("0.12")); // $120
        assertThat(marginalTax).isEqualByComparingTo(bd("222.00"));
        assertThat(marginalTax).isGreaterThan(bracketRateOnly);
    }

    // === Test 2: MFJ two-SS-source household — provisional aggregates spousal benefits ===

    @Test
    void run_mfjTwoSocialSecuritySources_usesCombinedProvisionalNotPerSource() {
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var taxEngine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // MFJ, two SS sources of $20k each ($40k combined), spending $80k -> $40k traditional draw.
        // COMBINED worksheet (MFJ): provisional = 40,000 draw + 50% * 40,000 = 60,000.
        //   tier-1 = min(50%*40,000 = 20,000, 50%*(44,000-32,000) = 6,000) = 6,000
        //   tier-2 = 85% * (60,000 - 44,000) = 13,600 ; total = 19,600 (below cap 34,000).
        // A buggy PER-SOURCE calc would evaluate each $20k source with only its own half-benefit in
        // provisional (40,000 + 10,000 = 50,000 each) yielding 11,100 per source = 22,200 combined,
        // which is NOT what the engine must produce.
        var input = createInput(
                LocalDate.of(2024, 1, 1), 80, BigDecimal.ZERO, MFJ_PARAMS,
                List.of(acct("1000000", "0", "0", "traditional"), acct("100000", "0", "0", "roth")),
                new SpendingProfileInput(bd("50000"), bd("30000"), null),
                2025, List.of(socialSecuritySource("20000", 62), socialSecuritySource("20000", 62)));

        var year1 = taxEngine.run(input).yearlyData().getFirst();

        assertThat(year1.socialSecurityTaxable()).isEqualByComparingTo(bd("19600"));
        assertThat(year1.socialSecurityTaxable()).isEqualByComparingTo(
                ssOracle.computeTaxableAmount(bd("40000"), bd("40000"), "married_filing_jointly"));
        assertThat(year1.socialSecurityTaxable()).isNotEqualByComparingTo(bd("22200"));
    }

    // === Test 3: Roth conversion drags Social Security into taxation ===

    @Test
    void run_rothConversionYear_dragsSocialSecurityIntoTaxationVsNoConversionBaseline() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var taxEngine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Single, SS $30k fully covers $30k spending (portfolio need $0). Baseline: no conversion.
        //   provisional = 0 draw + 15,000 = 15,000 < 25,000 -> taxable SS = 0.
        // With a $40k Roth conversion: provisional = 40,000 conversion + 15,000 = 55,000 -> taxable SS
        //   = 4,500 + 85%*(55,000-34,000) = 4,500 + 17,850 = 22,350 (below cap 25,500).
        String baseParams = """
                {"birth_year": 1957, "filing_status": "single", "fee_rate": 0, "dividend_yield": 0}
                """;
        String convParams = """
                {"birth_year": 1957, "filing_status": "single", "annual_roth_conversion": 40000,
                 "fee_rate": 0, "dividend_yield": 0}
                """;
        var spending = new SpendingProfileInput(bd("20000"), bd("10000"), null);
        var withoutConv = createInput(LocalDate.of(2024, 1, 1), 80, BigDecimal.ZERO, baseParams,
                List.of(acct("1000000", "0", "0", "traditional"), acct("50000", "0", "0", "roth")),
                spending, 2025, List.of(socialSecuritySource("30000", 62)));
        var withConv = createInput(LocalDate.of(2024, 1, 1), 80, BigDecimal.ZERO, convParams,
                List.of(acct("1000000", "0", "0", "traditional"), acct("50000", "0", "0", "roth")),
                spending, 2025, List.of(socialSecuritySource("30000", 62)));

        var baseline = taxEngine.run(withoutConv).yearlyData().getFirst();
        var converted = taxEngine.run(withConv).yearlyData().getFirst();

        assertThat(baseline.socialSecurityTaxable()).isNull(); // zero taxable SS -> mapped to null
        assertThat(converted.rothConversionAmount()).isEqualByComparingTo(bd("40000"));
        assertThat(converted.socialSecurityTaxable()).isEqualByComparingTo(bd("22350"));
        assertThat(converted.socialSecurityTaxable()).isEqualByComparingTo(
                ssOracle.computeTaxableAmount(bd("30000"), bd("40000"), "single"));
    }

    // === Test 4: zero portfolio-need year with a forced RMD — the RMD enters provisional income ===

    @Test
    void run_zeroNeedYearWithForcedRmd_rmdEntersProvisionalIncome() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var taxEngine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Single, age 75 (birth 1950 -> RMD age 73). SS $40k + pension $20k = $60k income fully covers
        // $60k spending, so the spending draw is $0. Traditional $492,000 forces an RMD of
        //   492,000 / 24.6 (ULT age 75) = $20,000.
        // provisional = pension 20,000 (non-SS) + RMD 20,000 + 50% * 40,000 = 60,000 ->
        //   taxable SS = 4,500 + 85%*(60,000-34,000) = 4,500 + 22,100 = 26,600 (below cap 34,000).
        // Without the RMD in provisional it would be only 4,500 + 85%*(40,000-34,000) = 9,600 -> the
        // $20,000 RMD adds exactly 0.85*20,000 = $17,000 of taxable SS.
        String params = """
                {"birth_year": 1950, "filing_status": "single", "withdrawal_order": "traditional_first",
                 "fee_rate": 0, "dividend_yield": 0}
                """;
        var input = createInput(
                LocalDate.of(2024, 1, 1), 90, BigDecimal.ZERO, params,
                List.of(acct("492000", "0", "0", "traditional"), acct("100000", "0", "0", "roth")),
                new SpendingProfileInput(bd("40000"), bd("20000"), null),
                2025, List.of(
                        socialSecuritySource("40000", 62),
                        pensionSource("20000", 62)));

        var year1 = taxEngine.run(input).yearlyData().getFirst();

        assertThat(year1.rmdAmount()).isEqualByComparingTo(bd("20000"));
        assertThat(year1.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year1.socialSecurityTaxable()).isEqualByComparingTo(bd("26600"));
        assertThat(year1.socialSecurityTaxable()).isEqualByComparingTo(
                ssOracle.computeTaxableAmount(bd("40000"), bd("40000"), "single")); // pension 20k + RMD 20k
    }

    // === Test 5: convergence to a self-consistent fixed point near the tier-1 threshold ===

    @Test
    void run_nearTier1Threshold_convergesToSelfConsistentFixedPoint() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var taxEngine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Single, SS $20k, spending $36k -> $16k traditional draw. provisional = 16,000 + 10,000 = 26,000,
        // just over the $25,000 tier-1 threshold -> taxable SS = min(50%*20,000, 50%*(26,000-25,000)) = 500.
        var input = createInput(
                LocalDate.of(2024, 1, 1), 80, BigDecimal.ZERO, SINGLE_PARAMS,
                List.of(acct("1000000", "0", "0", "traditional"), acct("100000", "0", "0", "roth")),
                new SpendingProfileInput(bd("26000"), bd("10000"), null),
                2025, List.of(socialSecuritySource("20000", 62)));

        var year1 = taxEngine.run(input).yearlyData().getFirst();

        // The converged value must be the small tier-1 amount, not the iteration-0 value of $0.
        assertThat(year1.socialSecurityTaxable()).isEqualByComparingTo(bd("500"));

        // Fixed-point / stability check: reconstruct provisional income from the realized ordinary
        // components the DTO exposes (traditional draw + conversion; taxable pool is $0 so no LTCG),
        // feed it back through the independent oracle, and confirm it reproduces the same taxable SS.
        BigDecimal realizedOrdinary = year1.withdrawalFromTraditional()
                .add(year1.rothConversionAmount() == null ? BigDecimal.ZERO : year1.rothConversionAmount());
        BigDecimal oracleFixedPoint = ssOracle.computeTaxableAmount(
                bd("20000"), realizedOrdinary, FilingStatus.SINGLE.value());
        assertThat(year1.socialSecurityTaxable())
                .isCloseTo(oracleFixedPoint, org.assertj.core.data.Offset.offset(bd("1")));
        assertThat(year1.withdrawalFromTraditional()).isEqualByComparingTo(bd("16000"));
    }

    private static com.wealthview.core.projection.dto.ProjectionIncomeSourceInput pensionSource(
            String amount, int startAge) {
        return new com.wealthview.core.projection.dto.ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Pension",
                com.wealthview.core.projection.dto.IncomeSourceType.PENSION,
                bd(amount), startAge, null, BigDecimal.ZERO, false, "taxable",
                null, null, null, null, null, null);
    }
}
