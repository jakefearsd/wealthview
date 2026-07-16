package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Sub-project B (stochastic mortality), task 6: the per-trial three-regime splice in
 * {@link TrialSimulator#simulateTrial}. Under stochastic mortality EITHER spouse can be the survivor
 * and the first death lands at a per-trial year, so each trial's config carries the JOINT phase in the
 * base arrays plus TWO precomputed {@link TrialSimulator.SurvivorRegime}s (income + tax tables built
 * SINGLE for each survivor identity). {@code household.transitionYearIndex()}/{@code
 * survivorIsPrimary()}/{@code truncateYearIndex()} are the per-trial mortality draw; the trial splices
 * in the matching survivor regime from the transition index forward and scales spending by the survivor
 * factor from that index.
 *
 * <p>Byte-identical anchor (the {@code survivorRegimes == null} path) is pinned by
 * {@link TrialSimulatorReturnTest} and {@link TrialSimulatorHouseholdTest}; every config here carries a
 * non-null two-element regime array.
 */
class TrialSimulatorStochasticTest {

    private final TrialSimulator simulator = new TrialSimulator();

    /** Builds a stochastic SimulationConfig (tracked, 0% cash return) with survivor regimes + factor. */
    // ExcessiveParameterList mirrors the SimulationConfig canonical shape for readable test call sites.
    @SuppressWarnings("PMD.ExcessiveParameterList")
    private static TrialSimulator.SimulationConfig cfg(
            double initTaxable, double initTraditional, double initRoth,
            String order, OrdinaryTaxTable[] tables, double[] base,
            double[] conv, double[] convTax, int retirementAge, int cashReserveYears,
            double[] taxRet, double[] tradRet, double[] rothRet, int rmdStartAge,
            double initTaxableBasis, LtcgTaxTable[] ltcg, TrialSimulator.HouseholdSim household,
            TrialSimulator.SurvivorRegime[] regimes, double survivorFactor) {
        return new TrialSimulator.SimulationConfig(
                initTaxable, initTraditional, initRoth, order,
                tables, base, conv, convTax, retirementAge, null,
                cashReserveYears, 0.0, true,
                taxRet, tradRet, rothRet, rmdStartAge,
                initTaxableBasis, ltcg, 0.0, null, null, 0.0, 1.0, household,
                regimes, survivorFactor);
    }

    /** A survivor regime carrying only per-year income + a flat ordinary table (the LTCG/DS/rental
     * arrays are inactive for these pool-draw oracles). */
    private static TrialSimulator.SurvivorRegime regime(double[] income, OrdinaryTaxTable[] tables,
                                                        double[] base) {
        return new TrialSimulator.SurvivorRegime(
                income, new double[income.length], base, tables, null, null, null);
    }

    // === (a) primary dies first -> the SPOUSE-survivor regime (income + single table) is spliced in ===

    @Test
    void simulateTrial_primaryDiesFirst_splicesSpouseSurvivorRegimeIncomeAndTableAndFactorAfterTransition() {
        // 2-year, traditional-only pool, no growth/conversion/cash. Each year draws
        // (spending - income) from traditional; the flat table's grossed-up tax makes the year's
        // traditional drop exactly W/(1-rate). Year 0 is the JOINT phase (income 20k, rate 10%,
        // full spending); year 1 (>= transition index 1) is the SPOUSE-survivor phase (income 10k,
        // single rate 20%, spending x0.75). The PRIMARY-survivor regime is a decoy that must NOT be
        // selected (survivorIsPrimary == false).
        var joint = new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.10), OrdinaryTaxTable.flat(0.10)};
        var spouseRegime = regime(new double[]{0.0, 10_000.0},
                new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.20), OrdinaryTaxTable.flat(0.20)},
                new double[]{0.0, 0.0});
        var primaryDecoy = regime(new double[]{0.0, 50_000.0},
                new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.40), OrdinaryTaxTable.flat(0.40)},
                new double[]{0.0, 0.0});
        var regimes = new TrialSimulator.SurvivorRegime[2];
        regimes[TrialSimulator.PRIMARY_SURVIVES] = primaryDecoy;
        regimes[TrialSimulator.SPOUSE_SURVIVES] = spouseRegime;

        var household = new TrialSimulator.HouseholdSim(
                0.0, 0.0, /*spouseAgeOffset=*/0, /*spouseRmdStartAge=*/99,
                /*transitionYearIndex=*/1, /*survivorIsPrimary=*/false,
                /*jointStepUpFactor=*/0.5, /*truncateYearIndex=*/2, TrialSimulator.TaxableSeed.EMPTY);
        var config = cfg(0.0, 1_000_000.0, 0.0, "traditional_first",
                joint, new double[]{0.0, 0.0}, null, null, /*retirementAge=*/65, 0,
                new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, /*rmdStartAge=*/99,
                0.0, null, household, regimes, /*survivorFactor=*/0.75);

        var result = simulator.simulateTrial(
                /*income (JOINT)=*/new double[]{20_000.0, 20_000.0}, new double[]{0.0, 0.0},
                /*floors=*/new double[]{40_000.0, 40_000.0}, /*discretionary=*/new double[]{20_000.0, 20_000.0},
                2, config);

        // Year 0 JOINT: W0 = 60k - 20k = 40k @10% -> drop 40k/0.9.
        double afterYear0 = 1_000_000.0 - 40_000.0 / 0.9;
        assertThat(result.yearBalances()[0]).isEqualTo(afterYear0, within(1e-4));
        // Year 1 SPOUSE regime: spending 60k x0.75 = 45k, income 10k -> W1 = 35k @20% -> drop 35k/0.8.
        double expectedFinal = afterYear0 - 35_000.0 / 0.8;
        assertThat(result.finalBalance()).isEqualTo(expectedFinal, within(1e-4));
        // Proof it spliced (not the no-splice joint-year-1 result of drawing 40k @10% again).
        assertThat(result.finalBalance()).isNotEqualTo(afterYear0 - 40_000.0 / 0.9);
    }

    // === (b) mirror: spouse dies first -> the PRIMARY-survivor regime is spliced in ===

    @Test
    void simulateTrial_spouseDiesFirst_splicesPrimarySurvivorRegimeIncomeAndTableAndFactorAfterTransition() {
        // Same oracle shape as (a) but survivorIsPrimary == true, so the PRIMARY-survivor regime
        // (income 10k, single rate 20%) must be selected and the SPOUSE regime is the decoy.
        var joint = new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.10), OrdinaryTaxTable.flat(0.10)};
        var primaryRegime = regime(new double[]{0.0, 10_000.0},
                new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.20), OrdinaryTaxTable.flat(0.20)},
                new double[]{0.0, 0.0});
        var spouseDecoy = regime(new double[]{0.0, 50_000.0},
                new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.40), OrdinaryTaxTable.flat(0.40)},
                new double[]{0.0, 0.0});
        var regimes = new TrialSimulator.SurvivorRegime[2];
        regimes[TrialSimulator.PRIMARY_SURVIVES] = primaryRegime;
        regimes[TrialSimulator.SPOUSE_SURVIVES] = spouseDecoy;

        var household = new TrialSimulator.HouseholdSim(
                0.0, 0.0, 0, 99, /*transitionYearIndex=*/1, /*survivorIsPrimary=*/true,
                0.5, /*truncateYearIndex=*/2, TrialSimulator.TaxableSeed.EMPTY);
        var config = cfg(0.0, 1_000_000.0, 0.0, "traditional_first",
                joint, new double[]{0.0, 0.0}, null, null, 65, 0,
                new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, 99,
                0.0, null, household, regimes, 0.75);

        var result = simulator.simulateTrial(
                new double[]{20_000.0, 20_000.0}, new double[]{0.0, 0.0},
                new double[]{40_000.0, 40_000.0}, new double[]{20_000.0, 20_000.0}, 2, config);

        double afterYear0 = 1_000_000.0 - 40_000.0 / 0.9;
        double expectedFinal = afterYear0 - 35_000.0 / 0.8;
        assertThat(result.finalBalance()).isEqualTo(expectedFinal, within(1e-4));
        // Had the SPOUSE decoy (income 50k > spending 45k -> no draw) been picked, final would be afterYear0.
        assertThat(result.finalBalance()).isNotEqualTo(afterYear0);
    }

    // === (c) truncation with regimes: the loop ends at truncateIdx, bequest carries forward ===

    @Test
    void simulateTrial_secondDeathTruncation_withRegimes_endsLoopAtTruncateIdxAndCarriesBequest() {
        // Horizon 5, transition at index 1 (spouse-survivor regime income 5k from then), second death
        // truncates after 3 simulated years (truncateYearIndex = 3). Taxable-only, no tax tables.
        var spouseRegime = regime(new double[]{0.0, 5_000.0, 5_000.0, 5_000.0, 5_000.0}, null,
                new double[5]);
        var regimes = new TrialSimulator.SurvivorRegime[2];
        regimes[TrialSimulator.PRIMARY_SURVIVES] = spouseRegime; // unused (survivorIsPrimary == false)
        regimes[TrialSimulator.SPOUSE_SURVIVES] = spouseRegime;

        var household = new TrialSimulator.HouseholdSim(
                0.0, 0.0, 0, 99, /*transitionYearIndex=*/1, /*survivorIsPrimary=*/false,
                0.5, /*truncateYearIndex=*/3, TrialSimulator.TaxableSeed.EMPTY);
        double[] zero5 = {0.0, 0.0, 0.0, 0.0, 0.0};
        double[] floors5 = {30_000.0, 30_000.0, 30_000.0, 30_000.0, 30_000.0};
        var config = cfg(100_000.0, 0.0, 0.0, "taxable_first",
                null, null, null, null, 60, 0,
                zero5, zero5, zero5, 99, 0.0, null, household, regimes, /*survivorFactor=*/1.0);

        var result = simulator.simulateTrial(
                /*income (JOINT)=*/zero5, zero5, floors5, zero5, 5, config);

        // y0 (joint, income 0): draw 30k -> 70k. y1,y2 (spouse regime, income 5k): draw 25k each ->
        // 45k, 20k. Loop stops at truncateYearIndex 3.
        assertThat(result.finalBalance()).isEqualTo(20_000.0, within(1e-6));
        assertThat(result.yearBalances()[2]).isEqualTo(20_000.0, within(1e-6)); // last simulated year
        assertThat(result.yearBalances()[3]).isEqualTo(20_000.0, within(1e-6)); // carried-forward bequest
        assertThat(result.yearBalances()[4]).isEqualTo(20_000.0, within(1e-6));
    }

    // === (d) composition/telescoping: two RMD streams (both alive) then the survivor-table splice ===

    @Test
    void simulateTrial_twoRmdStreamsThenTransition_totalTelescopesUnderPerYearRegimeTables() {
        // Year 0 both alive: two RMD streams under the JOINT flat 10% table (no draw). Transition at
        // year 1 (survivorIsPrimary == true): spouse's remaining traditional rolls into the primary,
        // and year 1's RMD streams + a 5k draw are priced under the SPLICED SINGLE survivor 20% table.
        // A huge taxable pool pays every cascade tax (no gross-up), so per-year ordinary tax telescopes
        // exactly and the trial's total drop is order-invariant across both RMD streams and the
        // transition -- the point of the composition identity, now spanning the regime splice.
        var joint = new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.10), OrdinaryTaxTable.flat(0.10)};
        var primaryRegime = regime(new double[]{0.0, 0.0},
                new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.20), OrdinaryTaxTable.flat(0.20)},
                new double[]{0.0, 0.0});
        var regimes = new TrialSimulator.SurvivorRegime[2];
        regimes[TrialSimulator.PRIMARY_SURVIVES] = primaryRegime;
        regimes[TrialSimulator.SPOUSE_SURVIVES] = primaryRegime; // unused (survivorIsPrimary == true)

        var household = new TrialSimulator.HouseholdSim(
                /*initTraditionalSpouse=*/100_000.0, 0.0, /*spouseAgeOffset=*/0, /*spouseRmdStartAge=*/73,
                /*transitionYearIndex=*/1, /*survivorIsPrimary=*/true,
                /*jointStepUpFactor=*/0.5, /*truncateYearIndex=*/2, TrialSimulator.TaxableSeed.EMPTY);
        var config = cfg(1_000_000.0, 300_000.0, 0.0, "traditional_first",
                joint, new double[]{0.0, 0.0}, null, null, /*retirementAge=*/75, 0,
                new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, /*rmdStartAge=*/73,
                1_000_000.0, null, household, regimes, /*survivorFactor=*/1.0);

        double draw1 = 5_000.0;
        var result = simulator.simulateTrial(
                new double[]{0.0, 0.0}, new double[]{0.0, 0.0},
                /*floors=*/new double[]{0.0, draw1}, /*discretionary=*/new double[]{0.0, 0.0}, 2, config);

        double dp75 = RmdCalculator.distributionPeriod(75);
        double dp76 = RmdCalculator.distributionPeriod(76);
        double rmdSum0 = 300_000.0 / dp75;                 // primary 200k + spouse 100k, both age 75
        double rmdSum1 = (300_000.0 - rmdSum0) / dp76;      // year-1 basis = year-0 end traditional
        double year0Drop = 0.10 * rmdSum0;                 // JOINT table, no draw
        double year1Drop = draw1 + 0.20 * (rmdSum1 + draw1); // SURVIVOR table, draw + telescoped tax
        double expectedFinal = 1_300_000.0 - year0Drop - year1Drop;

        assertThat(1_300_000.0 - result.finalBalance())
                .isEqualTo(year0Drop + year1Drop, within(1e-4));
        assertThat(result.finalBalance()).isEqualTo(expectedFinal, within(1e-4));
        // Had year 1 kept the JOINT 10% table (no splice), the survivor-year tax would halve.
        assertThat(result.finalBalance()).isNotEqualTo(1_300_000.0 - year0Drop
                - (draw1 + 0.10 * (rmdSum1 + draw1)));
    }
}
