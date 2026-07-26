package com.wealthview.projection;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import com.wealthview.projection.testutil.FlatTaxStubs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Audit C4 (RMD proceeds vanishing): a forced RMD must debit traditional by the gross amount,
 * tax it, and credit the AFTER-TAX remainder to the taxable pool -- not let it evaporate. Exercises
 * {@link ConversionSimulator#simulateForFraction} directly (package-private, same package) with a
 * one-year fixture pinned exactly at the RMD start age so the whole year's balance movement is
 * attributable to growth + the RMD step alone (no conversions -- blocked by {@code age >=
 * rmdStartAge} -- and no spending withdrawal -- {@code essentialFloor = 0}).
 */
class ConversionSimulatorRmdConservationTest {

    private static final double RETURN_MEAN = 0.05;
    private static final double INIT_TRADITIONAL = 1_000_000;
    private static final double INIT_TAXABLE = 200_000;
    private static final double INIT_ROTH = 50_000;
    private static final int BIRTH_YEAR = 1950; // RMD start age 73 (born before 1960)
    private static final int RETIREMENT_AGE = 73; // == rmdStartAge: first sim year IS an RMD year

    private FederalTaxCalculator flatRateTaxCalculator() {
        // Only the 3-arg computeTax overload is exercised here (conversions are blocked, so no
        // bracket-ceiling lookup happens) -- FlatTaxStubs.flat20() reproduces the original unscaled
        // formula exactly; see its javadoc for which shape matches which call site.
        return FlatTaxStubs.flat20();
    }

    private SimResult simulateOneYear() {
        int endAge = RETIREMENT_AGE + 1;
        var rentalCalc = new RentalAdjustmentCalculator(
                List.of(), new RentalLossCalculator(), BIRTH_YEAR, RETIREMENT_AGE);
        var config = new RothConversionConfig(
                INIT_TRADITIONAL, INIT_ROTH, INIT_TAXABLE,
                new double[]{0.0}, new double[]{0.0},
                BIRTH_YEAR, RETIREMENT_AGE, endAge, 5,
                0.22, 0.12, RETURN_MEAN,
                0.0, // essentialFloor = 0 -> zero spending withdrawal, isolates the RMD step
                FilingStatus.SINGLE, flatRateTaxCalculator(),
                "taxable_first", 0.10, 0.0,
                endAge - RETIREMENT_AGE, RmdCalculator.rmdStartAge(BIRTH_YEAR),
                rentalCalc);

        var simulator = new ConversionSimulator(config, 0.0);
        return simulator.simulateForFraction(0.0);
    }

    @Test
    void simulateForFraction_rmdYear_traditionalDecreasesByExactlyTheRmd() {
        var result = simulateOneYear();

        double rmd = result.projectedRmd()[0];
        assertThat(rmd).as("fixture must actually trigger an RMD").isGreaterThan(0);

        double expectedTraditional = INIT_TRADITIONAL * (1 + RETURN_MEAN) - rmd;
        assertThat(result.traditionalBalance()[0]).isEqualTo(expectedTraditional, offset(1e-6));
    }

    @Test
    void simulateForFraction_rmdYear_creditsAfterTaxRemainderToTaxable() {
        var result = simulateOneYear();

        double rmd = result.projectedRmd()[0];
        // Flat 20% mock, zero base income: incremental tax on the RMD is exactly rmd * 0.20.
        double rmdTax = rmd * 0.20;
        double expectedTaxable = INIT_TAXABLE * (1 + RETURN_MEAN) + (rmd - rmdTax);

        assertThat(result.taxableBalance()[0]).isEqualTo(expectedTaxable, offset(1e-6));

        // Conservation: RMD == tax + credited remainder, exactly.
        double creditedRemainder = result.taxableBalance()[0] - INIT_TAXABLE * (1 + RETURN_MEAN);
        assertThat(creditedRemainder + rmdTax).isEqualTo(rmd, offset(1e-6));
    }

    @Test
    void simulateForFraction_rmdYear_endingWealthReflectsOnlyTaxLeakageNotWholeRmd() {
        // Before the fix, the whole gross RMD vanished from total wealth (debited from traditional,
        // credited nowhere) -- total wealth would have dropped by growth minus the FULL RMD. After
        // the fix, only the tax actually leaves the three-pool system; the after-tax remainder just
        // moves pools.
        var result = simulateOneYear();

        double rmd = result.projectedRmd()[0];
        double rmdTax = rmd * 0.20;

        double startWealth = INIT_TRADITIONAL + INIT_TAXABLE + INIT_ROTH;
        double endWealth = result.traditionalBalance()[0] + result.taxableBalance()[0] + result.rothBalance()[0];

        assertThat(endWealth).isEqualTo(startWealth * (1 + RETURN_MEAN) - rmdTax, offset(1e-6));
        // The old (buggy) formula this replaces: startWealth*(1+returnMean) - rmd. Confirm we are
        // NOT on that formula (rmd > rmdTax whenever the marginal rate is below 100%).
        double buggyEndWealth = startWealth * (1 + RETURN_MEAN) - rmd;
        assertThat(Math.abs(endWealth - buggyEndWealth)).isGreaterThan(1.0);
    }

    @Test
    void simulateForFraction_lifetimeTaxIncludesRmdTaxRegardlessOfCredit() {
        // The credit changes WHERE the after-tax dollars sit; it must not change how much tax was
        // paid (that accounting was already correct before this fix).
        var result = simulateOneYear();

        double rmd = result.projectedRmd()[0];
        double rmdTax = rmd * 0.20;

        assertThat(result.lifetimeTax()).isEqualTo(rmdTax, offset(1e-6));
    }
}
