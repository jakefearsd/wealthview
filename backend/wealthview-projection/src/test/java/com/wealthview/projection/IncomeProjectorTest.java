package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Rental mortgage debt service in the Monte Carlo income precompute: each projected year charges
 * that calendar year's scheduled principal and interest (none after payoff), deflated to today's
 * dollars as a fixed-nominal outflow.
 */
class IncomeProjectorTest {

    private static final int BIRTH_YEAR = 1961;
    private static final double INFLATION = 0.03;

    /** Retires at 65 in 2026, which is also the base year: index y is calendar year 2026 + y. */
    private static IncomeProjector.Context context(int years) {
        return new IncomeProjector.Context(65, years, 0, INFLATION, BIRTH_YEAR, null);
    }

    /** A rental whose rent keeps pace with inflation, so its real gross is a constant 36,000. */
    private static ProjectionIncomeSourceInput rental(Map<Integer, BigDecimal> interest,
                                                      Map<Integer, BigDecimal> principal) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental", IncomeSourceType.RENTAL_PROPERTY,
                new BigDecimal("36000"), 60, null, new BigDecimal("0.03"), false, "rental_passive",
                null, interest, principal, null, null, null);
    }

    @Test
    void computeDeterministic_rentalMortgage_chargesEachYearsDeflatedPaymentAndStopsAtPayoff() {
        var source = rental(
                Map.of(2026, new BigDecimal("9000"), 2027, new BigDecimal("8800")),
                Map.of(2026, new BigDecimal("3000"), 2027, new BigDecimal("3200")));

        var result = IncomeProjector.computeDeterministic(List.of(source), context(3));

        assertThat(result[0].totalIncome()).isCloseTo(36000 - 12000, within(0.01));
        assertThat(result[1].totalIncome()).isCloseTo(36000 - 12000 / 1.03, within(0.01));
        assertThat(result[2].totalIncome()).isCloseTo(36000, within(0.01));
    }

    @Test
    void computeRentalAwareTaxable_rentalMortgage_deductsEachYearsDeflatedInterestUntilPayoff() {
        var withLoan = rental(
                Map.of(2026, new BigDecimal("9000"), 2027, new BigDecimal("8800")),
                Map.of(2026, new BigDecimal("3000"), 2027, new BigDecimal("3200")));
        var withoutLoan = rental(null, null);
        var base = new double[3];

        var loanTaxable = IncomeProjector.computeRentalAwareTaxable(base, List.of(withLoan), context(3));
        var plainTaxable = IncomeProjector.computeRentalAwareTaxable(base, List.of(withoutLoan), context(3));

        assertThat(plainTaxable[0] - loanTaxable[0]).isCloseTo(9000, within(0.01));
        assertThat(plainTaxable[1] - loanTaxable[1]).isCloseTo(8800 / 1.03, within(0.01));
        assertThat(plainTaxable[2] - loanTaxable[2]).isCloseTo(0, within(0.01));
    }
}
