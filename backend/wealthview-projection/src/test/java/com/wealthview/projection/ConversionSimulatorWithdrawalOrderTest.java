package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

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
 * Characterization of the spending-withdrawal step inside conversion scoring
 * ({@link ConversionSimulator#simulateForFraction}) for the withdrawal-order strings production
 * actually supplies.
 *
 * <p><strong>Pinned defect.</strong> {@code ConversionSimulator.parseWithdrawalOrder()} splits the
 * configured order on commas and matches each token against the pool names
 * ("taxable"/"traditional"/"roth"), but every production caller passes an enum WIRE TOKEN:
 * {@code OptimizationContextBuilder} defaults to {@code "taxable_first"} and hands it to
 * {@code JointConversionSearch}, which forwards it into the optimizer's assumptions. A single
 * token like {@code "taxable_first"} matches no pool, so the ordered strategy's switch falls to
 * its {@code default -> skip} arm: for age >= 59.5 non-dynamic-sequencing runs the spending
 * withdrawal drains NOTHING and accrues ZERO withdrawal tax. The tests below pin that no-op so the
 * fix has something to flip.
 *
 * <p>Fixture: one year, retirement age 65 (past the early-withdrawal proxy, before RMD age 75),
 * zero conversion fraction and no rentals, so the only balance movement is growth plus the
 * spending withdrawal. {@code lifetimeTax} therefore equals the withdrawal tax alone.
 */
class ConversionSimulatorWithdrawalOrderTest {

    private static final double RETURN_MEAN = 0.05;
    private static final double GROWTH = 1 + RETURN_MEAN;
    private static final double INIT_TRADITIONAL = 1_000_000;
    private static final double INIT_ROTH = 50_000;
    private static final double AMPLE_TAXABLE = 200_000;
    private static final double THIN_TAXABLE = 10_000;
    private static final double ESSENTIAL_FLOOR = 40_000;
    private static final double TAX_RATE = 0.20;
    private static final int BIRTH_YEAR = 1960; // RMD start age 75
    private static final int RETIREMENT_AGE = 65; // >= RetirementAges.EARLY_WITHDRAWAL_AGE, < 75

    private FederalTaxCalculator flatRateTaxCalculator() {
        var calc = mock(FederalTaxCalculator.class);
        when(calc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(inv -> {
                    BigDecimal income = inv.getArgument(0);
                    return income.compareTo(BigDecimal.ZERO) <= 0
                            ? BigDecimal.ZERO
                            : income.multiply(BigDecimal.valueOf(TAX_RATE));
                });
        return calc;
    }

    private SimResult simulateOneYear(String withdrawalOrder, double initTaxable) {
        int endAge = RETIREMENT_AGE + 1;
        var rentalCalc = new RentalAdjustmentCalculator(
                List.of(), new RentalLossCalculator(), BIRTH_YEAR, RETIREMENT_AGE);
        var config = new RothConversionConfig(
                INIT_TRADITIONAL, INIT_ROTH, initTaxable,
                new double[]{0.0}, new double[]{0.0},
                BIRTH_YEAR, RETIREMENT_AGE, endAge, 5,
                0.22, 0.12, RETURN_MEAN,
                ESSENTIAL_FLOOR,
                FilingStatus.SINGLE, flatRateTaxCalculator(),
                withdrawalOrder, 0.10, 0.0,
                endAge - RETIREMENT_AGE, RmdCalculator.rmdStartAge(BIRTH_YEAR),
                rentalCalc);

        return new ConversionSimulator(config, 0.0).simulateForFraction(0.0);
    }

    @Test
    void selectWithdrawalStrategy_enumTokenOrder_currentlySkipsAllPools_bugPin() {
        var result = simulateOneYear("taxable_first", AMPLE_TAXABLE);

        // Correct behavior would be taxable = AMPLE_TAXABLE * GROWTH - ESSENTIAL_FLOOR. The
        // "taxable_first" token matches no pool name, so nothing is drawn at all.
        assertThat(result.taxableBalance()[0]).isEqualTo(AMPLE_TAXABLE * GROWTH, offset(1e-6));
        assertThat(result.traditionalBalance()[0]).isEqualTo(INIT_TRADITIONAL * GROWTH, offset(1e-6));
        assertThat(result.rothBalance()[0]).isEqualTo(INIT_ROTH * GROWTH, offset(1e-6));
    }

    @Test
    void selectWithdrawalStrategy_enumTokenOrderWithThinTaxable_currentlyAccruesNoWithdrawalTax_bugPin() {
        var result = simulateOneYear("taxable_first", THIN_TAXABLE);

        // Taxable growth (10,500) cannot cover the 40,000 floor, so a working sequence would tap
        // traditional and owe tax on it. The skipped switch leaves every pool whole and tax at zero.
        assertThat(result.traditionalBalance()[0]).isEqualTo(INIT_TRADITIONAL * GROWTH, offset(1e-6));
        assertThat(result.lifetimeTax()).isEqualTo(0.0, offset(1e-6));
    }

    @Test
    void selectWithdrawalStrategy_commaListOrder_drawsFromTaxable() {
        // Control: the ordered strategy itself works. Only the token FORMAT is broken, which is why
        // the defect never surfaced -- every unit test passed the comma-list form.
        var result = simulateOneYear("taxable,traditional,roth", AMPLE_TAXABLE);

        assertThat(result.taxableBalance()[0])
                .isEqualTo(AMPLE_TAXABLE * GROWTH - ESSENTIAL_FLOOR, offset(1e-6));
        assertThat(result.traditionalBalance()[0]).isEqualTo(INIT_TRADITIONAL * GROWTH, offset(1e-6));
    }
}
