package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies that {@link TrialSimulator} grows each tax pool at its own per-year return sequence
 * (Task 15: per-pool allocation-driven Monte Carlo returns), and that the cash-reserve down-year
 * decision keys on the balance-weighted portfolio return across the three pools.
 */
class TrialSimulatorReturnTest {

    private final TrialSimulator simulator = new TrialSimulator();

    private static TrialSimulator.SimulationConfig config(
            double initTaxable, double initTraditional, double initRoth,
            int cashReserveYears,
            double[] taxableReturns, double[] traditionalReturns, double[] rothReturns) {
        return new TrialSimulator.SimulationConfig(
                initTaxable, initTraditional, initRoth,
                "taxable_first", null, null, null, 62, null,
                cashReserveYears, 0.0, false,
                taxableReturns, traditionalReturns, rothReturns, Integer.MAX_VALUE,
                initTaxable, null, 0.0);
    }

    @Test
    void simulateTrial_perPoolReturns_growEachPoolIndependently() {
        // 1 year, no withdrawals/income; taxable +10%, traditional +2%, roth +8%.
        // 110 + 102 + 108 = 320.
        var config = config(100, 100, 100, 0,
                new double[]{0.10}, new double[]{0.02}, new double[]{0.08});

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(320.0, within(1e-6));
    }

    @Test
    void simulateTrial_perPoolReturns_compoundIndependentlyAcrossYears() {
        // 2 years, no spend/income. taxable 100 -> 110 -> 121; traditional 100 -> 102 -> 104.04;
        // roth 100 -> 108 -> 116.64. Sum = 341.68.
        var config = config(100, 100, 100, 0,
                new double[]{0.10, 0.10}, new double[]{0.02, 0.02}, new double[]{0.08, 0.08});

        var result = simulator.simulateTrial(
                new double[]{0, 0}, new double[]{0, 0}, new double[]{0, 0}, new double[]{0, 0}, 2, config);

        assertThat(result.finalBalance()).isEqualTo(121.0 + 104.04 + 116.64, within(1e-6));
    }

    @Test
    void simulateTrial_cashReserveDownYear_keysOnBalanceWeightedPortfolioReturn() {
        // Taxable pool is UP (+10%) but traditional is sharply DOWN (-30%); the balance-weighted
        // portfolio return is negative, so the down-year branch draws the year's spending from the
        // cash reserve and leaves the equity pools untouched.
        var config = config(100, 100, 0, 1,
                new double[]{0.10}, new double[]{-0.30}, new double[]{0.0});

        // floor spend = 10 -> cash reserve seeded at 10 (drawn from taxable first).
        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{10}, new double[]{0}, 1, config);

        // taxable: (100 - 10 seeded) * 1.10 = 99 ; traditional: 100 * 0.70 = 70 ; cash: 10 - 10 spent = 0.
        assertThat(result.finalBalance()).isEqualTo(99.0 + 70.0, within(1e-6));
    }

    @Test
    void simulateTrial_essentialFloorUnfundableInAYear_marksNotSuccess() {
        var sim = new TrialSimulator();
        // Tiny portfolio, no income, a floor larger than the portfolio can ever supply → shortfall.
        double[] flatNoReturn = {0.0, 0.0};
        var config = new TrialSimulator.SimulationConfig(
                100.0, 0.0, 0.0, "taxable_first", null,
                null, null, 60, null, 0, 0.0, false,
                flatNoReturn, flatNoReturn, flatNoReturn, Integer.MAX_VALUE,
                100.0, null, 0.0);
        double[] income = {0.0, 0.0};
        double[] zero = {0.0, 0.0};
        double[] floors = {80.0, 80.0};        // year 2 floor (80) unfundable: only ~20 left
        double[] discretionary = {0.0, 0.0};

        var result = sim.simulateTrial(income, zero, floors, discretionary, 2, config);

        assertThat(result.success()).isFalse();
    }

    @Test
    void simulateTrial_floorFundedEveryYear_marksSuccess() {
        var sim = new TrialSimulator();
        double[] flatNoReturn = {0.0, 0.0};
        var config = new TrialSimulator.SimulationConfig(
                1000.0, 0.0, 0.0, "taxable_first", null,
                null, null, 60, null, 0, 0.0, false,
                flatNoReturn, flatNoReturn, flatNoReturn, Integer.MAX_VALUE,
                1000.0, null, 0.0);
        double[] income = {0.0, 0.0};
        double[] zero = {0.0, 0.0};
        double[] floors = {50.0, 50.0};
        double[] discretionary = {0.0, 0.0};

        var result = sim.simulateTrial(income, zero, floors, discretionary, 2, config);

        assertThat(result.success()).isTrue();
    }

    @Test
    void simulateTrial_taxableWithdrawalWithEmbeddedGain_realizesLtcgTax() {
        // 1 year, no growth, no cash reserve, no income. Taxable pool worth 1000 with a 600 basis
        // (40% embedded gain). Spend 500 -> a FIFO half-lot sale realizes gain 500 - 600*(500/1000)
        // = 200. LTCG rate 0.15 -> tax 30, paid taxable-first. Final = (1000 - 500) - 30 = 470
        // (vs 500 with no LTCG tax: the 30 is exactly the extra cash the capital-gains tax removes).
        double[] flatNoReturn = {0.0};
        var config = new TrialSimulator.SimulationConfig(
                1000.0, 0.0, 0.0, "taxable_first", null,
                null, null, 62, null, 0, 0.0, false,
                flatNoReturn, flatNoReturn, flatNoReturn, Integer.MAX_VALUE,
                600.0, new double[]{0.15}, 0.0);

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{500}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(470.0, within(1e-6));
    }

    @Test
    void simulateTrial_dividendYield_drainsQualifiedDividendTax() {
        // 1 year, taxable +10%, dividend yield 2%, no withdrawal. The pool still grows to 1100
        // (dividend booked as residual), but the 2% dividend (20) is qualified-dividend income taxed
        // at the 0.15 LTCG rate -> 3 leaves the portfolio. Final = 1100 - 3 = 1097 (dividend drag).
        var config = new TrialSimulator.SimulationConfig(
                1000.0, 0.0, 0.0, "taxable_first", null,
                null, null, 62, null, 0, 0.0, false,
                new double[]{0.10}, new double[]{0.0}, new double[]{0.0}, Integer.MAX_VALUE,
                1000.0, new double[]{0.15}, 0.02);

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(1097.0, within(1e-6));
    }

    @Test
    void simulateTrial_rmdAgeReached_forcesTraditionalDistributionToTaxable() {
        // 1 year, no growth, no spending: taxable=0, traditional=100000, roth=0,
        // marginalRateByYear={0.20}, rmdStartAge=75, retirementAge=75 (age year0 = 75).
        // divisor(75)=24.6 -> rmd = 100000 / 24.6 = 4065.0406504065...
        // Nothing is drawn for spending, so extra = rmd (capped at the 100000 traditional balance).
        // taxExtra = rmd * 0.20; pools: traditional = 100000 - rmd; taxable += rmd - taxExtra.
        // finalBalance = (100000 - rmd) + (rmd - taxExtra) = 100000 - taxExtra ~= 99186.99
        // (the tax on the forced distribution is the only amount that leaves the portfolio).
        double[] flatNoReturn = {0.0};
        var config = new TrialSimulator.SimulationConfig(
                0.0, 100_000.0, 0.0, "taxable_first", new double[]{0.20},
                null, null, 75, null, 0, 0.0, false,
                flatNoReturn, flatNoReturn, flatNoReturn, 75,
                0.0, null, 0.0);

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(99186.99, within(0.01));
    }
}
