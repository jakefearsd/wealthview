package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentMix;
import com.wealthview.core.projection.dto.BondTentMixSizing;
import com.wealthview.core.projection.dto.BondTentSizing;
import com.wealthview.core.projection.dto.BondTentSizingReason;
import com.wealthview.core.projection.strategy.WithdrawalOrder;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

class BondTentPlannerRunForTest {

    private static final BondMaturityValues SHORT_ONLY = new BondMaturityValues(bd("0"), bd("1"), bd("0"), bd("0"));
    private static final BondMaturityValues LONG_ONLY = new BondMaturityValues(bd("0"), bd("0"), bd("0"), bd("1"));
    private static final BondMaturityValues YIELDS = new BondMaturityValues(bd("0.03"), bd("0.04"), bd("0.045"),
            bd("0.05"));

    private static BondTentConfig config() {
        return new BondTentConfig(bd("5"), 3,
                List.of(new BondTentMix("Short", SHORT_ONLY), new BondTentMix("Long", LONG_ONLY)), YIELDS);
    }

    private static BondTentPlanner.Sized sized() {
        var mixes = List.of(mixSizing("Short", "100000"), mixSizing("Long", "200000"));
        var sizing = new BondTentSizing(bd("1000000"), bd("5"), mixes, List.of());
        return new BondTentPlanner.Sized(sizing, bd("100000"));
    }

    private static BondTentMixSizing mixSizing(String name, String target) {
        return new BondTentMixSizing(name, bd("0.01"), bd(target), bd("0.1"), bd("50000"),
                BondTentSizingReason.SPENDING_LEVEL, bd("1"));
    }

    private static Map<BondMaturity, Double> realReturns() {
        var map = new EnumMap<BondMaturity, Double>(BondMaturity.class);
        for (var maturity : BondMaturity.values()) {
            map.put(maturity, 0.01);
        }
        return map;
    }

    @Test
    void runFor_buildUpCarriesATarget_buildsToThatTarget() {
        var buildUp = BondTentPlanner.BuildUp.starting(2026, 2029, 3, bd("123456"));

        var run = BondTentPlanner.runFor(sized(), config(), 1, buildUp, realReturns(), BigDecimal.ZERO,
                WithdrawalOrder.TAXABLE_FIRST);

        assertThat(run.buildUp().target()).isEqualByComparingTo("123456");
    }

    @Test
    void runFor_mixIndex_selectsThatMixsSharesAndKeepsTheLineGeometry() {
        var buildUp = BondTentPlanner.BuildUp.starting(2026, 2029, 3, bd("200000"));

        var run = BondTentPlanner.runFor(sized(), config(), 1, buildUp, realReturns(), BigDecimal.ZERO,
                WithdrawalOrder.TAXABLE_FIRST);

        assertThat(run.setup().mixShares().get(BondMaturity.LONG)).isEqualByComparingTo("1");
        assertThat(run.buildUp().start()).isEqualTo(2026);
        assertThat(run.buildUp().retirementYear()).isEqualTo(2029);
    }
}
