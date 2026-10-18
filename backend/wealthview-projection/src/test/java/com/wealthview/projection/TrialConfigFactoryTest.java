package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TrialConfigFactoryTest {

    private static final double[][] ZERO_RETURNS = new double[2][3];

    private static TrialConfigFactory.Builder minimal(PoolSimSetup setup) {
        return TrialConfigFactory.builder(setup)
                .returns(ZERO_RETURNS, ZERO_RETURNS, ZERO_RETURNS)
                .ages(65, 73, 60)
                .taxableBasis(100_000);
    }

    private static TrialStartBalances start(double taxable, double traditional, double roth,
                                            double tradSpouse, double rothSpouse,
                                            double joint, double primary, double spouse) {
        return new TrialStartBalances(taxable, traditional, roth, tradSpouse, rothSpouse, joint, primary, spouse);
    }

    private static TrialSimulator.HouseholdSim household() {
        return new TrialSimulator.HouseholdSim(1, 2, 3, 75, 4, true, 0.5, 20,
                new TrialSimulator.TaxableSeed(10, 20, 30, 40, 50, 60));
    }

    @Test
    void configFor_noStartBalances_usesPoolSetupScalars() {
        var factory = minimal(new PoolSimSetup(100_000, 50_000, 25_000, "taxable_first", true)).build();

        var config = factory.configFor(0, null, false, null);

        assertThat(config.initTaxable()).isEqualTo(100_000);
        assertThat(config.initTraditional()).isEqualTo(50_000);
        assertThat(config.initRoth()).isEqualTo(25_000);
    }

    @Test
    void configFor_startBalancesWithPools_usesEachTrialsBalances() {
        var starts = new TrialStartBalances[] {
                start(1_000, 2_000, 3_000, 0, 0, 0, 0, 0),
                start(4_000, 5_000, 6_000, 0, 0, 0, 0, 0)};
        var factory = minimal(new PoolSimSetup(100_000, 50_000, 25_000, "taxable_first", true))
                .startBalances(starts).build();

        var c0 = factory.configFor(0, null, false, null);
        var c1 = factory.configFor(1, null, false, null);

        assertThat(new double[] {c0.initTaxable(), c0.initTraditional(), c0.initRoth()})
                .containsExactly(1_000, 2_000, 3_000);
        assertThat(new double[] {c1.initTaxable(), c1.initTraditional(), c1.initRoth()})
                .containsExactly(4_000, 5_000, 6_000);
    }

    @Test
    void configFor_startBalancesWithoutPools_taxableIsTrialTotal() {
        var starts = new TrialStartBalances[] {
                start(1_000, 2_000, 3_000, 0, 0, 0, 0, 0),
                start(4_000, 5_000, 6_000, 0, 0, 0, 0, 0)};
        var factory = minimal(new PoolSimSetup(100_000, 50_000, 25_000, "taxable_first", false))
                .startBalances(starts).build();

        var config = factory.configFor(1, null, false, null);

        assertThat(config.initTaxable()).isEqualTo(starts[1].total());
        assertThat(config.initTraditional()).isEqualTo(50_000);
        assertThat(config.initRoth()).isEqualTo(25_000);
    }

    @Test
    void configFor_household_spouseSlicesAndLotValuesFollowTrial_basesUnchanged() {
        var starts = new TrialStartBalances[] {
                start(0, 0, 0, 7, 8, 11, 12, 13),
                start(0, 0, 0, 17, 18, 21, 22, 23)};
        var factory = minimal(new PoolSimSetup(100_000, 50_000, 25_000, "taxable_first", true))
                .household(household()).startBalances(starts).build();

        var hh = factory.configFor(1, null, false, null).household();

        assertThat(hh.initTraditionalSpouse()).isEqualTo(17);
        assertThat(hh.initRothSpouse()).isEqualTo(18);
        assertThat(hh.taxableSeed()).isEqualTo(new TrialSimulator.TaxableSeed(10, 21, 30, 22, 50, 23));
        assertThat(hh.transitionYearIndex()).isEqualTo(4);
    }

    @Test
    void configFor_householdOverride_alsoReceivesTrialStartBalances() {
        var starts = new TrialStartBalances[] {
                start(0, 0, 0, 7, 8, 11, 12, 13),
                start(0, 0, 0, 17, 18, 21, 22, 23)};
        var factory = minimal(new PoolSimSetup(100_000, 50_000, 25_000, "taxable_first", true))
                .household(household()).startBalances(starts).build();
        var override = household().withTrialMortality(9, false, 15);

        var hh = factory.configFor(0, null, false, override).household();

        assertThat(hh.initTraditionalSpouse()).isEqualTo(7);
        assertThat(hh.initRothSpouse()).isEqualTo(8);
        assertThat(hh.transitionYearIndex()).isEqualTo(9);
        assertThat(hh.taxableSeed()).isEqualTo(new TrialSimulator.TaxableSeed(10, 11, 30, 12, 50, 13));
    }

    @Test
    void configFor_taxableBelowBasis_seedsLotWithUnrealizedLoss() {
        var starts = new TrialStartBalances[] {
                start(80_000, 0, 0, 0, 0, 0, 0, 0),
                start(80_000, 0, 0, 0, 0, 0, 0, 0)};
        var factory = minimal(new PoolSimSetup(100_000, 0, 0, "taxable_first", true))
                .startBalances(starts).build();
        var config = factory.configFor(0, null, false, null);

        double gain = TrialPools.seed(config, null).sellTaxable(80_000);

        assertThat(gain).isEqualTo(-20_000.0, within(1e-6));
    }
}
