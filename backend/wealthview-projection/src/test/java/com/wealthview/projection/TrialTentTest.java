package com.wealthview.projection;

import java.util.List;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.PoolType;

import static org.assertj.core.api.Assertions.assertThat;

class TrialTentTest {

    private static final Offset<Double> EPS = Offset.offset(1e-6);
    private static final List<PoolType> ORDER = List.of(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH);

    private static TrialTent tent(double... flatReturnsForYearZero) {
        var plan = new TrialTentPlan(5.0, 0.04, new double[] {0.2, 0.3, 0.5, 0.0},
                new double[] {0.04, 0.045, 0.05, 0.055}, ORDER, 0, 0, 1_000_000.0, null);
        return new TrialTent(plan, flatReturnsForYearZero);
    }

    private static TrialTent tent() {
        return tent(0.01, 0.02, 0.03, 0.04);
    }

    private static TrialTent reserved100kInTaxable() {
        var tent = tent();
        tent.topUp(100_000, 300_000, 0, 0, 1e12);
        return tent;
    }

    @Test
    void topUp_reservesInPlacementOrderSplitByShares() {
        var tent = tent();

        tent.topUp(200_000, 300_000, 500_000, 0, 1e12);

        assertThat(tent.totalIn(PoolType.TAXABLE)).isCloseTo(200_000, EPS);
        assertThat(tent.byMaturity(BondMaturity.ULTRASHORT)).isCloseTo(40_000, EPS);
        assertThat(tent.byMaturity(BondMaturity.SHORT)).isCloseTo(60_000, EPS);
        assertThat(tent.byMaturity(BondMaturity.INTERMEDIATE)).isCloseTo(100_000, EPS);
        assertThat(tent.byMaturity(BondMaturity.LONG)).isCloseTo(0, EPS);
    }

    @Test
    void topUp_firstPoolTooSmall_spillsToNextPool() {
        var tent = tent();

        tent.topUp(200_000, 50_000, 500_000, 0, 1e12);

        assertThat(tent.totalIn(PoolType.TAXABLE)).isCloseTo(50_000, EPS);
        assertThat(tent.totalIn(PoolType.TRADITIONAL)).isCloseTo(150_000, EPS);
    }

    @Test
    void topUp_cap_limitsTheIncrease() {
        var tent = tent();

        tent.topUp(200_000, 300_000, 500_000, 0, 30_000);

        assertThat(tent.total()).isCloseTo(30_000, EPS);
    }

    @Test
    void topUp_levelAboveTotalBalance_reservesEverything() {
        var tent = tent();

        tent.topUp(200_000, 20_000, 40_000, 60_000, 1e12);

        assertThat(tent.total()).isCloseTo(120_000, EPS);
    }

    @Test
    void grownBalance_blendsGrowthPartAndSleeves() {
        var tent = tent(0.01, 0.01, 0.01, 0.01);
        tent.topUp(100_000, 0, 500_000, 0, 1e12);

        double grown = tent.grownBalance(PoolType.TRADITIONAL, 500_000, 0.05, 0);

        assertThat(grown).isCloseTo(521_000.0, EPS);
        assertThat(tent.total()).isCloseTo(100_000, EPS);
    }

    @Test
    void growSleeves_eachAtItsOwnReturn() {
        var tent = reserved100kInTaxable();

        tent.growSleeves(0);

        assertThat(tent.byMaturity(BondMaturity.ULTRASHORT)).isCloseTo(20_200, EPS);
        assertThat(tent.byMaturity(BondMaturity.SHORT)).isCloseTo(30_600, EPS);
        assertThat(tent.byMaturity(BondMaturity.INTERMEDIATE)).isCloseTo(51_500, EPS);
    }

    @Test
    void interestIn_isSleeveTimesNominalYield() {
        var tent = reserved100kInTaxable();

        assertThat(tent.interestIn(PoolType.TAXABLE)).isCloseTo(4650.0, EPS);
        assertThat(tent.interestIn(PoolType.ROTH)).isCloseTo(0.0, EPS);
    }

    @Test
    void growthReturn_weightsGrowthPartsOnly() {
        var tent = reserved100kInTaxable();

        double g = tent.growthReturn(300_000, 200_000, 0, 0.10, 0.00, 0.50);

        assertThat(g).isCloseTo(0.05, EPS);
    }

    @Test
    void growthReturn_noGrowthPart_isZero() {
        var tent = reserved100kInTaxable();

        assertThat(tent.growthReturn(100_000, 0, 0, 0.10, 0.0, 0.0)).isCloseTo(0.0, EPS);
    }

    @Test
    void isBelowPeak_onlyUntilTheIndexRegainsItsHigh() {
        var tent = tent();
        assertThat(tent.isBelowPeak()).isFalse();

        tent.advanceIndex(0.1);
        assertThat(tent.isBelowPeak()).isFalse();

        tent.advanceIndex(-0.2);
        assertThat(tent.isBelowPeak()).isTrue();

        tent.advanceIndex(0.1);
        assertThat(tent.isBelowPeak()).isTrue();

        tent.advanceIndex(0.2);
        assertThat(tent.isBelowPeak()).isFalse();
    }

    @Test
    void spend_shortestMaturityFirst() {
        var tent = reserved100kInTaxable();

        tent.spend(35_000);

        assertThat(tent.byMaturity(BondMaturity.ULTRASHORT)).isCloseTo(0, EPS);
        assertThat(tent.byMaturity(BondMaturity.SHORT)).isCloseTo(15_000, EPS);
        assertThat(tent.byMaturity(BondMaturity.INTERMEDIATE)).isCloseTo(50_000, EPS);
    }

    @Test
    void spend_moreThanTotal_spendsOnlyWhatIsThere() {
        var tent = reserved100kInTaxable();

        tent.spend(500_000);

        assertThat(tent.total()).isCloseTo(0, EPS);
        assertThat(tent.isEmpty()).isTrue();
    }

    @Test
    void spend_withinAMaturity_followsPlacementOrder() {
        var plan = new TrialTentPlan(5.0, 0.04, new double[] {1, 0, 0, 0}, new double[4], ORDER, 0, 0, 1e12, null);
        var tent = new TrialTent(plan, new double[4]);
        tent.topUp(20_000, 10_000, 10_000, 0, 1e12);

        tent.spend(15_000);

        assertThat(tent.totalIn(PoolType.TAXABLE)).isCloseTo(0, EPS);
        assertThat(tent.totalIn(PoolType.TRADITIONAL)).isCloseTo(5_000, EPS);
    }

    private static void assertSleeves(TrialTent tent, PoolType pool, double us, double sh, double in, double lg) {
        assertThat(tent.sleeve(pool, BondMaturity.ULTRASHORT)).as("%s ultrashort", pool).isCloseTo(us, EPS);
        assertThat(tent.sleeve(pool, BondMaturity.SHORT)).as("%s short", pool).isCloseTo(sh, EPS);
        assertThat(tent.sleeve(pool, BondMaturity.INTERMEDIATE)).as("%s intermediate", pool).isCloseTo(in, EPS);
        assertThat(tent.sleeve(pool, BondMaturity.LONG)).as("%s long", pool).isCloseTo(lg, EPS);
    }

    @Test
    void clampAll_poolBelowItsReservation_movesExcessToFreeRoom() {
        var tent = reserved100kInTaxable();

        tent.clampAll(30_000, 500_000, 0);

        assertSleeves(tent, PoolType.TAXABLE, 0, 0, 30_000, 0);
        assertSleeves(tent, PoolType.TRADITIONAL, 20_000, 30_000, 20_000, 0);
        assertSleeves(tent, PoolType.ROTH, 0, 0, 0, 0);
        assertThat(tent.total()).isCloseTo(100_000, EPS);
    }

    @Test
    void clampAll_firstReceivingPoolLimited_fillsInPlacementOrderLongestFirst() {
        var tent = reserved100kInTaxable();

        tent.clampAll(30_000, 25_000, 500_000);

        assertSleeves(tent, PoolType.TAXABLE, 0, 0, 30_000, 0);
        assertSleeves(tent, PoolType.TRADITIONAL, 0, 5_000, 20_000, 0);
        assertSleeves(tent, PoolType.ROTH, 20_000, 25_000, 0, 0);
        assertThat(tent.total()).isCloseTo(100_000, EPS);
    }

    @Test
    void spend_acrossPoolsAndMaturities_exhaustsEachMaturityInPlacementOrderFirst() {
        var plan = new TrialTentPlan(5.0, 0.04, new double[] {0.5, 0.5, 0, 0}, new double[4], ORDER, 0, 0, 1e12, null);
        var tent = new TrialTent(plan, new double[4]);
        tent.topUp(40_000, 20_000, 20_000, 0, 1e12);

        tent.spend(25_000);

        assertSleeves(tent, PoolType.TAXABLE, 0, 5_000, 0, 0);
        assertSleeves(tent, PoolType.TRADITIONAL, 0, 10_000, 0, 0);
    }

    @Test
    void clampAll_noRoomAnywhere_spendsTheRest() {
        var tent = reserved100kInTaxable();

        tent.clampAll(60_000, 0, 0);

        assertThat(tent.total()).isCloseTo(60_000, EPS);
        assertThat(tent.byMaturity(BondMaturity.SHORT)).isCloseTo(10_000, EPS);
        assertThat(tent.byMaturity(BondMaturity.INTERMEDIATE)).isCloseTo(50_000, EPS);
    }

    @Test
    void yearsBeforeRetirement_echoesThePlan() {
        var plan = new TrialTentPlan(5.0, 0.04, new double[4], new double[4], ORDER, 7, 0, 0.0, null);

        assertThat(new TrialTent(plan, new double[4]).yearsBeforeRetirement()).isEqualTo(7);
    }

    @Test
    void yearsCovered_partialDrawThenWindowEnds() {
        var tent = tent();
        tent.topUp(50_000, 300_000, 0, 0, 1e12);
        tent.trackCoverage(new double[] {40_000, 40_000});
        double before = tent.total();
        tent.spend(20_000);
        tent.recordCoverageYear(0, before);
        before = tent.total();
        tent.spend(0);
        tent.recordCoverageYear(1, before);
        tent.spend(0);

        assertThat(tent.yearsCovered()).isCloseTo(0.5 + 30_000.0 / 40_000.0, EPS);
    }

    @Test
    void yearsCovered_notTracked_isNaN() {
        assertThat(reserved100kInTaxable().yearsCovered()).isNaN();
    }

    @Test
    void isEmpty_residueBelowTolerance_isEmpty() {
        var tent = tent();
        tent.topUp(1e-11, 300_000, 0, 0, 1e12);

        assertThat(tent.isEmpty()).isTrue();
    }

    @Test
    void isEmpty_aboveTolerance_isNotEmpty() {
        var tent = tent();
        tent.topUp(1e-6, 300_000, 0, 0, 1e12);

        assertThat(tent.isEmpty()).isFalse();
    }
}
