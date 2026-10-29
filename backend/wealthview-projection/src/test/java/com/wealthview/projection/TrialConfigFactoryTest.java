package com.wealthview.projection;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.PoolType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private static HouseholdSim household() {
        return new HouseholdSim(1, 2, 3, 75, 4, true, 0.5, 20,
                new TaxableSeed(10, 20, 30, 40, 50, 60));
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
        assertThat(hh.taxableSeed()).isEqualTo(new TaxableSeed(10, 21, 30, 22, 50, 23));
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
        assertThat(hh.taxableSeed()).isEqualTo(new TaxableSeed(10, 11, 30, 12, 50, 13));
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

    private static TrialTentPlan tentPlan(int pre, int buildUp, double coverage) {
        return new TrialTentPlan(coverage, 0.0, new double[] {1, 0, 0, 0}, new double[4],
                List.of(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH), pre, Math.max(0, pre - buildUp),
                0, null);
    }

    @Test
    void configFor_tentWithPreRetirementYears_carriesTheBuildUpIntoStartBalances() {
        double[] before = {1_000_000, 0, 0, 1_100_000, 0, 0, 770_000, 0, 0};
        double[] after = {1_100_000, 0, 0, 770_000, 0, 0, 770_000, 0, 0};
        var paths = new TentPaths(new double[][] {new double[13 * 4]}, new double[][] {before},
                new double[][] {after});
        var starts = new TrialStartBalances[] {start(770_000, 0, 0, 0, 0, 770_000, 0, 0)};
        var factory = minimal(new PoolSimSetup(100_000, 0, 0, "taxable_first", true))
                .startBalances(starts).tent(tentPlan(3, 3, 5.0), paths).build();

        var config = factory.configFor(0, null, false, null, 90_000);

        assertThat(config.initTaxable()).isCloseTo(779_000, within(1e-6));
        assertThat(config.tent().total()).isCloseTo(30_000, within(1e-6));
    }

    @Test
    void configFor_retiredAlready_fundsFullTargetInYearZero() {
        var paths = new TentPaths(new double[][] {new double[4 * 4], new double[4 * 4]}, null, null);
        var factory = minimal(new PoolSimSetup(50_000, 500_000, 0, "taxable_first", true))
                .tent(tentPlan(0, 0, 5.0), paths).build();

        var config = factory.configFor(0, null, false, null, 200_000);

        assertThat(config.tent().total()).isCloseTo(200_000, within(1e-6));
        assertThat(config.tent().totalIn(PoolType.TAXABLE)).isCloseTo(50_000, within(1e-6));
        assertThat(config.tent().totalIn(PoolType.TRADITIONAL)).isCloseTo(150_000, within(1e-6));
    }

    @Test
    void tentTargetFor_noPlan_isZero() {
        var factory = minimal(new PoolSimSetup(50_000, 500_000, 0, "taxable_first", true)).build();

        double target = factory.tentTargetFor(new double[] {40_000}, new double[] {0}, new double[] {0},
                new double[] {0});

        assertThat(target).isZero();
    }

    @Test
    void tentSizingFor_matchesTentTargetFor() {
        var paths = new TentPaths(new double[][] {new double[4 * 4]}, null, null);
        var factory = minimal(new PoolSimSetup(50_000, 500_000, 0, "taxable_first", true))
                .tent(tentPlan(0, 0, 5.0), paths).build();
        double[] floors = {40_000, 40_000, 40_000, 40_000, 40_000, 40_000};
        double[] zero = new double[6];

        var sizing = factory.tentSizingFor(floors, zero, zero, zero);

        assertThat(sizing.target()).isEqualTo(factory.tentTargetFor(floors, zero, zero, zero));
        assertThat(sizing.withdrawals()).hasSize(5);
    }

    @Test
    void configFor_noPlan_hasNoTent() {
        var factory = minimal(new PoolSimSetup(50_000, 500_000, 0, "taxable_first", true)).build();

        assertThat(factory.configFor(0, null, false, null).tent()).isNull();
    }

    @Test
    void configFor_nonPooledTrial_placesTheWholeTentInTaxable() {
        double[] before = {1_000_000, 0, 0};
        double[] after = {1_000_000, 0, 0};
        var paths = new TentPaths(new double[][] {new double[5 * 4]}, new double[][] {before},
                new double[][] {after});
        var starts = new TrialStartBalances[] {start(300_000, 600_000, 100_000, 0, 0, 300_000, 0, 0)};
        var factory = minimal(new PoolSimSetup(0, 0, 0, "taxable_first", false))
                .startBalances(starts).tent(tentPlan(1, 1, 5.0), paths).build();

        var config = factory.configFor(0, null, false, null, 90_000);

        assertThat(config.initTaxable()).isCloseTo(1_000_000, within(1e-6));
        assertThat(config.tent().total()).isCloseTo(90_000, within(1e-6));
        assertThat(config.tent().totalIn(PoolType.TAXABLE)).isCloseTo(90_000, within(1e-6));
    }

    @Test
    void configFor_nonPooledWithoutStartBalances_fundsTheTentFromTaxableOnly() {
        var paths = new TentPaths(new double[][] {new double[4 * 4]}, null, null);
        var factory = minimal(new PoolSimSetup(100_000, 50_000, 25_000, "taxable_first", false))
                .tent(tentPlan(0, 0, 5.0), paths).build();

        var config = factory.configFor(0, null, false, null, 200_000);

        // A non-pooled run holds everything as taxable, so the tent can take only the 100k taxable balance even
        // though the pool setup's traditional and Roth scalars (50k, 25k) would otherwise absorb the rest.
        assertThat(config.tent().total()).isCloseTo(100_000, within(1e-6));
        assertThat(config.tent().totalIn(PoolType.TAXABLE)).isCloseTo(100_000, within(1e-6));
        assertThat(config.tent().totalIn(PoolType.TRADITIONAL)).isZero();
        assertThat(config.tent().totalIn(PoolType.ROTH)).isZero();
        assertThat(config.initTaxable()).isEqualTo(100_000);
    }

    @Test
    void configFor_legacyDoubleTargetOnAnEmptyPlan_coversZeroYearsNotNaN() {
        var paths = new TentPaths(new double[][] {new double[4 * 4]}, null, null);
        var factory = minimal(new PoolSimSetup(50_000, 500_000, 0, "taxable_first", true))
                .tent(tentPlan(0, 0, 5.0), paths).build();

        var empty = factory.configFor(0, null, false, null, 0.0);
        var funded = factory.configFor(0, null, false, null, 50_000);

        // The double overload carries no withdrawal schedule, so there is nothing to measure coverage against:
        // 0.0 (a defined "no coverage"), never NaN, which the Monte Carlo coverage percentiles filter out.
        assertThat(empty.tent().yearsCovered()).isEqualTo(0.0);
        assertThat(funded.tent().yearsCovered()).isEqualTo(0.0);
    }

    @Test
    void tent_onlyOneOfPlanAndPaths_isRejected() {
        var builder = minimal(new PoolSimSetup(0, 0, 0, "taxable_first", true));

        assertThatThrownBy(() -> builder.tent(tentPlan(0, 0, 5.0), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
