package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.NullStateTaxCalculator;
import com.wealthview.core.projection.tax.StateTaxCalculator;
import com.wealthview.core.projection.tax.StateTaxCalculatorFactory;
import com.wealthview.persistence.entity.StandardDeductionEntity;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createRetiredInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTax;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.incomeSource;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.property;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.selfEmploymentSource;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.socialSecuritySource;
import static com.wealthview.projection.testutil.TierJsonBuilder.tiers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeterministicProjectionEngineTaxTest extends DeterministicProjectionEngineTestSupport {

    @Test
    void run_allRothPortfolio_noTaxOnWithdrawals() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("500000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        for (var yearData : result.yearlyData()) {
            if (yearData.taxLiability() != null) {
                assertThat(yearData.taxLiability()).isEqualByComparingTo(BigDecimal.ZERO);
            }
        }
    }

    // Audit D (2026-07-11 audit, Tier-3): the age-65+ additional standard deduction must reach the
    // deterministic engine's actual computed federal tax, not just FederalTaxCalculator in isolation.
    @Test
    void run_age66Retiree_federalTaxUsesAge65BoostedStandardDeduction() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        // Override the shared 2025 fixture's deduction with the real OBBBA base + age-65 addition:
        // the shared TaxBracketFixtures.singleDeduction2025() intentionally stays frozen at its
        // original pre-OBBBA numbers (it backs ~20 unrelated pinned-number tests across the suite),
        // so this test wires its own deduction entity with the real 2025 figures to prove the
        // age-65 seam end-to-end without perturbing that shared fixture.
        when(standardDeductionRepository.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int referenceYear = 2025;
        int birthYear = 1959; // age 66 at referenceYear -- below the RMD start age (73), isolating
                               // pure pension income tax from any RMD interaction
        LocalDate retirementDate = LocalDate.of(2020, 1, 1);

        // Pension $60,000 exactly equals spending $60,000 (essential $40,000 + discretionary
        // $20,000): portfolioNeed = 0, so withdrawals are 0 and the pension is the ONLY taxable
        // income this year (mirrors run_incomeExactlyEqualsSpending_taxStillComputed in
        // DeterministicProjectionEngineSpendingPlanTest).
        var input = createInput(
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single"}
                """.formatted(birthYear),
                List.of(
                        acct("300000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "roth")),
                new SpendingProfileInput(bd("40000"), bd("20000"), "[]"),
                referenceYear, List.of(incomeSource("Pension", "60000", 60, null, "0")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        assertThat(year1.age()).isEqualTo(66);
        assertThat(year1.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO);

        // Independent oracle: the same production class, called directly via the new age-aware
        // overload -- not a hand-derived approximation.
        var refCalc = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
        BigDecimal expectedTax = refCalc.computeTax(bd("60000"), referenceYear, FilingStatus.SINGLE, 66);
        // Taxable = 60,000 - (15,750 + 2,000) = 42,250 -> 1,192.50 + 30,325*0.12 = 4,831.50. This is
        // the pre-C2 base tax; portfolioNeed is $0 (pension exactly covers spending) and there is no
        // taxable account in this fixture, so the whole $4,831.50 is unfunded-from-surplus and must
        // drain from traditional -- audit C2 grosses it up. 5-pass fixed point (base=60,000,
        // taxableAvail=0): 4,831.50 -> 5,411.28 -> 5,480.8536 -> 5,489.202432 -> 5,490.204290 (implied
        // 5,490.324515 on the would-be 6th pass is within <$1 of the 5th, but the loop is capped at 5)
        // -> converged/capped at 5,490.3245 -- independently reproduced against the SAME age-65-boosted
        // deduction and single-2025 brackets, matching the engine's own output to the cent.
        assertThat(expectedTax).isEqualByComparingTo(bd("4831.5000"));
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("5490.3245"));

        // Direction: strictly less tax than the age-unaware (OBBBA-base-only) deduction would have
        // produced -- the conservative direction of this fix.
        BigDecimal agelessTax = refCalc.computeTax(bd("60000"), referenceYear, FilingStatus.SINGLE);
        assertThat(expectedTax).isLessThan(agelessTax);
    }

    @Test
    void run_withPartTimeWorkAndSETax_computesSelfEmploymentTax() {
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 63;

        var spending = new SpendingProfileInput(bd("40000"), bd("10000"), "[]");

        var ptSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Consulting", IncomeSourceType.PART_TIME_WORK,
                bd("50000"), 60, 70, BigDecimal.ZERO, false,
                "self_employment",
                null, null, null, null, null, null);

        var input = createInput(
                LocalDate.of(currentYear - 1, 1, 1), 80, bd("0.03"),
                """
                {"birth_year": %d}
                """.formatted(birthYear),
                List.of(acct("300000", "0", "0.05")),
                spending, currentYear, List.of(ptSource));

        var result = engine.run(input);
        var year1 = result.yearlyData().getFirst();

        // SE tax should be calculated on $50k
        assertThat(year1.selfEmploymentTax()).isNotNull();
        assertThat(year1.selfEmploymentTax()).isGreaterThan(BigDecimal.ZERO);
    }

    // === Fill-bracket Roth conversion with state taxes ===

    private DeterministicProjectionEngine engineWithStateTax(String stateCode) {
        var calc = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
        var factory = mock(StateTaxCalculatorFactory.class);
        // Return a proportional 6% state tax calculator to simulate CA-like behavior
        StateTaxCalculator proportional = new StateTaxCalculator() {
            @Override
            public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
                return grossIncome.compareTo(BigDecimal.ZERO) > 0
                        ? grossIncome.multiply(bd("0.06")).setScale(4, java.math.RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            }

            @Override
            public BigDecimal getStandardDeduction(int taxYear, FilingStatus status) {
                return BigDecimal.ZERO;
            }

            @Override
            public String stateCode() {
                return stateCode;
            }

            @Override
            public boolean taxesCapitalGainsAsOrdinaryIncome() {
                return true;
            }
        };
        when(factory.forState(stateCode)).thenReturn(proportional);
        return new DeterministicProjectionEngine(calc, factory);
    }

    @Test
    void run_fillBracket_withState_22vs24_producesDifferentConversions() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);

        var engine22 = engineWithStateTax("CA");
        var input22 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("1500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        var engine24 = engineWithStateTax("CA");
        var input24 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.24}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("1500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        var result22 = engine22.run(input22);
        var result24 = engine24.run(input24);

        var year1at22 = result22.yearlyData().getFirst();
        var year1at24 = result24.yearlyData().getFirst();

        // Both should convert something
        assertThat(year1at22.rothConversionAmount()).isNotNull();
        assertThat(year1at24.rothConversionAmount()).isNotNull();

        // 24% target should convert MORE than 22% target
        assertThat(year1at24.rothConversionAmount()).isGreaterThan(year1at22.rothConversionAmount());
    }

    @Test
    void run_fillBracket_withState_22vs24_producesDifferentTax() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);

        var engine22 = engineWithStateTax("CA");
        var input22 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("1500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        var engine24 = engineWithStateTax("CA");
        var input24 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.24}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("1500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        var result22 = engine22.run(input22);
        var result24 = engine24.run(input24);

        var year1at22 = result22.yearlyData().getFirst();
        var year1at24 = result24.yearlyData().getFirst();

        // Higher bracket target → more conversion → more tax
        assertThat(year1at24.taxLiability()).isGreaterThan(year1at22.taxLiability());
    }

    @Test
    void run_fillBracket_withState_mfj_22vs24_differentConversions() {
        stubMfj2025(taxBracketRepository, standardDeductionRepository);

        var engine22 = engineWithStateTax("CA");
        var input22 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "married_filing_jointly", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("2000000", "0", "0.07", "traditional"),
                        acct("200000", "0", "0.07", "roth")));

        var engine24 = engineWithStateTax("CA");
        var input24 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "married_filing_jointly", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.24}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("2000000", "0", "0.07", "traditional"),
                        acct("200000", "0", "0.07", "roth")));

        var result22 = engine22.run(input22);
        var result24 = engine24.run(input24);

        var year1at22 = result22.yearlyData().getFirst();
        var year1at24 = result24.yearlyData().getFirst();

        // MFJ 22% bracket ceiling = $206,700, 24% = $394,600 — very different
        assertThat(year1at24.rothConversionAmount()).isGreaterThan(year1at22.rothConversionAmount());
        // The difference should be substantial
        assertThat(year1at24.rothConversionAmount().subtract(year1at22.rothConversionAmount()))
                .isGreaterThan(bd("100000"));
    }

    @Test
    void run_fillBracket_withState_conversionAmountMatchesFederalBracketCeiling() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);

        // With no state tax, fill_bracket at 12% should fill to federal 12% bracket
        var engineFedOnly = engineWithTax(taxBracketRepository, standardDeductionRepository);
        var inputFedOnly = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        // With state tax, fill_bracket at 12% should STILL fill to the federal 12% bracket
        // (the target rate refers to the federal bracket, not the combined rate)
        var engineState = engineWithStateTax("CA");
        var inputState = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        var resultFedOnly = engineFedOnly.run(inputFedOnly);
        var resultState = engineState.run(inputState);

        var fedConv = resultFedOnly.yearlyData().getFirst().rothConversionAmount();
        var stateConv = resultState.yearlyData().getFirst().rothConversionAmount();

        // Federal-only: 12% ceiling $48,475 + $15,000 standard deduction = $63,475
        assertThat(fedConv).isEqualByComparingTo(bd("63475"));

        // With state: conversion should be similar — possibly slightly different due to
        // itemized vs standard deduction choice, but should be in the same ballpark
        // (not wildly different due to combined marginal rate confusion)
        assertThat(stateConv).isGreaterThan(bd("50000"));
        assertThat(stateConv).isLessThan(bd("80000"));
    }

    // === Gap #1: Conversion + withdrawal combined tax must not double-count ===

    @Test
    void run_conversionAndTraditionalWithdrawal_correctCombinedTax() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Retired, 0% return, 0% inflation → perfectly predictable amounts
        // other_income=20000, annual_roth_conversion=30000, withdrawal_rate=0.04
        // withdrawal_order=traditional_first forces traditional withdrawal
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000,
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Conversion: $30K. Withdrawal: 4% of $900K = $36K from traditional.
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("30000"));
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("36000"));

        // Correct tax = tax($30K conv + $36K wd + $20K other = $86K gross)
        // Taxable = $86K - $15K deduction = $71K
        // 10% on $11,925 = $1,192.50
        // 12% on ($48,475 - $11,925) = $4,386.00
        // 22% on ($71,000 - $48,475) = $4,955.50
        // Total = $10,534.00
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("10534.0000"));
    }

    @Test
    void run_conversionAndTraditionalWithdrawal_taxLiabilityMatchesFederalTax() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000,
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // For federal-only, taxLiability must equal the federalTax breakdown
        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.taxLiability()).isEqualByComparingTo(year1.federalTax());
    }

    @Test
    void run_conversionAndTraditionalWithdrawal_withState_taxMatchesBreakdown() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineState = engineWithStateTax("CA");

        // The default 1.8% dividend yield books qualified-dividend income on the $300K taxable
        // account, and this CA-like state calculator taxes capital gains as ordinary income
        // (audit C3). The invariant below must hold WITH that realized capital income in play:
        // the pool-funded withdrawal-tax bundle and the annotator's displayed breakdown both apply
        // the SAME state-base adjustment, so taxLiability still reconciles exactly.
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000,
                 "withdrawal_order": "traditional_first", "state": "CA"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineState.run(input);
        var year1 = result.yearlyData().getFirst();

        // taxLiability must equal federalTax + stateTax
        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.stateTax()).isNotNull();
        BigDecimal breakdownTotal = year1.federalTax().add(year1.stateTax());
        assertThat(year1.taxLiability()).isEqualByComparingTo(breakdownTotal);
    }

    /**
     * End-to-end wiring check for audit C3's Social-Security-exemption seam: runs the SAME retiree
     * (Social Security + traditional withdrawal) through two otherwise-identical state calculators
     * that differ ONLY in {@code exemptsSocialSecurity()}, and pins the resulting state-tax delta to
     * exactly {@code rate * year1.socialSecurityTaxable()}. This exercises the REAL path -- {@code
     * YearFinanceResolver} -> {@code DeterministicProjectionEngine} -> {@code
     * RetirementTaxAnnotator} -> {@code CombinedTaxCalculator} -- catching wiring bugs (e.g. an
     * argument-order mistake in the many-BigDecimal {@code AnnotationContext} constructor) that a
     * pure {@code CombinedTaxCalculatorTest} unit test can't see.
     */
    @Test
    void run_stateExemptsSocialSecurity_reducesStateTaxByExactlyRateTimesSsTaxable() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var federal = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
        BigDecimal rate = bd("0.06");

        // Traditional-only draw with a zero-yield taxable BUFFER (dividend_yield explicitly 0, so it
        // generates no dividend/LTCG income of its own -- it exists purely to fund this year's tax
        // bill in both branches). Audit C2: once the tax bill drains traditional (which it would with
        // no taxable pool at all), that draw is itself grossed up as ordinary income -- and since the
        // two branches below owe DIFFERENT total tax (the state-exemption flag under test), they'd be
        // grossed up by different amounts too, breaking the "delta is EXACTLY rate * ssTaxable"
        // invariant this test pins. A taxable buffer large enough to fund the tax bill in both
        // branches keeps this test isolated to the C3/T8 SS-exemption wiring it's actually about.
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "withdrawal_order": "traditional_first", "state": "CA", "dividend_yield": 0}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("500000", "0", "0.00", "traditional"), acct("100000", "0", "0.00", "taxable")),
                null, List.of(socialSecuritySource("30000", 62)));

        var exemptEngine = new DeterministicProjectionEngine(
                federal, mockStateFactory("CA", rate, false, true));
        var year1Exempt = exemptEngine.run(input).yearlyData().getFirst();

        var taxedEngine = new DeterministicProjectionEngine(
                federal, mockStateFactory("CA", rate, false, false));
        var year1Taxed = taxedEngine.run(input).yearlyData().getFirst();

        // Same underlying federal SS-taxability computation regardless of the state's own treatment.
        assertThat(year1Exempt.socialSecurityTaxable()).isEqualByComparingTo(year1Taxed.socialSecurityTaxable());
        BigDecimal ssTaxable = year1Exempt.socialSecurityTaxable();
        assertThat(ssTaxable).isGreaterThan(BigDecimal.ZERO);

        BigDecimal expectedDelta = ssTaxable.multiply(rate).setScale(4, RoundingMode.HALF_UP);
        assertThat(year1Taxed.stateTax().subtract(year1Exempt.stateTax()))
                .isEqualByComparingTo(expectedDelta);
        assertThat(year1Exempt.federalTax()).isEqualByComparingTo(year1Taxed.federalTax());
        // The exemption is FUNDED, not just displayed: the year's pool-debited taxLiability moves by
        // the same amount (T8 review: the state adjustment must reach the pool cascade).
        assertThat(year1Taxed.taxLiability().subtract(year1Exempt.taxLiability()))
                .isEqualByComparingTo(expectedDelta);
    }

    /**
     * Pins the audit-C3 DOLLAR effect end-to-end: a CA-like retiree with qualified dividends
     * (realized capital income), taxable Social Security, and a traditional draw pays MORE pool-
     * debited tax than the identical scenario without state tax -- by exactly the state tax on the
     * ADJUSTED base (ordinary + LTCG/dividends - federally-taxed SS), and the year-end balance is
     * lower by the same amount. Proves the state adjustment reaches the funding cascade
     * (T8 review), not just the displayed breakdown.
     *
     * <p>Hand-computed fixture (returns 0.00, inflation 0, no conversion, no RMD at 66):
     * <ul>
     *   <li>draw = 4% x $800K = $32,000, all traditional (traditional_first; taxable untouched)</li>
     *   <li>dividend = $300K x 1.8% default yield = $5,400 = the year's realized LTCG income
     *       (no taxable sale, so no FIFO gain on top)</li>
     *   <li>V = converged federally-taxable SS (read from the DTO; identical in both runs since
     *       provisional income is independent of tax amounts)</li>
     *   <li>state base = (32,000 + V) + 5,400 - V = $37,400; at 6%: $2,244 exactly --
     *       V cancels, so the pin is exact without hard-coding V</li>
     * </ul>
     */
    @Test
    void run_caRetireeWithDividendsAndSs_poolDebitedTaxCarriesNetStateAdjustment() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var federal = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
        BigDecimal rate = bd("0.06");

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "withdrawal_order": "traditional_first", "state": "CA"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("300000", "0", "0.00", "taxable")),
                null, List.of(socialSecuritySource("30000", 62)));

        // CA-like state: taxes capital gains as ordinary income AND exempts Social Security.
        var caEngine = new DeterministicProjectionEngine(
                federal, mockStateFactory("CA", rate, true, true));
        var year1Ca = caEngine.run(input).yearlyData().getFirst();

        // Identical scenario, no state-tax factory -> FederalOnlyTaxStrategy (state modeled $0).
        var fedOnlyEngine = new DeterministicProjectionEngine(federal, null);
        var year1Fed = fedOnlyEngine.run(input).yearlyData().getFirst();

        // Same federal frame in both runs: identical converged SS taxability and federal tax.
        BigDecimal ssTaxable = year1Ca.socialSecurityTaxable();
        assertThat(ssTaxable).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1Fed.socialSecurityTaxable()).isEqualByComparingTo(ssTaxable);
        assertThat(year1Ca.federalTax()).isEqualByComparingTo(year1Fed.federalTax());

        // The net state adjustment: 6% x ($32,000 draw + $5,400 dividends; SS exempted).
        BigDecimal expectedStateTax = bd("2244.0000");
        assertThat(year1Ca.stateTax()).isEqualByComparingTo(expectedStateTax);
        // Direction checks: LTCG addition RAISED it above the ordinary-only base (6% x $32,000 =
        // $1,920 after the SS exemption cancels V), while the SS exemption LOWERED it below the
        // pre-C3 unadjusted base of 6% x ($32,000 + V).
        assertThat(year1Ca.stateTax()).isGreaterThan(bd("1920.0000"));
        BigDecimal preC3StateTax = bd("32000").add(ssTaxable).multiply(rate);
        assertThat(year1Ca.stateTax()).isLessThan(preC3StateTax);

        // The DOLLAR effect (T8 review): the state tax is pool-DEBITED, not just displayed --
        // taxLiability is higher and the year-end balance lower by exactly the state tax.
        assertThat(year1Ca.taxLiability().subtract(year1Fed.taxLiability()))
                .isEqualByComparingTo(expectedStateTax);
        assertThat(year1Fed.endBalance().subtract(year1Ca.endBalance()))
                .isEqualByComparingTo(expectedStateTax);
        // And the displayed breakdown reconciles with the funded liability.
        assertThat(year1Ca.federalTax().add(year1Ca.stateTax()))
                .isEqualByComparingTo(year1Ca.taxLiability());
    }

    /**
     * A mocked {@link StateTaxCalculatorFactory} resolving {@code stateCode} to a proportional
     * ({@code grossIncome * rate}) calculator with configurable audit-C3 flags
     * ({@code taxesCapitalGainsAsOrdinaryIncome}, {@code exemptsSocialSecurity}).
     */
    private StateTaxCalculatorFactory mockStateFactory(String stateCode, BigDecimal rate,
                                                         boolean taxesCapitalGainsAsOrdinaryIncome,
                                                         boolean exemptsSocialSecurity) {
        var factory = mock(StateTaxCalculatorFactory.class);
        StateTaxCalculator proportional = new StateTaxCalculator() {
            @Override
            public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
                return grossIncome.compareTo(BigDecimal.ZERO) > 0
                        ? grossIncome.multiply(rate).setScale(4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            }

            @Override
            public BigDecimal getStandardDeduction(int taxYear, FilingStatus status) {
                return BigDecimal.ZERO;
            }

            @Override
            public String stateCode() {
                return stateCode;
            }

            @Override
            public boolean taxesCapitalGainsAsOrdinaryIncome() {
                return taxesCapitalGainsAsOrdinaryIncome;
            }

            @Override
            public boolean exemptsSocialSecurity() {
                return exemptsSocialSecurity;
            }
        };
        when(factory.forState(stateCode)).thenReturn(proportional);
        return factory;
    }

    @Test
    void run_traditionalWithdrawalOnly_noConversion_taxCorrect() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // No Roth conversion — just traditional withdrawal
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Withdrawal: 4% of $800K = $32K from traditional
        // Tax = tax($32K + $20K other = $52K gross)
        // Taxable = $52K - $15K = $37K
        // 10% on $11,925 = $1,192.50
        // 12% on ($37,000 - $11,925) = $3,009.00
        // Total = $4,201.50
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("4201.5000"));
    }

    @Test
    void run_conversionOnly_preRetirement_taxCorrect() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Pre-retirement conversion only (no withdrawal)
        var input = createInput(
                LocalDate.now().plusYears(10), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Conversion: $30K. Tax = tax($30K + $20K = $50K)
        // Taxable = $50K - $15K = $35K
        // 10% on $11,925 = $1,192.50
        // 12% on ($35,000 - $11,925) = $2,769.00
        // Total = $3,961.50
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("30000"));
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("3961.5000"));
    }

    // === Gap #4: Tax source pool assignment when conversion exhausts taxable ===

    @Test
    void run_conversionTaxExhaustsTaxable_withdrawalTaxFallsOnTraditional() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Tiny taxable balance ($1000) — conversion tax ($3961.50) will exceed it
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000,
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("1000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Tax was paid — some must have come from traditional since taxable was tiny
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.taxPaidFromTraditional()).isNotNull();
        assertThat(year1.taxPaidFromTraditional()).isGreaterThan(BigDecimal.ZERO);
    }

    // === Gap #7: State tax breakdown fields in retirement years ===

    @Test
    void run_withStateTax_retirementYear_breakdownFieldsPopulated() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineState = engineWithStateTax("CA");

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000,
                 "withdrawal_order": "traditional_first", "state": "CA",
                 "primary_residence_property_tax": 5000}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineState.run(input);
        var year1 = result.yearlyData().getFirst();

        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.federalTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.stateTax()).isNotNull();
        assertThat(year1.stateTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.saltDeduction()).isNotNull();
        assertThat(year1.usedItemizedDeduction()).isNotNull();
    }

    // === Gap #8: Pre-retirement conversion tax breakdown fields ===

    @Test
    void run_preRetirementConversion_breakdownFieldsSet() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineState = engineWithStateTax("CA");

        var input = createInput(
                LocalDate.now().plusYears(10), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "annual_roth_conversion": 50000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineState.run(input);
        var year1 = result.yearlyData().getFirst();

        // Pre-retirement conversion should still have tax breakdown from lastTaxBreakdown
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.federalTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.stateTax()).isNotNull();
        assertThat(year1.stateTax()).isGreaterThan(BigDecimal.ZERO);
    }

    // === Primary residence deductions without state tax ===

    @Test
    void run_noStateTax_withPrimaryResidenceDeductions_usesItemizedWhenLarger() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // No state configured, but large primary residence deductions:
        // Property tax $12K, mortgage interest $25K
        // SALT = min($0 state tax + $12K property tax, $10K) = $10K
        // Itemized = $10K + $25K = $35K > standard $15K → should itemize
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "primary_residence_property_tax": 12000,
                 "primary_residence_mortgage_interest": 25000,
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Withdrawal: 4% of $600K = $24K from traditional
        // With standard deduction ($15K): taxable = $24K - $15K = $9K, tax ≈ $900
        // With itemized ($35K): taxable = $24K - $35K = negative → $0 tax
        // The engine should use itemized → null/zero tax
        // If FederalOnlyTaxStrategy is used, it applies standard → ~$900 tax
        if (year1.taxLiability() != null) {
            assertThat(year1.taxLiability()).isEqualByComparingTo(BigDecimal.ZERO);
        }
        // The key assertion: with itemized deductions, tax must be lower than
        // what standard deduction would produce
        assertThat(year1.usedItemizedDeduction()).isNotNull();
        assertThat(year1.usedItemizedDeduction()).isTrue();
    }

    @Test
    void run_suspendedLoss_carriesForwardAndReleasesInLaterYear() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 66;

        // High MAGI ($200K) eliminates $25K PAL exception → losses fully suspended
        // Year 1: $40K depreciation creates ~$29K loss (suspended)
        // Year 2: $0 depreciation → rental is profitable, suspended loss releases
        var depSchedule = Map.of(currentYear, bd("40000"));

        var rentalSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental", IncomeSourceType.RENTAL_PROPERTY,
                bd("24000"), 60, null, BigDecimal.ZERO, false,
                "rental_passive",
                bd("6000"), bd("4000"), null, bd("3000"),
                "straight_line", depSchedule);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 200000}
                """.formatted(birthYear),
                List.of(
                        acct("300000", "0", "0.00", "taxable"),
                        acct("200000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                null,
                List.of(rentalSource));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();
        var year2 = result.yearlyData().get(1);

        // Year 1: depreciation creates loss → suspended
        assertThat(year1.suspendedLossCarryforward()).isNotNull();
        assertThat(year1.suspendedLossCarryforward()).isGreaterThan(BigDecimal.ZERO);

        // Year 2: no depreciation → rental profitable → suspended loss releases
        assertThat(year2.suspendedLossCarryforward()).isNotNull();
        assertThat(year2.suspendedLossCarryforward()).isLessThan(year1.suspendedLossCarryforward());
    }

    @Test
    void run_fillBracket_retiredWithTraditionalWithdrawal_combinedTaxSpansBrackets() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Fill-bracket at 22% + large traditional withdrawal pushes into 24% bracket
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22,
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("1000000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("50000", "0", "0.00", "taxable")),
                new SpendingProfileInput(bd("40000"), bd("20000"), "[]"));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Conversion fills to 22% bracket: $103,350 + $15,000 deduction = $118,350
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("118350"));

        // Withdrawal: $60K from traditional (spending need)
        // Combined taxable = $118,350 + $60,000 = $178,350
        // Tax on $178,350 (with $15K deduction → $163,350 taxable):
        // 10%: $1,192.50, 12%: $4,386, 22%: $12,072.50, 24%: $14,400
        // Total = $32,051
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("32051.0000"));

        // Tax must be much higher than conversion-only tax
        assertThat(year1.taxLiability()).isGreaterThan(bd("20000"));
    }

    @Test
    void run_ssSurplus_taxOnTaxablePortionOnly() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // SS $40K + other_income $30K. The $100K taxable pool books a qualified dividend this year of
        // 100,000 * 0.018 (default dividend yield) = $1,800 (audit B2: dividends are AGI, so they enter
        // Social Security provisional income). Provisional = $30K other + $1,800 dividend + $20K(50% SS)
        // = $51,800 > $34K.
        // Both tiers: tier1 = ($34K-$25K)*0.5 = $4,500, tier2 = ($51,800-$34K)*0.85 = $15,130
        // SS taxable = $19,630 (< 85% cap of $34K)
        // effectiveOtherIncome = $30K (other_income) + $19,630 (SS taxable) = $49,630
        // Spending $20K < cash $40K → surplus = $20K
        // Tax on $49,630: taxable = $49,630 - $15K = $34,630
        // 10%: $1,192.50, 12%: $2,724.60 = $3,917.10
        // afterTaxSurplus = $20,000 - $3,917.10 = $16,082.90
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 30000}
                """.formatted(birthYear),
                List.of(
                        acct("200000", "0", "0.00", "roth"),
                        acct("100000", "0", "0.00", "taxable")),
                new SpendingProfileInput(bd("15000"), bd("5000"), "[]"),
                List.of(socialSecuritySource("40000", retireAge - 1)));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(bd("40000"));
        assertThat(year1.surplusReinvested()).isNotNull();
        assertThat(year1.surplusReinvested()).isEqualByComparingTo(bd("16082.9000"));
    }

    @Test
    void run_rothFirstWithdrawal_incomeSourceIncome_taxShouldBeComputed() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // Pension $30K, spending $45K, roth_first withdrawal covers $15K gap
        // No traditional withdrawal → no conversion → income tax on pension might be missed
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "withdrawal_order": "roth_first"}
                """.formatted(birthYear),
                List.of(
                        acct("300000", "0", "0.00", "roth"),
                        acct("100000", "0", "0.00", "taxable")),
                new SpendingProfileInput(bd("30000"), bd("15000"), "[]"),
                List.of(incomeSource("Pension", "30000", retireAge - 1, null, "0")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Pension $30K is taxable income — tax must be computed even without
        // traditional withdrawal or conversion.
        // Tax on $30K: taxable = $30K - $15K deduction = $15K
        // 10%: $11,925 * 0.10 = $1,192.50
        // 12%: ($15,000 - $11,925) * 0.12 = $369.00
        // Total = $1,561.50
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("1561.5000"));
    }

    // === Surplus tax must be reported in taxLiability ===

    @Test
    void run_surplusIncome_taxReportedInTaxLiability() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // Pension $50K exceeds spending $30K → surplus = $20K
        // Tax on $50K pension: taxable = $50K - $15K = $35K
        // 10%: $1,192.50, 12%: $2,769.00 = $3,961.50
        // afterTaxSurplus = $20K - $3,961.50 = $16,038.50
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single"}
                """.formatted(birthYear),
                List.of(
                        acct("300000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "roth")),
                new SpendingProfileInput(bd("20000"), bd("10000"), "[]"),
                List.of(incomeSource("Pension", "50000", retireAge - 1, null, "0")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Surplus correctly deposited after tax
        assertThat(year1.surplusReinvested()).isEqualByComparingTo(bd("16038.5000"));

        // The $3,961.50 income tax MUST appear in taxLiability — it's real tax owed
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("3961.5000"));
    }

    @Test
    void run_surplusIncome_taxLiabilityMatchesBreakdown() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineState = engineWithStateTax("CA");

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "primary_residence_property_tax": 5000}
                """.formatted(birthYear),
                List.of(
                        acct("300000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "roth")),
                new SpendingProfileInput(bd("20000"), bd("10000"), "[]"),
                List.of(incomeSource("Pension", "50000", retireAge - 1, null, "0")));

        var result = engineState.run(input);
        var year1 = result.yearlyData().getFirst();

        // taxLiability must equal federalTax + stateTax in surplus years too
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.stateTax()).isNotNull();
        BigDecimal breakdownSum = year1.federalTax().add(year1.stateTax());
        assertThat(year1.taxLiability()).isEqualByComparingTo(breakdownSum);
    }

    @Test
    void run_surplusIncome_withOtherIncome_taxIncludesOtherIncome() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // Pension $50K + other_income $20K → total taxable = $70K
        // Spending $30K < pension cash $50K → surplus = $20K
        // Tax should be on $70K (pension + other_income), not just $50K (pension only)
        // Tax on $70K: taxable = $70K - $15K = $55K
        // 10%: $1,192.50, 12%: $4,386.00, 22%: $1,435.50 = $7,014.00
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 20000}
                """.formatted(birthYear),
                List.of(
                        acct("300000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "roth")),
                new SpendingProfileInput(bd("20000"), bd("10000"), "[]"),
                List.of(incomeSource("Pension", "50000", retireAge - 1, null, "0")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // The tax must cover ALL taxable income ($70K), not just income sources ($50K)
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("7014.0000"));

        // Surplus deposit = cash surplus - full tax
        // Cash surplus = $50K - $30K = $20K
        // After-tax surplus = $20K - $7,014 = $12,986
        assertThat(year1.surplusReinvested()).isEqualByComparingTo(bd("12986.0000"));
    }

    @Test
    void run_surplusIncome_withOtherIncome_stateTax_breakdownMatches() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineState = engineWithStateTax("CA");

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // With state taxes and other_income, breakdown must match taxLiability
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "other_income": 20000}
                """.formatted(birthYear),
                List.of(
                        acct("300000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "roth")),
                new SpendingProfileInput(bd("20000"), bd("10000"), "[]"),
                List.of(incomeSource("Pension", "50000", retireAge - 1, null, "0")));

        var result = engineState.run(input);
        var year1 = result.yearlyData().getFirst();

        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.stateTax()).isNotNull();
        BigDecimal breakdownSum = year1.federalTax().add(year1.stateTax());
        assertThat(year1.taxLiability()).isEqualByComparingTo(breakdownSum);
    }

    // === Pool cascade: tax drains into Roth ===

    @Test
    void run_poolCascade_taxDrainsIntoRoth_exactValues() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Tiny taxable ($100) + tiny traditional ($1000). fee_rate pinned to 0 (audit B1) so the
        // 0%-real taxable pool stays EXACTLY flat -- this test isolates the tax-cascade dollar
        // math, not fee drag.
        // Conversion tax $3,961.50 exceeds both → remainder from Roth
        var input = createInput(
                LocalDate.now().plusYears(10), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "fee_rate": 0,
                 "other_income": 20000, "annual_roth_conversion": 30000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("50000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "roth"),
                        acct("100", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Conversion = $30K from traditional → traditional = $20K
        // Tax = $3,961.50
        // Real terms: taxable $100 pool grows at 0% real (0.00 nominal deflated at the scenario's
        // own 0% inflation) so it stays flat at 100; tax drains it, remainder 3961.50 - 100 = 3861.50
        // from traditional. Roth untouched.
        assertThat(year1.taxPaidFromTaxable()).isEqualByComparingTo(bd("100.0000"));
        assertThat(year1.taxPaidFromTraditional()).isEqualByComparingTo(bd("3861.5000"));

        // All balances >= 0
        assertThat(year1.taxableBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year1.traditionalBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(year1.rothBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // === Surplus + conversion must not double-count tax ===

    @Test
    void run_surplusWithConversion_taxNotDoubleCounted() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // Pension $50K > spending $30K → surplus $20K
        // Roth conversion $20K also happens
        // Conversion tax = tax($20K conv + $50K pension = $70K)
        // Taxable = $70K - $15K = $55K
        // 10%: $1,192.50, 12%: $4,386, 22%: $1,435.50 = $7,014
        // Surplus tax should be $0 — conversion already taxed everything
        // Total taxLiability = $7,014 (not $14,028)
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "annual_roth_conversion": 20000}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("50000", "0", "0.00", "taxable")),
                new SpendingProfileInput(bd("20000"), bd("10000"), "[]"),
                List.of(incomeSource("Pension", "50000", retireAge - 1, null, "0")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("20000"));

        // Tax must be computed ONCE on the $70K combined income, not twice
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("7014.0000"));

        // Full surplus deposited (no additional surplus tax)
        assertThat(year1.surplusReinvested()).isEqualByComparingTo(bd("20000"));
    }

    @Test
    void run_surplusWithConversion_breakdownMatchesTaxLiability() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "annual_roth_conversion": 20000}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("50000", "0", "0.00", "taxable")),
                new SpendingProfileInput(bd("20000"), bd("10000"), "[]"),
                List.of(incomeSource("Pension", "50000", retireAge - 1, null, "0")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // federalTax must match taxLiability (no state tax in this scenario)
        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.taxLiability()).isEqualByComparingTo(year1.federalTax());
    }

    @Test
    void run_poolCascade_conversionTaxExhaustsTaxable_exactValues() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Tiny taxable ($500), conversion tax $3,961.50 exceeds it. fee_rate pinned to 0 (audit B1)
        // so the 0%-real taxable pool stays EXACTLY flat -- this test isolates the tax-cascade
        // dollar math, not fee drag.
        var input = createInput(
                LocalDate.now().plusYears(10), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "fee_rate": 0,
                 "other_income": 20000, "annual_roth_conversion": 30000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("500", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Conversion tax = tax($50K) = $3,961.50
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("3961.5000"));

        // Real terms: taxable $500 pool grows at 0% real (0.00 nominal deflated at the scenario's own
        // 0% inflation) so it stays flat at 500; tax drains it, remainder 3961.50 - 500 = 3461.50 from
        // traditional.
        assertThat(year1.taxPaidFromTaxable()).isEqualByComparingTo(bd("500.0000"));
        assertThat(year1.taxPaidFromTraditional()).isEqualByComparingTo(bd("3461.5000"));
        assertThat(year1.taxPaidFromRoth()).isNull();

        // Taxable fully drained
        assertThat(year1.taxableBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        // All balances non-negative
        assertThat(year1.traditionalBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(year1.rothBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // === SE tax must be subtracted from surplus deposit ===

    @Test
    void run_surplusWithSelfEmployment_depositSubtractsSETax() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // SE income $80K, spending $50K → grossSurplus = $30K
        // SE tax: 15.3% on 92.35% of $80K = 15.3% * $73,880 = $11,303.64
        // Income tax on $80K: taxable = $80K - $15K = $65K
        // 10%: $1,192.50, 12%: $4,386, 22%: $3,635.50 = $9,214
        // Total tax = $9,214 (income) + $11,303.64 (SE) = $20,517.64
        // afterTaxSurplus should = $30K - $9,214 - $11,303.64 = $9,482.36
        // (not $30K - $9,214 = $20,786 — which ignores SE tax)
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single"}
                """.formatted(birthYear),
                List.of(
                        acct("300000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "roth")),
                new SpendingProfileInput(bd("35000"), bd("15000"), "[]"),
                List.of(selfEmploymentSource("Consulting", "80000", retireAge - 1, null)));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // taxLiability includes both income tax and SE tax
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.selfEmploymentTax()).isNotNull();
        assertThat(year1.selfEmploymentTax()).isGreaterThan(bd("10000"));

        // surplusReinvested must reflect BOTH income tax AND SE tax deductions
        // If SE tax is ignored in the deposit, surplusReinvested would be too large
        if (year1.surplusReinvested() != null) {
            BigDecimal incomeTaxPortion = year1.taxLiability().subtract(year1.selfEmploymentTax());
            BigDecimal correctSurplus = bd("30000").subtract(incomeTaxPortion)
                    .subtract(year1.selfEmploymentTax()).max(BigDecimal.ZERO);
            assertThat(year1.surplusReinvested()).isEqualByComparingTo(correctSurplus);
        }

        // spendingSurplus and surplusReinvested must be consistent
        // (both account for SE tax)
        if (year1.surplusReinvested() != null && year1.spendingSurplus() != null) {
            // surplusReinvested should not exceed what spendingSurplus indicates
            assertThat(year1.surplusReinvested())
                    .isLessThanOrEqualTo(year1.spendingSurplus().max(BigDecimal.ZERO).add(bd("1")));
        }
    }

    // === IRMAA warning tests ===

    @Test
    void irmaaWarning_age63AboveBracket_warningTrue() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Person is 63, retired since 60. Large traditional balance → fill_bracket at 22% will
        // convert up to the 22% ceiling ($118,350 for single). Plus other_income of $50K.
        // Total income = other_income ($50K) + conversion (~$68,350) = ~$118,350, right at ceiling.
        // Add an income source to push above the ceiling.
        int currentAge = 63;
        int birthYear = LocalDate.now().getYear() - currentAge;

        var input = createInput(
                LocalDate.of(birthYear + 60, 1, 1), 70, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 50000,
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22,
                 "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(
                        acct("2000000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                new SpendingProfileInput(bd("30000"), bd("10000"), null),
                List.of(incomeSource("Pension", "80000", 60, null, "0")));

        var result = engineTax.run(input);

        // Find the year where age is 63
        var age63Year = result.yearlyData().stream()
                .filter(y -> y.age() == 63)
                .findFirst()
                .orElseThrow();

        assertThat(age63Year.irmaaWarning()).isTrue();
    }

    @Test
    void irmaaWarning_age62AboveBracket_warningFalse() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Same high income scenario but at age 62 — below the IRMAA age threshold (63)
        int currentAge = 62;
        int birthYear = LocalDate.now().getYear() - currentAge;

        var input = createInput(
                LocalDate.of(birthYear + 60, 1, 1), 65, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 50000,
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22,
                 "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(
                        acct("2000000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                new SpendingProfileInput(bd("30000"), bd("10000"), null),
                List.of(incomeSource("Pension", "80000", 60, null, "0")));

        var result = engineTax.run(input);

        // Find the year where age is 62
        var age62Year = result.yearlyData().stream()
                .filter(y -> y.age() == 62)
                .findFirst()
                .orElseThrow();

        // Age 62 is below IRMAA threshold — no warning regardless of income
        assertThat(age62Year.irmaaWarning()).isNull();
    }

    @Test
    void irmaaWarning_age63BelowBracket_warningFalse() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Age 63 but low income well within 22% bracket ceiling ($118,350 for single)
        int currentAge = 63;
        int birthYear = LocalDate.now().getYear() - currentAge;

        var input = createInput(
                LocalDate.of(birthYear + 60, 1, 1), 70, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(
                        acct("100000", "0", "0.00", "traditional"),
                        acct("50000", "0", "0.00", "taxable")),
                new SpendingProfileInput(bd("5000"), bd("1000"), null));

        var result = engineTax.run(input);

        // Find the year where age is 63
        var age63Year = result.yearlyData().stream()
                .filter(y -> y.age() == 63)
                .findFirst()
                .orElseThrow();

        // Income is well below 22% bracket ceiling — no IRMAA warning
        assertThat(age63Year.irmaaWarning()).isNull();
    }

    // === Coverage gap: Tax strategy building (buildTaxStrategy) ===

    @Test
    void run_nullTaxCalculator_noTaxBreakdownInResults() {
        // Engine constructed with null taxCalculator (the default setUp engine)
        // Tax-related fields should be null in results
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.05", "traditional"),
                        acct("100000", "0", "0.05", "roth")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        // Without a tax calculator, no detailed breakdown should be present
        assertThat(year1.federalTax()).isNull();
        assertThat(year1.stateTax()).isNull();
        assertThat(year1.saltDeduction()).isNull();
        assertThat(year1.usedItemizedDeduction()).isNull();
        assertThat(year1.irmaaWarning()).isNull();
    }

    @Test
    void run_stateTaxConfigured_stateTaxAppearsInBreakdown() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineState = engineWithStateTax("CA");

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 50000, "state": "CA",
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")));

        var result = engineState.run(input);
        var year1 = result.yearlyData().getFirst();

        // With state tax configured, CombinedTaxCalculator is used
        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.federalTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.stateTax()).isNotNull();
        assertThat(year1.stateTax()).isGreaterThan(BigDecimal.ZERO);
        // taxLiability should be the sum of federal + state
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isGreaterThan(year1.federalTax());
    }

    @Test
    void run_noStateTaxButPropertyTaxPositive_usesNullStateTaxCalculatorWithItemizedComparison() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // No state configured, but property tax > 0 → triggers CombinedTaxCalculator
        // with NullStateTaxCalculator for itemized vs standard comparison
        // Property tax $8K, mortgage interest $10K → itemized = min($8K, $10K cap) + $10K = $18K > $15K standard
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "primary_residence_property_tax": 8000,
                 "primary_residence_mortgage_interest": 10000,
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // With itemized deduction ($18K) > standard ($15K), should use itemized
        assertThat(year1.usedItemizedDeduction()).isNotNull();
        assertThat(year1.usedItemizedDeduction()).isTrue();
        // State tax should be null since NullStateTaxCalculator returns 0
        assertThat(year1.stateTax()).isNull();
        // Federal tax should still be computed
        assertThat(year1.federalTax()).isNotNull();
    }

    // === Coverage gap: IRMAA warning ===

    @Test
    void run_retiredAge63IncomeExceedsIrmaaBracket_irmaaWarningSet() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int currentAge = 63;
        int birthYear = LocalDate.now().getYear() - currentAge;

        // Retired at 60, now 63, large traditional withdrawal + other income pushes past 22% ceiling
        // 22% bracket ceiling for single: $48,475 + $15,000 std deduction = $63,475
        // Other income $50K + large traditional withdrawal should exceed this
        var input = createInput(
                LocalDate.of(birthYear + 60, 1, 1), 70, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 50000,
                 "withdrawal_order": "traditional_first"}
                """.formatted(birthYear),
                List.of(
                        acct("2000000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                new SpendingProfileInput(bd("60000"), bd("20000"), null));

        var result = engineTax.run(input);

        var age63Year = result.yearlyData().stream()
                .filter(y -> y.age() == 63)
                .findFirst()
                .orElseThrow();

        // At age 63 with high income, IRMAA warning should be set
        assertThat(age63Year.irmaaWarning()).isTrue();
    }

    @Test
    void run_retiredAgeBelow63_noIrmaaWarningRegardlessOfIncome() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Retire at 58, check age 60 — well below 63 threshold
        int currentAge = 60;
        int birthYear = LocalDate.now().getYear() - currentAge;

        var input = createInput(
                LocalDate.of(birthYear + 58, 1, 1), 65, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 200000,
                 "annual_roth_conversion": 100000,
                 "withdrawal_order": "traditional_first"}
                """.formatted(birthYear),
                List.of(
                        acct("3000000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                new SpendingProfileInput(bd("50000"), bd("20000"), null));

        var result = engineTax.run(input);

        // Check all years before age 63 — none should have IRMAA warning
        for (var year : result.yearlyData()) {
            if (year.age() < 63) {
                assertThat(year.irmaaWarning())
                        .as("age %d should not have IRMAA warning", year.age())
                        .isNull();
            }
        }
    }
}
