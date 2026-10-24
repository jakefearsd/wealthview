package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentMix;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.strategy.WithdrawalOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TrialTentPlanTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static BondTentConfig config(int buildUp) {
        var yields = new BondMaturityValues(bd("0.0394"), bd("0.0465"), bd("0.048"), bd("0.0539"));
        var shares = new BondMaturityValues(bd("0.2"), bd("0.3"), bd("0.5"), bd("0"));
        return new BondTentConfig(bd("5"), buildUp, List.of(new BondTentMix("Balanced", shares)), yields);
    }

    private static TrialTentPlan plan(int pre, int buildUp) {
        return TrialTentPlan.of(config(buildUp), WithdrawalOrder.TAXABLE_FIRST, 0.025, pre, 0, null);
    }

    @Test
    void of_balancedMix_resolvesSharesYieldsPlacementAndRealYield() {
        var plan = TrialTentPlan.of(config(3), WithdrawalOrder.TRADITIONAL_FIRST, 0.025, 10, 12_000, null);

        assertThat(plan.coverageYears()).isEqualTo(5.0);
        assertThat(plan.mixShares()).containsExactly(0.2, 0.3, 0.5, 0.0);
        assertThat(plan.nominalYields()).containsExactly(0.0394, 0.0465, 0.048, 0.0539);
        assertThat(plan.placementOrder())
                .containsExactly(PoolType.TRADITIONAL, PoolType.TAXABLE, PoolType.ROTH);
        assertThat(plan.sizingRealYield()).isEqualTo(0.0203219512, within(1e-9));
        assertThat(plan.buildUpStartIndex()).isEqualTo(7);
        assertThat(plan.contributionCap()).isEqualTo(12_000);
    }

    @Test
    void lineLevel_threeYearBuildUp_isAStraightLine() {
        var plan = plan(3, 3);

        assertThat(plan.lineLevel(0, 90_000)).isZero();
        assertThat(plan.lineLevel(1, 90_000)).isEqualTo(30_000, within(1e-9));
        assertThat(plan.lineLevel(2, 90_000)).isEqualTo(60_000, within(1e-9));
        assertThat(plan.lineLevel(3, 90_000)).isEqualTo(90_000, within(1e-9));
    }

    @Test
    void lineLevel_buildUpBeforeToday_compressesTheRamp() {
        var plan = plan(4, 10);

        assertThat(plan.buildUpStartIndex()).isZero();
        assertThat(plan.lineLevel(1, 80_000)).isEqualTo(20_000, within(1e-9));
    }

    @Test
    void lineLevel_buildUpZero_isTheFullTargetAtRetirement() {
        var plan = plan(3, 0);

        assertThat(plan.buildUpSpan()).isZero();
        assertThat(plan.lineLevel(2, 90_000)).isZero();
        assertThat(plan.lineLevel(3, 90_000)).isEqualTo(90_000);
    }

    @Test
    void withMix_usesThatMixsSharesAndRealYield() {
        var plan = plan(3, 3);
        var yields = config(3).yields();
        var cash = new BondTentMix("Cash", new BondMaturityValues(bd("1"), bd("0"), bd("0"), bd("0")));

        var swapped = plan.withMix(cash, yields, 0.025);

        assertThat(swapped.mixShares()).containsExactly(1.0, 0.0, 0.0, 0.0);
        assertThat(swapped.sizingRealYield()).isEqualTo((1.0394 / 1.025) - 1.0, within(1e-12));
        assertThat(swapped.coverageYears()).isEqualTo(plan.coverageYears());
        assertThat(swapped.buildUpStartIndex()).isEqualTo(plan.buildUpStartIndex());
        assertThat(swapped.nominalYields()).containsExactly(plan.nominalYields());
    }
}
