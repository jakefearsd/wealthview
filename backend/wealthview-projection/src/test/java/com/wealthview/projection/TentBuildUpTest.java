package com.wealthview.projection;

import java.util.List;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.PoolType;

import static org.assertj.core.api.Assertions.assertThat;

class TentBuildUpTest {

    private static final Offset<Double> EPS = Offset.offset(1e-6);
    private static final List<PoolType> ORDER = List.of(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH);

    private static TrialTentPlan plan(int pre, int buildUp, double contributionCap) {
        return new TrialTentPlan(5.0, 0.0, new double[] {1, 0, 0, 0}, new double[4], ORDER, pre,
                Math.max(0, pre - buildUp), contributionCap, null);
    }

    /** One trial, taxable pool only; sleeves all at 0%. */
    private static TentPaths paths(int pre, double[] before, double[] after) {
        double[] b = new double[pre * 3];
        double[] a = new double[pre * 3];
        for (int y = 0; y < pre; y++) {
            b[y * 3] = before[y];
            a[y * 3] = after[y];
        }
        return new TentPaths(new double[][] {new double[(pre + 10) * 4]}, new double[][] {b}, new double[][] {a});
    }

    private static TrialStartBalances taxableStart(double taxable) {
        return new TrialStartBalances(taxable, 0, 0, 0, 0, taxable, 0, 0);
    }

    @Test
    void toRetirement_upMarkets_followsTheLineToTarget() {
        var paths = paths(3, new double[] {1_000_000, 1_100_000, 1_210_000},
                new double[] {1_100_000, 1_210_000, 1_331_000});

        var start = TentBuildUp.toRetirement(plan(3, 3, 0), paths, 0, 90_000, taxableStart(1_331_000));

        assertThat(start.tent().total()).isCloseTo(90_000, EPS);
        assertThat(start.taxableAdjustment()).isCloseTo(-9_300, EPS);
    }

    @Test
    void toRetirement_crashDuringBuildUp_topsUpOnlyFromContributions() {
        var paths = paths(3, new double[] {1_010_000, 818_000, 868_900},
                new double[] {808_000, 858_900, 912_345});

        var start = TentBuildUp.toRetirement(plan(3, 3, 10_000), paths, 0, 90_000, taxableStart(912_345));

        assertThat(start.tent().total()).isCloseTo(20_000, EPS);
        assertThat(start.taxableAdjustment()).isCloseTo(-1_525, EPS);
    }

    @Test
    void toRetirement_buildUpZero_fundsFullTargetAtRetirementInADownMarket() {
        var paths = paths(3, new double[] {1_000_000, 800_000, 640_000},
                new double[] {800_000, 640_000, 512_000});

        var start = TentBuildUp.toRetirement(plan(3, 0, 0), paths, 0, 90_000, taxableStart(512_000));

        assertThat(start.tent().total()).isCloseTo(90_000, EPS);
        assertThat(start.taxableAdjustment()).isCloseTo(0, EPS);
    }

    @Test
    void toRetirement_bondsHoldUpInACrash_raisesTheStartBalance() {
        var paths = paths(3, new double[] {1_000_000, 1_100_000, 770_000},
                new double[] {1_100_000, 770_000, 770_000});

        var start = TentBuildUp.toRetirement(plan(3, 3, 0), paths, 0, 90_000, taxableStart(770_000));

        assertThat(start.tent().total()).isCloseTo(30_000, EPS);
        assertThat(start.taxableAdjustment()).isCloseTo(9_000, EPS);
    }

    @Test
    void toRetirement_buildUpBeforeToday_compressesTheRamp() {
        var paths = paths(4, new double[] {1_000_000, 1_100_000, 1_210_000, 1_331_000},
                new double[] {1_100_000, 1_210_000, 1_331_000, 1_464_100});

        var start = TentBuildUp.toRetirement(plan(4, 10, 0), paths, 0, 80_000, taxableStart(1_464_100));

        assertThat(start.tent().total()).isCloseTo(80_000, EPS);
        assertThat(start.taxableAdjustment()).isCloseTo(-12_820, EPS);
    }

    @Test
    void fundedAtStart_firstPoolTooSmall_spillsToNextPool() {
        var start = TentBuildUp.fundedAtStart(plan(0, 0, 0), new double[4], 200_000, 50_000, 500_000, 0);

        assertThat(start.tent().totalIn(PoolType.TAXABLE)).isCloseTo(50_000, EPS);
        assertThat(start.tent().totalIn(PoolType.TRADITIONAL)).isCloseTo(150_000, EPS);
        assertThat(start.taxableAdjustment()).isZero();
    }
}
