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
 * The spending-withdrawal step inside conversion scoring
 * ({@link ConversionSimulator#simulateForFraction}) must honour the withdrawal-order strings
 * production actually supplies, which are enum wire tokens ("taxable_first", "traditional_first",
 * ...) rather than pool name lists.
 *
 * <p><strong>Regression guard.</strong> The simulator used to split the order on commas and match
 * each token against a pool name, while every production caller passed a wire token
 * ({@code OptimizationContextBuilder} defaults to {@code "taxable_first"}, which
 * {@code JointConversionSearch} forwards into the optimizer's assumptions). A token like
 * {@code "taxable_first"} matched no pool, so the ordered strategy's switch fell to its
 * default-skip arm: for age >= 59.5 non-dynamic-sequencing runs the spending withdrawal drained
 * nothing and accrued zero withdrawal tax. The order is now resolved through
 * {@code WithdrawalOrder.fromString(...).drawSequence()}.
 *
 * <p>Fixture: one year, retirement age 65 (past the early-withdrawal proxy, before RMD age 75),
 * zero conversion fraction and no rentals, so the only balance movement is growth plus the
 * spending withdrawal. {@code lifetimeTax} therefore equals the withdrawal tax alone. The
 * withdrawal tax is accounted but not debited from the pools, so pool balances reflect the draws
 * only.
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
    void simulateForFraction_taxableFirstEnumToken_drawsTheWholeNeedFromTaxable() {
        var result = simulateOneYear("taxable_first", AMPLE_TAXABLE);

        assertThat(result.taxableBalance()[0])
                .isEqualTo(AMPLE_TAXABLE * GROWTH - ESSENTIAL_FLOOR, offset(1e-6));
        assertThat(result.traditionalBalance()[0]).isEqualTo(INIT_TRADITIONAL * GROWTH, offset(1e-6));
        assertThat(result.rothBalance()[0]).isEqualTo(INIT_ROTH * GROWTH, offset(1e-6));
    }

    @Test
    void simulateForFraction_taxableFirstEnumTokenWithThinTaxable_tapsTraditionalAndTaxesIt() {
        var result = simulateOneYear("taxable_first", THIN_TAXABLE);

        double fromTraditional = ESSENTIAL_FLOOR - THIN_TAXABLE * GROWTH;
        assertThat(result.taxableBalance()[0]).isEqualTo(0.0, offset(1e-6));
        assertThat(result.traditionalBalance()[0])
                .isEqualTo(INIT_TRADITIONAL * GROWTH - fromTraditional, offset(1e-6));
        assertThat(result.rothBalance()[0]).isEqualTo(INIT_ROTH * GROWTH, offset(1e-6));
        assertThat(result.lifetimeTax()).isEqualTo(fromTraditional * TAX_RATE, offset(1e-6));
    }

    @Test
    void simulateForFraction_traditionalFirstEnumToken_drawsTraditionalBeforeTaxable() {
        var result = simulateOneYear("traditional_first", AMPLE_TAXABLE);

        assertThat(result.traditionalBalance()[0])
                .isEqualTo(INIT_TRADITIONAL * GROWTH - ESSENTIAL_FLOOR, offset(1e-6));
        assertThat(result.taxableBalance()[0]).isEqualTo(AMPLE_TAXABLE * GROWTH, offset(1e-6));
        assertThat(result.lifetimeTax()).isEqualTo(ESSENTIAL_FLOOR * TAX_RATE, offset(1e-6));
    }

    @Test
    void simulateForFraction_rothFirstEnumToken_drawsRothBeforeTaxable() {
        var result = simulateOneYear("roth_first", AMPLE_TAXABLE);

        assertThat(result.rothBalance()[0]).isEqualTo(INIT_ROTH * GROWTH - ESSENTIAL_FLOOR, offset(1e-6));
        assertThat(result.taxableBalance()[0]).isEqualTo(AMPLE_TAXABLE * GROWTH, offset(1e-6));
        assertThat(result.lifetimeTax()).isEqualTo(0.0, offset(1e-6));
    }

    @Test
    void simulateForFraction_proRataEnumToken_drawsTaxableFirst() {
        // Conversion scoring has no proportional mode: pro-rata resolves to the taxable-first
        // sequence, matching how the Monte Carlo trial path treats it.
        var result = simulateOneYear("pro_rata", AMPLE_TAXABLE);

        assertThat(result.taxableBalance()[0])
                .isEqualTo(AMPLE_TAXABLE * GROWTH - ESSENTIAL_FLOOR, offset(1e-6));
        assertThat(result.traditionalBalance()[0]).isEqualTo(INIT_TRADITIONAL * GROWTH, offset(1e-6));
    }

    @Test
    void simulateForFraction_legacyCommaListOrder_fallsBackToTaxableFirst() {
        // Persisted scenarios may still hold the pre-enum comma-list form. WithdrawalOrder.fromString
        // maps anything unrecognized to taxable-first, which is how the rest of the engine has always
        // treated these strings -- the draw must still happen.
        var result = simulateOneYear("roth,taxable,traditional", AMPLE_TAXABLE);

        assertThat(result.taxableBalance()[0])
                .isEqualTo(AMPLE_TAXABLE * GROWTH - ESSENTIAL_FLOOR, offset(1e-6));
        assertThat(result.rothBalance()[0]).isEqualTo(INIT_ROTH * GROWTH, offset(1e-6));
    }
}
