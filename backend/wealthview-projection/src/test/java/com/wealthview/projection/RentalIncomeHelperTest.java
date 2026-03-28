package com.wealthview.projection;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.RentalLossCalculator;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RentalIncomeHelperTest {

    private RentalLossCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new RentalLossCalculator();
    }

    // --- Positive net rental income ---

    @Test
    void computeForSource_incomeExceedsExpensesAndDepreciation_returnsPositiveNetTaxable() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("36000"))
                .annualOperatingExpenses(new BigDecimal("6000"))
                .annualPropertyTax(new BigDecimal("3000"))
                .annualMortgageInterest(new BigDecimal("5000"))
                .depreciationByYear(Map.of(2030, new BigDecimal("10000")))
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 80000, BigDecimal.ZERO, calculator);

        // 36000 - 6000 - 3000 - 5000 - 10000 = 12000 net positive
        assertThat(result.netTaxableIncome()).isCloseTo(12000.0, within(0.01));
        assertThat(result.newSuspendedLoss()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- Negative net rental income (loss case) ---

    @Test
    void computeForSource_expensesExceedIncome_returnsLossThroughPassiveRules() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("12000"))
                .annualOperatingExpenses(new BigDecimal("10000"))
                .annualPropertyTax(new BigDecimal("5000"))
                .annualMortgageInterest(new BigDecimal("8000"))
                .depreciationByYear(Map.of(2030, new BigDecimal("5000")))
                .taxTreatment("rental_passive")
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 80000, BigDecimal.ZERO, calculator);

        // 12000 - 10000 - 5000 - 8000 - 5000 = -16000
        // Passive with MAGI < 100k: full $25k exception covers -16000 loss
        assertThat(result.netTaxableIncome()).isCloseTo(-16000.0, within(0.01));
        assertThat(result.newSuspendedLoss()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- Zero inflation rate ---

    @Test
    void computeForSource_zeroInflationRate_noInflationApplied() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("24000"))
                .inflationRate(BigDecimal.ZERO)
                .annualOperatingExpenses(new BigDecimal("4000"))
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 5, 2035, 50000, BigDecimal.ZERO, calculator);

        // Zero inflation: gross stays 24000, expenses 4000, net = 20000
        assertThat(result.netTaxableIncome()).isCloseTo(20000.0, within(0.01));
    }

    // --- Null depreciation schedule ---

    @Test
    void computeForSource_nullDepreciationSchedule_treatsDepreciationAsZero() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("24000"))
                .annualOperatingExpenses(new BigDecimal("4000"))
                .depreciationByYear(null)
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 50000, BigDecimal.ZERO, calculator);

        // 24000 - 4000 = 20000 (no depreciation)
        assertThat(result.netTaxableIncome()).isCloseTo(20000.0, within(0.01));
    }

    // --- Year index beyond depreciation schedule length ---

    @Test
    void computeForSource_yearNotInDepreciationMap_treatsDepreciationAsZero() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("24000"))
                .annualOperatingExpenses(new BigDecimal("4000"))
                .depreciationByYear(Map.of(2030, new BigDecimal("10000")))
                .build();

        // Calendar year 2035 is not in the depreciation map
        var result = RentalIncomeHelper.computeForSource(
                source, 5, 2035, 50000, BigDecimal.ZERO, calculator);

        // Inflation at 3%: 24000 * (1.03)^5 = ~27822.35, minus 4000 expenses
        assertThat(result.netTaxableIncome()).isCloseTo(27822.35 - 4000, within(1.0));
    }

    // --- Mortgage interest deduction included ---

    @Test
    void computeForSource_withMortgageInterest_deductsFromIncome() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("36000"))
                .annualOperatingExpenses(BigDecimal.ZERO)
                .annualPropertyTax(BigDecimal.ZERO)
                .annualMortgageInterest(new BigDecimal("12000"))
                .depreciationByYear(null)
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 50000, BigDecimal.ZERO, calculator);

        // 36000 - 0 - 0 - 12000 - 0 = 24000
        assertThat(result.netTaxableIncome()).isCloseTo(24000.0, within(0.01));
    }

    // --- No mortgage (null loan fields) ---

    @Test
    void computeForSource_nullMortgageInterest_treatsAsZero() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("24000"))
                .annualOperatingExpenses(new BigDecimal("4000"))
                .annualMortgageInterest(null)
                .depreciationByYear(null)
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 50000, BigDecimal.ZERO, calculator);

        // 24000 - 4000 - 0 = 20000
        assertThat(result.netTaxableIncome()).isCloseTo(20000.0, within(0.01));
    }

    // --- Combined depreciation + expenses ---

    @Test
    void computeForSource_depreciationAndExpensesCombined_subtractsBoth() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("30000"))
                .annualOperatingExpenses(new BigDecimal("5000"))
                .annualPropertyTax(new BigDecimal("3000"))
                .annualMortgageInterest(BigDecimal.ZERO)
                .depreciationByYear(Map.of(2030, new BigDecimal("8000")))
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 50000, BigDecimal.ZERO, calculator);

        // 30000 - 5000 - 3000 - 0 - 8000 = 14000
        assertThat(result.netTaxableIncome()).isCloseTo(14000.0, within(0.01));
    }

    // --- Suspended loss carryforward: loss exceeds allowance ---

    @Test
    void computeForSource_lossExceedsPassiveAllowance_suspendedLossReturned() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("10000"))
                .annualOperatingExpenses(new BigDecimal("30000"))
                .annualPropertyTax(new BigDecimal("5000"))
                .annualMortgageInterest(new BigDecimal("10000"))
                .depreciationByYear(Map.of(2030, new BigDecimal("5000")))
                .taxTreatment("rental_passive")
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 80000, BigDecimal.ZERO, calculator);

        // 10000 - 30000 - 5000 - 10000 - 5000 = -40000
        // Passive with MAGI 80k (< 100k): $25k exception applies
        // -25000 applied via exception, 15000 suspended
        assertThat(result.netTaxableIncome()).isCloseTo(-25000.0, within(0.01));
        assertThat(result.newSuspendedLoss()).isEqualByComparingTo(new BigDecimal("15000"));
    }

    // --- Suspended loss carryforward: prior suspended loss applied in current year ---

    @Test
    void computeForSource_priorSuspendedLoss_appliedAgainstPositiveIncome() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("30000"))
                .annualOperatingExpenses(new BigDecimal("10000"))
                .depreciationByYear(null)
                .build();

        var priorSuspended = new BigDecimal("8000");

        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 80000, priorSuspended, calculator);

        // Net income before loss rules: 30000 - 10000 = 20000 (positive)
        // Prior suspended 8000 released against 20000 -> net taxable = 12000
        assertThat(result.netTaxableIncome()).isCloseTo(12000.0, within(0.01));
        assertThat(result.newSuspendedLoss()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- Active/REPS property: full loss deductible ---

    @Test
    void computeForSource_activeRepsProperty_fullLossDeductible() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("10000"))
                .annualOperatingExpenses(new BigDecimal("20000"))
                .annualPropertyTax(new BigDecimal("5000"))
                .depreciationByYear(Map.of(2030, new BigDecimal("5000")))
                .taxTreatment("rental_active_reps")
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 200000, BigDecimal.ZERO, calculator);

        // 10000 - 20000 - 5000 - 0 - 5000 = -20000
        // Active REPS: full loss deductible regardless of MAGI
        assertThat(result.netTaxableIncome()).isCloseTo(-20000.0, within(0.01));
        assertThat(result.newSuspendedLoss()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- Passive property: high MAGI phases out $25K exception ---

    @Test
    void computeForSource_passiveHighMagi_exceptionPhasedOut() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("10000"))
                .annualOperatingExpenses(new BigDecimal("20000"))
                .annualPropertyTax(BigDecimal.ZERO)
                .annualMortgageInterest(BigDecimal.ZERO)
                .depreciationByYear(null)
                .taxTreatment("rental_passive")
                .build();

        // MAGI at 150k: full phaseout of $25k exception
        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 150000, BigDecimal.ZERO, calculator);

        // Net = 10000 - 20000 = -10000 loss
        // MAGI >= 150k: exception = 0, entire loss suspended
        assertThat(result.netTaxableIncome()).isCloseTo(0.0, within(0.01));
        assertThat(result.newSuspendedLoss()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    void computeForSource_passivePartialPhaseout_reducedException() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("10000"))
                .annualOperatingExpenses(new BigDecimal("40000"))
                .annualPropertyTax(BigDecimal.ZERO)
                .annualMortgageInterest(BigDecimal.ZERO)
                .depreciationByYear(null)
                .taxTreatment("rental_passive")
                .build();

        // MAGI at 120k: reduction = (120000-100000)*0.5 = 10000, exception = 25000 - 10000 = 15000
        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 120000, BigDecimal.ZERO, calculator);

        // Net = 10000 - 40000 = -30000 loss
        // Exception = 15000, so 15000 applied, 15000 suspended
        assertThat(result.netTaxableIncome()).isCloseTo(-15000.0, within(0.01));
        assertThat(result.newSuspendedLoss()).isEqualByComparingTo(new BigDecimal("15000"));
    }

    // --- Zero rental income ---

    @Test
    void computeForSource_zeroAnnualAmount_onlyExpensesCreateLoss() {
        var source = sourceBuilder()
                .annualAmount(BigDecimal.ZERO)
                .annualOperatingExpenses(new BigDecimal("5000"))
                .annualPropertyTax(BigDecimal.ZERO)
                .annualMortgageInterest(BigDecimal.ZERO)
                .depreciationByYear(null)
                .taxTreatment("rental_passive")
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 80000, BigDecimal.ZERO, calculator);

        // 0 - 5000 = -5000, under $25k exception
        assertThat(result.netTaxableIncome()).isCloseTo(-5000.0, within(0.01));
        assertThat(result.newSuspendedLoss()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- Cost segregation depreciation ---

    @Test
    void computeForSource_costSegregationHighFirstYear_largerDeduction() {
        // Cost seg typically front-loads depreciation with large year-1 deduction
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("36000"))
                .annualOperatingExpenses(new BigDecimal("6000"))
                .annualPropertyTax(new BigDecimal("3000"))
                .annualMortgageInterest(BigDecimal.ZERO)
                .depreciationByYear(Map.of(
                        2030, new BigDecimal("50000"),  // large cost seg first-year deduction
                        2031, new BigDecimal("8000")    // normal subsequent year
                ))
                .taxTreatment("rental_active_reps")
                .build();

        var resultYear1 = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 80000, BigDecimal.ZERO, calculator);

        // 36000 - 6000 - 3000 - 0 - 50000 = -23000
        assertThat(resultYear1.netTaxableIncome()).isCloseTo(-23000.0, within(0.01));

        var resultYear2 = RentalIncomeHelper.computeForSource(
                source, 1, 2031, 80000, BigDecimal.ZERO, calculator);

        // 36000 * 1.03 = 37080; 37080 - 6000 - 3000 - 0 - 8000 = 20080
        assertThat(resultYear2.netTaxableIncome()).isCloseTo(20080.0, within(1.0));
    }

    // --- First year vs later year inflation compounding ---

    @Test
    void computeForSource_firstYear_noInflationApplied() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("24000"))
                .inflationRate(new BigDecimal("0.05"))
                .annualOperatingExpenses(BigDecimal.ZERO)
                .annualPropertyTax(BigDecimal.ZERO)
                .annualMortgageInterest(BigDecimal.ZERO)
                .depreciationByYear(null)
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 0, 2030, 50000, BigDecimal.ZERO, calculator);

        // yearIndex 0: (1.05)^0 = 1, gross = 24000
        assertThat(result.netTaxableIncome()).isCloseTo(24000.0, within(0.01));
    }

    @Test
    void computeForSource_laterYear_inflationCompoundsCorrectly() {
        var source = sourceBuilder()
                .annualAmount(new BigDecimal("24000"))
                .inflationRate(new BigDecimal("0.05"))
                .annualOperatingExpenses(BigDecimal.ZERO)
                .annualPropertyTax(BigDecimal.ZERO)
                .annualMortgageInterest(BigDecimal.ZERO)
                .depreciationByYear(null)
                .build();

        var result = RentalIncomeHelper.computeForSource(
                source, 3, 2033, 50000, BigDecimal.ZERO, calculator);

        // yearIndex 3: 24000 * (1.05)^3 = 24000 * 1.157625 = 27783.0
        assertThat(result.netTaxableIncome()).isCloseTo(27783.0, within(1.0));
    }

    // --- nullSafe helper ---

    @Test
    void nullSafe_nullInput_returnsZero() {
        assertThat(RentalIncomeHelper.nullSafe(null)).isEqualTo(0.0);
    }

    @Test
    void nullSafe_nonNullInput_returnsDoubleValue() {
        assertThat(RentalIncomeHelper.nullSafe(new BigDecimal("123.45"))).isCloseTo(123.45, within(0.001));
    }

    // --- Builder helper for test readability ---

    private static SourceBuilder sourceBuilder() {
        return new SourceBuilder();
    }

    private static class SourceBuilder {
        private BigDecimal annualAmount = BigDecimal.ZERO;
        private BigDecimal inflationRate = new BigDecimal("0.03");
        private BigDecimal annualOperatingExpenses;
        private BigDecimal annualMortgageInterest;
        private BigDecimal annualMortgagePrincipal;
        private BigDecimal annualPropertyTax;
        private Map<Integer, BigDecimal> depreciationByYear;
        private String taxTreatment = "rental_passive";

        SourceBuilder annualAmount(BigDecimal val) { this.annualAmount = val; return this; }
        SourceBuilder inflationRate(BigDecimal val) { this.inflationRate = val; return this; }
        SourceBuilder annualOperatingExpenses(BigDecimal val) { this.annualOperatingExpenses = val; return this; }
        SourceBuilder annualMortgageInterest(BigDecimal val) { this.annualMortgageInterest = val; return this; }
        SourceBuilder annualMortgagePrincipal(BigDecimal val) { this.annualMortgagePrincipal = val; return this; }
        SourceBuilder annualPropertyTax(BigDecimal val) { this.annualPropertyTax = val; return this; }
        SourceBuilder depreciationByYear(Map<Integer, BigDecimal> val) { this.depreciationByYear = val; return this; }
        SourceBuilder taxTreatment(String val) { this.taxTreatment = val; return this; }

        ProjectionIncomeSourceInput build() {
            return new ProjectionIncomeSourceInput(
                    UUID.randomUUID(),
                    "Test Rental",
                    IncomeSourceType.RENTAL_PROPERTY,
                    annualAmount,
                    65,
                    null,
                    inflationRate,
                    false,
                    taxTreatment,
                    annualOperatingExpenses,
                    annualMortgageInterest,
                    annualMortgagePrincipal,
                    annualPropertyTax,
                    null,
                    depreciationByYear
            );
        }
    }
}
