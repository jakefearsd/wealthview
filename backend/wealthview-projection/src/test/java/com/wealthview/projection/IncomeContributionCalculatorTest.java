package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.household.HouseholdContext;

import static org.assertj.core.api.Assertions.assertThat;

class IncomeContributionCalculatorTest {

    private IncomeContributionCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new IncomeContributionCalculator();
    }

    private ProjectionIncomeSourceInput source(String name, String amount, int startAge,
                                                Integer endAge, String inflationRate) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), name, IncomeSourceType.OTHER,
                new BigDecimal(amount), startAge, endAge,
                new BigDecimal(inflationRate), false,
                "taxable",
                null, null, null, null, null, null);
    }

    private ProjectionIncomeSourceInput oneTimeSource(String name, String amount, int startAge) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), name, IncomeSourceType.OTHER,
                new BigDecimal(amount), startAge, startAge + 1,
                new BigDecimal("0.02"), true,
                "taxable",
                null, null, null, null, null, null);
    }

    @Test
    void compute_noSources_returnsZero() {
        var result = calculator.compute(List.of(), 67, 2, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void compute_nullSources_returnsZero() {
        var result = calculator.compute(null, 67, 2, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void compute_singleSourceActive_returnsAmount() {
        var sources = List.of(source("SS", "30000", 65, null, "0"));

        var result = calculator.compute(sources, 67, 1, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    void compute_ageBeforeStartAge_returnsZero() {
        var sources = List.of(source("SS", "30000", 67, null, "0"));

        var result = calculator.compute(sources, 65, 1, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void compute_ageAtEndAge_halvesAmount() {
        var sources = List.of(source("SS", "30000", 65, 70, "0"));

        var result = calculator.compute(sources, 70, 1, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("15000"));
    }

    @Test
    void compute_ageAfterEndAge_returnsZero() {
        var sources = List.of(source("SS", "30000", 65, 70, "0"));

        var result = calculator.compute(sources, 71, 1, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void compute_nullEndAge_alwaysActive() {
        var sources = List.of(source("SS", "30000", 65, null, "0"));

        var result = calculator.compute(sources, 95, 1, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    void compute_withInflation_appliesCompounding() {
        // yearsInRetirement=4 -> 3 compounding years: 30000 * (1.02)^3
        var sources = List.of(source("SS", "30000", 65, null, "0.02"));

        var result = calculator.compute(sources, 68, 4, BigDecimal.ZERO);

        var expected = new BigDecimal("30000")
                .multiply(BigDecimal.ONE.add(new BigDecimal("0.02")).pow(3))
                .setScale(4, RoundingMode.HALF_UP);
        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void compute_firstYearRetirement_halvesAtStartAge() {
        var sources = List.of(source("SS", "30000", 65, null, "0.02"));

        var result = calculator.compute(sources, 65, 1, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("15000"));
    }

    @Test
    void compute_zeroInflation_returnsNominal() {
        var sources = List.of(source("SS", "30000", 65, null, "0"));

        var result = calculator.compute(sources, 70, 5, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    void compute_multipleSources_sumsAll() {
        var sources = List.of(
                source("SS", "30000", 65, null, "0"),
                source("Pension", "20000", 60, null, "0"));

        var result = calculator.compute(sources, 67, 1, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    void compute_multipleSources_differentRates_inflatesIndependently() {
        var sources = List.of(
                source("SS", "20000", 60, null, "0.02"),
                source("Rental", "10000", 60, null, "0.03"));

        var result = calculator.compute(sources, 67, 2, BigDecimal.ZERO);

        // Year 2: SS = 20000 * 1.02^1 = 20400, Rental = 10000 * 1.03^1 = 10300
        var expected = new BigDecimal("20400.0000").add(new BigDecimal("10300.0000"));
        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void compute_oneTimeSource_noInflation() {
        // One-time sources should not have inflation applied, even if inflationRate is set
        var sources = List.of(oneTimeSource("Bonus", "50000", 65));

        var result = calculator.compute(sources, 65, 5, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    void compute_oneTimeSource_atStartAge_notHalved() {
        var sources = List.of(oneTimeSource("Inheritance", "50000", 65));

        var result = calculator.compute(sources, 65, 1, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    void compute_oneTimeSource_atEndAge_returnsZero() {
        var sources = List.of(oneTimeSource("Inheritance", "50000", 65));

        var result = calculator.compute(sources, 66, 2, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void compute_oneTimeSource_yearAfterStart_returnsZero() {
        var sources = List.of(oneTimeSource("Inheritance", "50000", 65));

        var result = calculator.compute(sources, 67, 3, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void compute_oneTimeSource_withInflation_ignoresInflation() {
        // oneTimeSource helper sets inflationRate=0.02 — should still return base amount
        var sources = List.of(oneTimeSource("Bonus", "50000", 65));

        var result = calculator.compute(sources, 65, 5, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    void compute_midRangeAge_fullAmount() {
        var sources = List.of(source("SS", "30000", 65, 70, "0"));

        var result = calculator.compute(sources, 67, 1, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    void compute_rentalPropertyWithExpenses_returnsNetAmount() {
        var rental = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental", IncomeSourceType.RENTAL_PROPERTY,
                new BigDecimal("24000"), 60, null,
                new BigDecimal("0"), false, "active_participation",
                new BigDecimal("3600"),   // operating expenses
                Map.of(2026, new BigDecimal("9600")),   // mortgage interest
                null,                     // mortgagePrincipalByYear
                new BigDecimal("5000"),   // property tax
                null, null);

        var result = calculator.compute(List.of(rental), 65, 1, BigDecimal.ZERO, 2026, null);

        // NET = 24000 - (3600 + 9600 + 5000) = 24000 - 18200 = 5800
        assertThat(result).isEqualByComparingTo(new BigDecimal("5800"));
    }

    @Test
    void compute_rentalPropertyNoExpenses_returnsGrossAmount() {
        var rental = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental", IncomeSourceType.RENTAL_PROPERTY,
                new BigDecimal("24000"), 60, null,
                new BigDecimal("0"), false, "active_participation",
                null, null, null, null, null, null);

        var result = calculator.compute(List.of(rental), 65, 1, BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("24000"));
    }

    // === Household task 7 (T5-review, spec §1): owner-age windows in the accumulation phase ===

    private static final HouseholdContext AGE_GAP_HOUSEHOLD = HouseholdContext.of(1958, 90, 1970, 90, 2070);

    private ProjectionIncomeSourceInput sourceWithOwner(String name, String amount, int startAge,
                                                        Integer endAge, String owner) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), name, IncomeSourceType.OTHER,
                new BigDecimal(amount), startAge, endAge, BigDecimal.ZERO, false, "taxable",
                null, null, null, null, null, null, owner, BigDecimal.ONE);
    }

    @Test
    void compute_household_spouseOwnedSourceEvaluatesAgainstSpouseAge_notPrimaryAge() {
        // Spouse (born 1970) is only 53 in 2023 even though the primary (1958) is 65 -- the
        // spouse-owned source (start_age 65) must not be active yet.
        var sources = List.of(sourceWithOwner("Spouse pension", "24000", 65, null, "spouse"));

        var result = calculator.compute(sources, 65, 1, BigDecimal.ZERO, 2023, AGE_GAP_HOUSEHOLD);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void compute_household_spouseOwnedSourceActivatesAtSpouseOwnAge() {
        var sources = List.of(sourceWithOwner("Spouse pension", "24000", 65, null, "spouse"));

        // 2036: spouse (born 1970) is 66 -- past the boundary, full amount.
        var result = calculator.compute(sources, 78, 1, BigDecimal.ZERO, 2036, AGE_GAP_HOUSEHOLD);

        assertThat(result).isEqualByComparingTo(new BigDecimal("24000"));
    }

    @Test
    void compute_household_primaryOwnedSourceUnaffected() {
        var sources = List.of(sourceWithOwner("Primary pension", "24000", 65, null, "primary"));

        var result = calculator.compute(sources, 66, 1, BigDecimal.ZERO, 2024, AGE_GAP_HOUSEHOLD);

        assertThat(result).isEqualByComparingTo(new BigDecimal("24000"));
    }

    @Test
    void compute_householdNull_spouseOwnedSourceFallsBackToUniformAge() {
        // No household threaded (household == null) reproduces the pre-task-7, age-uniform
        // behavior: the source is evaluated against the `age` param regardless of `owner`.
        var sources = List.of(sourceWithOwner("Spouse pension", "24000", 65, null, "spouse"));

        var result = calculator.compute(sources, 66, 1, BigDecimal.ZERO, 2024, null);

        assertThat(result).isEqualByComparingTo(new BigDecimal("24000"));
    }

    // --- Mortgage debt service follows the amortization schedule ---

    private static ProjectionIncomeSourceInput mortgagedRental(Map<Integer, BigDecimal> interest,
                                                              Map<Integer, BigDecimal> principal) {
        return mortgagedRental(interest, principal, BigDecimal.ZERO);
    }

    /** Rent indexed at {@code rentGrowth}; matching scenario inflation keeps its real gross at 36,000. */
    private static ProjectionIncomeSourceInput mortgagedRental(Map<Integer, BigDecimal> interest,
                                                              Map<Integer, BigDecimal> principal,
                                                              BigDecimal rentGrowth) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental", IncomeSourceType.RENTAL_PROPERTY,
                new BigDecimal("36000"), 60, null,
                rentGrowth, false, "active_participation",
                new BigDecimal("4000"), interest, principal, null, null, null);
    }

    @Test
    void compute_rentalAfterMortgagePayoff_chargesNoDebtService() {
        var rental = mortgagedRental(Map.of(2030, new BigDecimal("1000")), Map.of(2030, new BigDecimal("11000")));

        var result = calculator.compute(List.of(rental), 65, 1, BigDecimal.ZERO, 2031, null);

        assertThat(result).isEqualByComparingTo("32000");
    }

    @Test
    void compute_rentalMortgage_chargesThatYearsScheduledPrincipalAndInterest() {
        var rental = mortgagedRental(
                Map.of(2030, new BigDecimal("9000"), 2031, new BigDecimal("8800")),
                Map.of(2030, new BigDecimal("3000"), 2031, new BigDecimal("3200")));

        var result = calculator.compute(List.of(rental), 65, 1, BigDecimal.ZERO, 2031, null);

        // 36000 - 4000 - (8800 + 3200)
        assertThat(result).isEqualByComparingTo("20000");
    }

    @Test
    void compute_rentalMortgage_deflatesTheFixedNominalPaymentLikeANoColaSource() {
        var inflation = new BigDecimal("0.03");
        var rental = mortgagedRental(Map.of(2028, new BigDecimal("9000")), Map.of(2028, new BigDecimal("3000")),
                inflation);

        var result = calculator.compute(List.of(rental), 65, 3, inflation, 2028, null);

        // The 12000 nominal payment two years after the base year is worth 12000 / 1.03^2 today,
        // exactly as a 12000 pension with no COLA would be.
        var noColaPension = calculator.compute(List.of(source("Pension", "12000", 60, null, "0")), 65, 3, inflation);
        assertThat(result).isEqualByComparingTo(new BigDecimal("32000").subtract(noColaPension));
        assertThat(noColaPension).isLessThan(new BigDecimal("12000"));
    }
}
