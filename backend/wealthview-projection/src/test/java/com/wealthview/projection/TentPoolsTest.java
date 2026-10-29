package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

class TentPoolsTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static Map<BondMaturity, BigDecimal> ultrashortOnly() {
        var map = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        for (BondMaturity maturity : BondMaturity.values()) {
            map.put(maturity, maturity == BondMaturity.ULTRASHORT ? BigDecimal.ONE : ZERO);
        }
        return map;
    }

    private static Map<BondMaturity, BigDecimal> flat(String rate) {
        var map = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        for (BondMaturity maturity : BondMaturity.values()) {
            map.put(maturity, bd(rate));
        }
        return map;
    }

    private static MultiPool pool(String taxable, String trad, String roth) {
        Map<PoolType, List<ProjectionAccountInput>> accounts = Map.of(
                PoolType.TAXABLE, List.of(new HypotheticalAccountInput(bd(taxable), ZERO, ZERO, "taxable")),
                PoolType.TRADITIONAL, List.of(new HypotheticalAccountInput(bd(trad), ZERO, ZERO, "traditional")),
                PoolType.ROTH, List.of(new HypotheticalAccountInput(bd(roth), ZERO, ZERO, "roth")));
        return new MultiPool(accounts, ZERO, ZERO, ZERO, ZERO,
                PoolFixtures.singleFilerConfig(WithdrawalOrder.TAXABLE_FIRST));
    }

    private static MultiPool tentPool(String taxable, String trad, String roth) {
        var pool = pool(taxable, trad, roth);
        pool.configureTent(new PoolStrategy.TentSetup(
                List.of(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH), ultrashortOnly(), flat("0"),
                flat("0")));
        return pool;
    }

    @Test
    void topUp_capped_reservesAtMostTheCap() {
        var pool = tentPool("500000", "0", "0");

        pool.tentPools().topUp(bd("90000"), bd("10000"));

        assertThat(pool.tentTotal()).isEqualByComparingTo("10000");
    }

    @Test
    void topUp_nullCap_reachesTheTarget() {
        var pool = tentPool("500000", "0", "0");

        pool.tentPools().topUp(bd("90000"), null);

        assertThat(pool.tentTotal()).isEqualByComparingTo("90000");
    }

    @Test
    void growthPartReturn_weightsUnreservedBalances() {
        var pool = tentPool("300000", "200000", "0");
        pool.topUpTent(bd("100000"));

        var g = pool.tentPools().growthPartReturn(bd("0.10"), ZERO, ZERO);

        assertThat(g).isEqualByComparingTo("0.05");
    }

    @Test
    void growthPartReturn_noGrowthPartLeft_isZero() {
        var pool = tentPool("100000", "0", "0");
        pool.topUpTent(bd("100000"));

        var g = pool.tentPools().growthPartReturn(bd("0.10"), bd("0.10"), bd("0.10"));

        assertThat(g).isEqualByComparingTo("0");
    }
}
