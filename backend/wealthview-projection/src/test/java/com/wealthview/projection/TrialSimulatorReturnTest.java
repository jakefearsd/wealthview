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
                taxableReturns, traditionalReturns, rothReturns);
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
                flatNoReturn, flatNoReturn, flatNoReturn);
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
                flatNoReturn, flatNoReturn, flatNoReturn);
        double[] income = {0.0, 0.0};
        double[] zero = {0.0, 0.0};
        double[] floors = {50.0, 50.0};
        double[] discretionary = {0.0, 0.0};

        var result = sim.simulateTrial(income, zero, floors, discretionary, 2, config);

        assertThat(result.success()).isTrue();
    }
}
