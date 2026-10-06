package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.RentalLossCalculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Roth conversion optimizer's rental adjustment deducts each calendar year's scheduled mortgage
 * interest, deflated from the projection's base year like every other fixed-nominal amount.
 */
class RentalAdjustmentCalculatorTest {

    private static final int BIRTH_YEAR = 1961;
    private static final int RETIREMENT_AGE = 65;
    private static final int BASE_YEAR = 2024;

    private static ProjectionIncomeSourceInput rental(Map<Integer, BigDecimal> interest) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental", IncomeSourceType.RENTAL_PROPERTY,
                new BigDecimal("36000"), 60, null, BigDecimal.ZERO, false, "rental_active_reps",
                null, interest, null, null, null, null);
    }

    private static double adjustment(ProjectionIncomeSourceInput source, int yearIndex) {
        var calculator = new RentalAdjustmentCalculator(List.of(source), new RentalLossCalculator(),
                BIRTH_YEAR, RETIREMENT_AGE, BASE_YEAR, 0.03);
        return calculator.adjustmentForYear(yearIndex, 50000, calculator.initSuspendedLosses());
    }

    @Test
    void adjustmentForYear_rentalMortgage_deductsThatYearsInterestDeflatedFromTheBaseYear() {
        // Year index 1 is calendar year 2027, three years after the 2024 base year.
        var source = rental(Map.of(2026, new BigDecimal("9000"), 2027, new BigDecimal("8800")));

        assertThat(adjustment(source, 1)).isCloseTo(36000 - 8800 / Math.pow(1.03, 3), within(0.01));
    }

    @Test
    void adjustmentForYear_afterMortgagePayoff_deductsNoInterest() {
        var source = rental(Map.of(2026, new BigDecimal("9000")));

        assertThat(adjustment(source, 1)).isCloseTo(36000, within(0.01));
    }
}
