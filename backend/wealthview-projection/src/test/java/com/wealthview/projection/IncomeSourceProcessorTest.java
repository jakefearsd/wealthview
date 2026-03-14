package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import com.wealthview.core.projection.tax.SelfEmploymentTaxCalculator;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncomeSourceProcessorTest {

    @Mock
    private RentalLossCalculator rentalLossCalculator;

    @Mock
    private SocialSecurityTaxCalculator ssTaxCalculator;

    @Mock
    private SelfEmploymentTaxCalculator seTaxCalculator;

    private IncomeSourceProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new IncomeSourceProcessor(rentalLossCalculator, ssTaxCalculator, seTaxCalculator);
    }

    // --- Empty / null source list ---

    @Test
    void process_emptySourceList_returnsAllZerosWithSuspendedLossPassedThrough() {
        var suspendedLoss = new BigDecimal("5000.0000");

        var result = processor.process(
                Collections.emptyList(), 65, 1, 2026,
                BigDecimal.ZERO, "single", suspendedLoss);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.rentalIncomeGross()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.rentalExpensesTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.depreciationTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.rentalLossApplied()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.suspendedLossCarryforward()).isEqualByComparingTo(suspendedLoss);
        assertThat(result.socialSecurityTaxable()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.selfEmploymentTax()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void process_nullSourceList_returnsAllZerosWithSuspendedLossPassedThrough() {
        var suspendedLoss = new BigDecimal("12345.6789");

        var result = processor.process(
                null, 70, 5, 2030,
                new BigDecimal("50000"), "married_filing_jointly", suspendedLoss);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.suspendedLossCarryforward()).isEqualByComparingTo(suspendedLoss);
    }

    // --- Age filtering ---

    @Test
    void process_ageBeforeStartAge_sourceInactive() {
        var source = makeSource("pension", new BigDecimal("24000"), 65, 70,
                BigDecimal.ZERO, null);

        var result = processor.process(
                List.of(source), 64, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void process_ageEqualsStartAge_halvesAmount() {
        var source = makeSource("pension", new BigDecimal("24000"), 65, 70,
                BigDecimal.ZERO, null);

        var result = processor.process(
                List.of(source), 65, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(new BigDecimal("12000"));
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(new BigDecimal("12000"));
    }

    @Test
    void process_ageOneBeforeEndAge_sourceActive() {
        var source = makeSource("pension", new BigDecimal("24000"), 65, 70,
                BigDecimal.ZERO, null);

        var result = processor.process(
                List.of(source), 69, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(new BigDecimal("24000"));
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(new BigDecimal("24000"));
    }

    @Test
    void process_ageEqualsEndAge_halvesAmount() {
        var source = makeSource("pension", new BigDecimal("24000"), 65, 70,
                BigDecimal.ZERO, null);

        var result = processor.process(
                List.of(source), 70, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(new BigDecimal("12000"));
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(new BigDecimal("12000"));
    }

    @Test
    void process_ageAfterEndAge_sourceInactive() {
        var source = makeSource("pension", new BigDecimal("24000"), 65, 70,
                BigDecimal.ZERO, null);

        var result = processor.process(
                List.of(source), 71, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void process_midRange_fullAmount() {
        var source = makeSource("pension", new BigDecimal("24000"), 65, 70,
                BigDecimal.ZERO, null);

        var result = processor.process(
                List.of(source), 67, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(new BigDecimal("24000"));
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(new BigDecimal("24000"));
    }

    // --- Inflation computation ---

    @Test
    void process_inflationApplied_nominalAmountInflatesForYearsInRetirement() {
        var inflationRate = new BigDecimal("0.03");
        var annualAmount = new BigDecimal("10000");
        var source = makeSource("pension", annualAmount, 60, null,
                inflationRate, null);

        // yearsInRetirement = 4 means 3 years of compounding: 10000 * 1.03^3
        var result = processor.process(
                List.of(source), 63, 4, 2029,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        var expectedNominal = annualAmount
                .multiply(BigDecimal.ONE.add(inflationRate).pow(3))
                .setScale(4, RoundingMode.HALF_UP);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(expectedNominal);
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(expectedNominal);
    }

    @Test
    void process_yearsInRetirementOne_halvesAtStartAge() {
        var source = makeSource("pension", new BigDecimal("10000"), 60, null,
                new BigDecimal("0.05"), null);

        var result = processor.process(
                List.of(source), 60, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(new BigDecimal("5000"));
    }

    // --- Tax-free income ---

    @Test
    void process_taxFreeIncome_includedInCashInflowButNotTaxableIncome() {
        var source = makeSource("annuity", new BigDecimal("18000"), 60, null,
                BigDecimal.ZERO, "tax_free");

        var result = processor.process(
                List.of(source), 65, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(new BigDecimal("18000"));
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- One-time source tests ---

    @Test
    void process_oneTimeSource_atStartAge_fullAmount() {
        var source = makeOneTimeSource(new BigDecimal("50000"), 65);

        var result = processor.process(
                List.of(source), 65, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    void process_oneTimeSource_atEndAge_inactive() {
        var source = makeOneTimeSource(new BigDecimal("50000"), 65);

        var result = processor.process(
                List.of(source), 66, 2, 2027,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void process_oneTimeSource_withInflation_ignoresInflation() {
        var source = makeOneTimeSource(new BigDecimal("50000"), 65);

        var result = processor.process(
                List.of(source), 65, 5, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.totalCashInflow()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo(new BigDecimal("50000"));
    }

    // --- Per-source income map ---

    @Test
    void process_rentalWithExpenses_incomeBySourceContainsNetCashFlow() {
        var rentalId = UUID.randomUUID();
        var rental = new ProjectionIncomeSourceInput(
                rentalId, "Rental", "rental_property",
                new BigDecimal("24000"), 65, null,
                BigDecimal.ZERO, false, "active_participation",
                new BigDecimal("3600"),   // annualOperatingExpenses (insurance+maintenance)
                new BigDecimal("9600"),   // annualMortgageInterest
                new BigDecimal("5000"),   // annualPropertyTax
                null, null);

        when(rentalLossCalculator.applyLossRules(any(), eq("active_participation"),
                any(), any(), any()))
                .thenReturn(new RentalLossCalculator.LossResult(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5800")));

        var result = processor.process(
                List.of(rental), 67, 3, 2028,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        // NET = 24000 - (3600 + 9600 + 5000) = 24000 - 18200 = 5800
        assertThat(result.incomeBySource()).containsKey(rentalId.toString());
        assertThat(result.incomeBySource().get(rentalId.toString()))
                .isEqualByComparingTo(new BigDecimal("5800"));
    }

    @Test
    void process_pensionSource_incomeBySourceContainsNominal() {
        var pensionId = UUID.randomUUID();
        var pension = new ProjectionIncomeSourceInput(
                pensionId, "Pension", "pension",
                new BigDecimal("30000"), 65, null,
                BigDecimal.ZERO, false, "taxable",
                null, null, null, null, null);

        var result = processor.process(
                List.of(pension), 67, 3, 2028,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.incomeBySource()).containsKey(pensionId.toString());
        assertThat(result.incomeBySource().get(pensionId.toString()))
                .isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    void process_multipleSources_incomeBySourceContainsAllActive() {
        var rentalId = UUID.randomUUID();
        var pensionId = UUID.randomUUID();
        var rental = new ProjectionIncomeSourceInput(
                rentalId, "Rental", "rental_property",
                new BigDecimal("24000"), 65, null,
                BigDecimal.ZERO, false, "active_participation",
                new BigDecimal("6000"), null, null, null, null);
        var pension = new ProjectionIncomeSourceInput(
                pensionId, "Pension", "pension",
                new BigDecimal("20000"), 65, null,
                BigDecimal.ZERO, false, "taxable",
                null, null, null, null, null);

        when(rentalLossCalculator.applyLossRules(any(), eq("active_participation"),
                any(), any(), any()))
                .thenReturn(new RentalLossCalculator.LossResult(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("18000")));

        var result = processor.process(
                List.of(rental, pension), 67, 3, 2028,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.incomeBySource()).hasSize(2);
        // Rental NET = 24000 - 6000 = 18000
        assertThat(result.incomeBySource().get(rentalId.toString()))
                .isEqualByComparingTo(new BigDecimal("18000"));
        assertThat(result.incomeBySource().get(pensionId.toString()))
                .isEqualByComparingTo(new BigDecimal("20000"));
    }

    @Test
    void process_inactiveSource_notInIncomeBySource() {
        var pensionId = UUID.randomUUID();
        var pension = new ProjectionIncomeSourceInput(
                pensionId, "Pension", "pension",
                new BigDecimal("30000"), 70, null,
                BigDecimal.ZERO, false, "taxable",
                null, null, null, null, null);

        var result = processor.process(
                List.of(pension), 65, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.incomeBySource()).isEmpty();
    }

    @Test
    void process_emptyList_incomeBySourceIsEmpty() {
        var result = processor.process(
                Collections.emptyList(), 65, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        assertThat(result.incomeBySource()).isEmpty();
    }

    // --- Rental transition multiplier on expenses ---

    @Test
    void process_rentalAtEndAge_halvesExpensesAndGross() {
        var rentalId = UUID.randomUUID();
        var rental = new ProjectionIncomeSourceInput(
                rentalId, "Rental", "rental_property",
                new BigDecimal("24000"), 65, 70,
                BigDecimal.ZERO, false, "active_participation",
                new BigDecimal("3600"),   // annualOperatingExpenses
                new BigDecimal("9600"),   // annualMortgageInterest
                new BigDecimal("5000"),   // annualPropertyTax
                null, null);

        when(rentalLossCalculator.applyLossRules(any(), eq("active_participation"),
                any(), any(), any()))
                .thenReturn(new RentalLossCalculator.LossResult(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        var result = processor.process(
                List.of(rental), 70, 6, 2031,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        // gross=24000*0.5=12000, expenses=(3600+9600+5000)*0.5=9100, net=2900
        assertThat(result.incomeBySource().get(rentalId.toString()))
                .isEqualByComparingTo(new BigDecimal("2900"));
        assertThat(result.totalCashInflow()).isEqualByComparingTo(new BigDecimal("2900"));
        assertThat(result.rentalIncomeGross()).isEqualByComparingTo(new BigDecimal("12000"));
        assertThat(result.rentalExpensesTotal()).isEqualByComparingTo(new BigDecimal("9100"));
    }

    @Test
    void process_rentalAtStartAge_halvesExpensesAndGross() {
        var rentalId = UUID.randomUUID();
        var rental = new ProjectionIncomeSourceInput(
                rentalId, "Rental", "rental_property",
                new BigDecimal("24000"), 65, 70,
                BigDecimal.ZERO, false, "active_participation",
                new BigDecimal("3600"),
                new BigDecimal("9600"),
                new BigDecimal("5000"),
                null, null);

        when(rentalLossCalculator.applyLossRules(any(), eq("active_participation"),
                any(), any(), any()))
                .thenReturn(new RentalLossCalculator.LossResult(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        var result = processor.process(
                List.of(rental), 65, 1, 2026,
                BigDecimal.ZERO, "single", BigDecimal.ZERO);

        // Same math: gross halved, expenses halved
        assertThat(result.incomeBySource().get(rentalId.toString()))
                .isEqualByComparingTo(new BigDecimal("2900"));
        assertThat(result.totalCashInflow()).isEqualByComparingTo(new BigDecimal("2900"));
        assertThat(result.rentalIncomeGross()).isEqualByComparingTo(new BigDecimal("12000"));
        assertThat(result.rentalExpensesTotal()).isEqualByComparingTo(new BigDecimal("9100"));
    }

    // --- Helper ---

    private static ProjectionIncomeSourceInput makeOneTimeSource(
            BigDecimal annualAmount, int startAge) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(),
                "One-time event",
                "other",
                annualAmount,
                startAge,
                startAge + 1,
                new BigDecimal("0.03"),  // non-zero inflation — should be ignored
                true,
                "taxable",
                null, null, null, null, null);
    }

    private static ProjectionIncomeSourceInput makeSource(
            String incomeType, BigDecimal annualAmount, int startAge, Integer endAge,
            BigDecimal inflationRate, String taxTreatment) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(),
                "Test " + incomeType,
                incomeType,
                annualAmount,
                startAge,
                endAge,
                inflationRate,
                false,
                taxTreatment,
                null,   // annualOperatingExpenses
                null,   // annualMortgageInterest
                null,   // annualPropertyTax
                null,   // depreciationMethod
                null    // depreciationByYear
        );
    }
}
