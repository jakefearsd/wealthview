package com.wealthview.projection;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.PoolType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TrialSimulatorTentTest {

    private static final double EPS = 1e-6;
    private static final List<PoolType> ORDER = List.of(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH);

    private final TrialSimulator simulator = new TrialSimulator();

    private static TrialTent tent(double amount, double taxable, double ultrashortYield, int sleeveYears) {
        double[] yields = {ultrashortYield, 0, 0, 0};
        var plan = new TrialTentPlan(5.0, 0.0, new double[] {1, 0, 0, 0}, yields, ORDER, 0, 0, 0, null);
        return TentBuildUp.fundedAtStart(plan, new double[sleeveYears * 4], amount, taxable, 0, 0).tent();
    }

    private static double[] filled(int n, double value) {
        var out = new double[n];
        Arrays.fill(out, value);
        return out;
    }

    private static TrialSimulator.SimulationConfig.Builder base(double opening, double[] returns, TrialTent tent) {
        double[] zeros = new double[returns.length];
        return TrialSimulator.SimulationConfig.builder(opening, 0, 0, "taxable_first")
                .retirementAge(62)
                .returns(returns, zeros, zeros)
                .taxableBasis(opening)
                .tent(tent);
    }

    private TrialSimulator.TrialResult run(TrialSimulator.SimulationConfig config, double floor, int years) {
        double[] zeros = new double[years];
        return simulator.simulateTrial(zeros, zeros, filled(years, floor), zeros, years, config);
    }

    @Test
    void simulateTrial_crashInFirstYear_spendsTentBeforeStocks() {
        double[] returns = {-0.30, 0.10, 0.10, 0.10, 0.10};

        var withTent = run(base(1_000_000, returns, tent(80_000, 1_000_000, 0, 5)).build(), 40_000, 5);
        var noTent = run(base(1_000_000, returns, null).build(), 40_000, 5);

        assertThat(withTent.finalBalance()).isCloseTo(810_480.40, within(EPS));
        assertThat(noTent.finalBalance()).isCloseTo(780_666.00, within(EPS));
    }

    @Test
    void simulateTrial_risingMarket_neverDrawsTheTentAndCostsGrowth() {
        double[] returns = filled(5, 0.05);
        var tent = tent(80_000, 1_000_000, 0, 5);

        var withTent = run(base(1_000_000, returns, tent).build(), 40_000, 5);
        var noTent = run(base(1_000_000, returns, null).build(), 40_000, 5);

        assertThat(withTent.finalBalance()).isCloseTo(1_033_153.7875, within(EPS));
        assertThat(noTent.finalBalance()).isCloseTo(1_055_256.3125, within(EPS));
        assertThat(tent.total()).isCloseTo(80_000, within(EPS));
    }

    @Test
    void simulateTrial_growthAssetsGone_spendsTentAndFundsTheFloor() {
        double[] returns = filled(2, 0.05);
        var tent = tent(80_000, 100_000, 0, 2);

        var result = run(base(100_000, returns, tent).trackYearBalances(true).build(), 40_000, 2);

        assertThat(result.yearBalances()).containsExactly(new double[] {61_000, 21_000}, within(EPS));
        assertThat(result.success()).isTrue();
        assertThat(result.minBalance()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void simulateTrial_retiredTaxableTent_taxesSleeveInterestAsOrdinary() {
        double[] returns = {0.05};
        var taxes = new OrdinaryTaxTable[] {OrdinaryTaxTable.flat(0.25)};
        var base = new double[] {0};

        var withYield = run(base(1_000_000, returns, tent(80_000, 1_000_000, 0.04, 1))
                .taxTables(taxes, base).build(), 0, 1);
        var noYield = run(base(1_000_000, returns, tent(80_000, 1_000_000, 0, 1))
                .taxTables(taxes, base).build(), 0, 1);

        assertThat(withYield.finalBalance()).isCloseTo(1_045_200, within(EPS));
        assertThat(noYield.finalBalance()).isCloseTo(1_046_000, within(EPS));
    }

    @Test
    void simulateTrial_emptyTent_isIdenticalToNoTent() {
        double[] returns = {-0.30, 0.10, 0.10};
        var emptyTent = tent(0, 1_000_000, 0.04, 3);

        var withEmpty = run(base(1_000_000, returns, emptyTent).trackYearBalances(true).build(), 40_000, 3);
        var noTent = run(base(1_000_000, returns, null).trackYearBalances(true).build(), 40_000, 3);

        assertThat(withEmpty).usingRecursiveComparison().ignoringFields("tentYearsCovered").isEqualTo(noTent);
    }

    @Test
    void simulateTrial_tentEmptiedInEarlyYear_laterYearsFollowTheUntentedArithmetic() {
        // Tent 30k; crash year 0: 970k*0.7 + 30k = 709k, less 40k = 669k, tent spent in full.
        // Later years run untented: *1.1 - 40k.
        double[] returns = {-0.30, 0.10, 0.10};
        var tent = tent(30_000, 1_000_000, 0, 3);

        var result = run(base(1_000_000, returns, tent).build(), 40_000, 3);

        double expected = (669_000 * 1.1 - 40_000) * 1.1 - 40_000;
        assertThat(tent.total()).isZero();
        assertThat(result.finalBalance()).isCloseTo(expected, within(EPS));
    }

    private static TrialTent trackedTent() {
        var tent = tent(80_000, 1_000_000, 0, 5);
        tent.trackCoverage(new double[] {40_000, 40_000});
        return tent;
    }

    @Test
    void simulateTrial_crashInFirstYear_tentCoversTwoYears() {
        double[] returns = {-0.30, 0.10, 0.10, 0.10, 0.10};

        var result = run(base(1_000_000, returns, trackedTent()).build(), 40_000, 5);

        assertThat(result.tentYearsCovered()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void simulateTrial_risingMarket_unusedTentCountsAsCoverage() {
        var result = run(base(1_000_000, filled(5, 0.05), trackedTent()).build(), 40_000, 5);

        assertThat(result.tentYearsCovered()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void simulateTrial_noTent_tentYearsCoveredIsNaN() {
        var result = run(base(1_000_000, filled(5, 0.05), null).build(), 40_000, 5);

        assertThat(result.tentYearsCovered()).isNaN();
    }
}
