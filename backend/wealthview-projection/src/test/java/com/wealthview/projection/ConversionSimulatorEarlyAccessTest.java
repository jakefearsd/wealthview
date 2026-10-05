package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.household.AgeMilestones;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1a: the conversion simulator's two pre-59½ rules (taxable-only spending and the
 * conversion-tax affordability cap) key off {@code RothConversionConfig.earlyAccessAge}. With a
 * March birth (early-access age 59), the age-59 year is no longer "early".
 *
 * <p>Fixture: one year at age 59, flat 20% tax, a mocked bracket ceiling of $100,000, no target
 * balance (0 disables the RMD-target cap), so the conversion is sized purely by bracket space and
 * the affordability rule.
 */
class ConversionSimulatorEarlyAccessTest {

    private static final double RETURN_MEAN = 0.0;
    private static final double INIT_TRADITIONAL = 1_000_000;
    private static final double THIN_TAXABLE = 10_000;
    private static final double AMPLE_TAXABLE = 200_000;
    private static final double ESSENTIAL_FLOOR = 40_000;
    private static final int BIRTH_YEAR = 1960;
    private static final int AGE = 59;

    private FederalTaxCalculator flatTwentyPercent() {
        var calc = mock(FederalTaxCalculator.class);
        when(calc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(inv -> {
                    BigDecimal income = inv.getArgument(0);
                    return income.signum() <= 0 ? BigDecimal.ZERO : income.multiply(new BigDecimal("0.20"));
                });
        when(calc.computeMaxIncomeForBracket(any(BigDecimal.class), anyInt(), any(FilingStatus.class),
                any(BigDecimal.class))).thenReturn(new BigDecimal("100000"));
        return calc;
    }

    private SimResult simulate(String order, double initTaxable, double fraction, int earlyAccessAge) {
        int endAge = AGE + 1;
        var config = new RothConversionConfig(
                INIT_TRADITIONAL, 0.0, initTaxable,
                new double[]{0.0}, new double[]{0.0},
                BIRTH_YEAR, AGE, endAge, 5,
                0.22, 0.12, RETURN_MEAN,
                ESSENTIAL_FLOOR,
                FilingStatus.SINGLE, flatTwentyPercent(),
                order, 0.10, 0.0,
                endAge - AGE, RmdCalculator.rmdStartAge(BIRTH_YEAR),
                new RentalAdjustmentCalculator(List.of(), new RentalLossCalculator(), BIRTH_YEAR, AGE),
                earlyAccessAge);
        return new ConversionSimulator(config, 0.0).simulateForFraction(fraction);
    }

    @Test
    void simulateForFraction_age59WithEarlyAccessAge59_spendingUsesOrderedStrategy() {
        var result = simulate("traditional_first", AMPLE_TAXABLE, 0.0, 59);

        assertThat(result.traditionalBalance()[0]).isEqualTo(INIT_TRADITIONAL - ESSENTIAL_FLOOR, offset(1e-6));
        assertThat(result.taxableBalance()[0]).isEqualTo(AMPLE_TAXABLE, offset(1e-6));
    }

    @Test
    void simulateForFraction_age59WithLegacyEarlyAccessAge_spendingIsTaxableOnly() {
        var result = simulate("traditional_first", AMPLE_TAXABLE, 0.0, AgeMilestones.LEGACY_EARLY_ACCESS_AGE);

        assertThat(result.traditionalBalance()[0]).isEqualTo(INIT_TRADITIONAL, offset(1e-6));
        assertThat(result.taxableBalance()[0]).isEqualTo(AMPLE_TAXABLE - ESSENTIAL_FLOOR, offset(1e-6));
    }

    @Test
    void simulateForFraction_thinTaxableAge59_affordabilityCapOnlyAppliesBeforeEarlyAccess() {
        var capped = simulate("taxable_first", THIN_TAXABLE, 1.0, AgeMilestones.LEGACY_EARLY_ACCESS_AGE);
        var uncapped = simulate("taxable_first", THIN_TAXABLE, 1.0, 59);

        // Legacy: taxable (10k) minus the essential need (40k) is <= 0, so the pre-59 1/2
        // affordability cap zeroes the conversion. Early-access 59: the cap is off, so the full
        // bracket space converts.
        assertThat(capped.conversionByYear()[0]).isEqualTo(0.0, offset(1e-6));
        assertThat(uncapped.conversionByYear()[0]).isGreaterThan(0.0);
    }
}
