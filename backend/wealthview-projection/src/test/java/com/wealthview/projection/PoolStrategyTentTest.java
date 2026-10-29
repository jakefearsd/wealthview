package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.FilingStatus;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

class PoolStrategyTentTest {

    private static final Map<AssetClass, Double> CMA = Map.of(
            AssetClass.US_STOCK, 0.05, AssetClass.INTL_STOCK, 0.05, AssetClass.BOND, 0.02, AssetClass.CASH, 0.0);

    private static final List<ProjectionAccountInput> ACCOUNTS = List.of(
            new HypotheticalAccountInput(bd("1000000"), BigDecimal.ZERO, AssetAllocation.ALL_US, Optional.empty(),
                    "taxable"));

    private static PoolConfig config() {
        return new PoolConfig(FilingStatus.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null, CMA, BigDecimal.ZERO);
    }

    private static Map<AssetClass, Double> year(double usStock) {
        return Map.of(AssetClass.US_STOCK, usStock, AssetClass.INTL_STOCK, usStock, AssetClass.BOND, 0.0,
                AssetClass.CASH, 0.0);
    }

    private static Map<BondMaturity, BigDecimal> flat(String rate) {
        var map = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        for (BondMaturity maturity : BondMaturity.values()) {
            map.put(maturity, bd(rate));
        }
        return map;
    }

    private static Map<BondMaturity, BigDecimal> allLong() {
        var map = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        for (BondMaturity maturity : BondMaturity.values()) {
            map.put(maturity, maturity == BondMaturity.LONG ? BigDecimal.ONE : BigDecimal.ZERO);
        }
        return map;
    }

    private static PoolStrategy tentPool() {
        var pool = PoolStrategy.create(ACCOUNTS, config());
        pool.configureTent(new PoolStrategy.TentSetup(List.of(PoolType.TAXABLE), allLong(), flat("0"), flat("0")));
        return pool;
    }

    @Test
    void poolReturnsFor_sameInputs_matchesCreate() {
        var returns = PoolStrategy.poolReturnsFor(ACCOUNTS, CMA, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(returns.taxable()).isEqualByComparingTo(PoolStrategy.create(ACCOUNTS, config())
                .getWeightedReturn());
    }

    @Test
    void useYearReturns_growsPoolsAtTheYearsReturns() {
        var pool = PoolStrategy.create(ACCOUNTS, config());

        pool.useYearReturns(PoolStrategy.poolReturnsFor(ACCOUNTS, year(-0.30), BigDecimal.ZERO, BigDecimal.ZERO),
                Map.of());
        pool.applyGrowth(false);

        assertThat(pool.getTotal()).isEqualByComparingTo("700000.00");
    }

    @Test
    void useYearReturns_sleevesEarnTheYearsMaturityReturns() {
        var pool = tentPool();
        pool.topUpTent(bd("200000"));

        var sleeves = flat("0");
        sleeves.put(BondMaturity.LONG, bd("0.20"));
        pool.useYearReturns(PoolStrategy.poolReturnsFor(ACCOUNTS, year(-0.30), BigDecimal.ZERO, BigDecimal.ZERO),
                sleeves);
        pool.applyGrowth(false);

        assertThat(pool.getTotal()).isEqualByComparingTo("800000.00");
    }

    @Test
    void useYearReturns_leavesWeightedReturnAsExpected() {
        var pool = PoolStrategy.create(ACCOUNTS, config());
        var before = pool.getWeightedReturn();

        pool.useYearReturns(PoolStrategy.poolReturnsFor(ACCOUNTS, year(-0.30), BigDecimal.ZERO, BigDecimal.ZERO),
                Map.of());

        assertThat(pool.getWeightedReturn()).isEqualByComparingTo(before);
    }

    @Test
    void spendTent_noTent_returnsZero() {
        var pool = PoolStrategy.create(ACCOUNTS, config());

        assertThat(pool.spendTent(bd("1000"))).isEqualByComparingTo("0");
    }

    @Test
    void spendTent_withTent_spendsAtMostTheTent() {
        var pool = tentPool();
        pool.topUpTent(bd("200000"));

        assertThat(pool.spendTent(bd("50000"))).isEqualByComparingTo("50000");
        assertThat(pool.spendTent(bd("500000"))).isEqualByComparingTo("150000");
    }
}
