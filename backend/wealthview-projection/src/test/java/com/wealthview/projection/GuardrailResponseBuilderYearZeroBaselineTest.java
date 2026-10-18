package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.household.AgeMilestones;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailResponseBuilderYearZeroBaselineTest {

    private static OptimizationSetup setupWith(TrialStartBalances[] starts) {
        var portfolio = new PortfolioSetup(0.0, 0.0, 0.0, 0.0, null, 0, 0.0, 0.0, 0.0, 0.0);
        var sim = new SimulationParameters(0, 65, 0, 1, 3, 0.90, 0.0,
                new double[3][2], new double[3][1], new double[3][1], new double[3][1], Integer.MAX_VALUE,
                0.0, 0.0, 0.0, 0.0, 1.0, 1.0, null, null, null, AgeMilestones.LEGACY_EARLY_ACCESS_AGE,
                starts, starts == null ? 0 : 5, null);
        var taxIncome = new TaxIncomeContext(null, 0.0, new double[1], null, new double[1], null, null, null,
                null, null, null, null, null);
        return new OptimizationSetup(portfolio, sim, taxIncome);
    }

    private static TrialStartBalances taxableStart(double taxable) {
        return new TrialStartBalances(taxable, 0, 0, 0, 0, 0, taxable, 0);
    }

    @Test
    void yearZeroBaseline_withStartBalances_isMedianOfTrialStartTotals() {
        var starts = new TrialStartBalances[] {taxableStart(0), taxableStart(40_000), taxableStart(700_000)};

        double baseline = GuardrailResponseBuilder.yearZeroBaseline(
                setupWith(starts), new PoolSimSetup(123, 0, 0, "taxable_first", false));

        assertThat(baseline).isEqualTo(TrialStartBalances.medianTotal(starts)).isEqualTo(40_000);
    }

    @Test
    void yearZeroBaseline_withoutStartBalances_isExpectedPathPoolTotal() {
        double baseline = GuardrailResponseBuilder.yearZeroBaseline(
                setupWith(null), new PoolSimSetup(100_000, 90_000, 60_000, "taxable_first", true));

        assertThat(baseline).isEqualTo(250_000);
    }
}
