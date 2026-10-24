package com.wealthview.projection;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.PoolType;

/**
 * What {@link PortfolioPathGenerator} records, per trial, for the Monte Carlo bond tent: the four sleeve
 * returns for every year (pre-retirement then retirement, net of fee) and the three pool totals before and
 * after each pre-retirement year's growth. All of it derives from the trial's existing bootstrap index
 * sequence, so the tent adds no random draw.
 *
 * <p>Layouts: {@code sleeveReturns[t][yearFromToday * 4 + maturity.ordinal()]};
 * {@code pools*[t][yearFromToday * 3 + pool.ordinal()]}, over the pre-retirement years only (both pool
 * arrays are {@code null} when there are none). "Before growth" is the pool's balances plus that year's
 * contributions; "after growth" is the same accounts after the year's growth.
 */
record TentPaths(double[][] sleeveReturns, @Nullable double[][] poolsBeforeGrowth,
                 @Nullable double[][] poolsAfterGrowth) {

    private static final int MATURITIES = BondMaturity.values().length;
    private static final int POOLS = PoolType.values().length;

    double[] sleeveReturnsOf(int trial) {
        return sleeveReturns[trial];
    }

    double sleeveReturn(int trial, int yearFromToday, BondMaturity maturity) {
        return sleeveReturns[trial][yearFromToday * MATURITIES + maturity.ordinal()];
    }

    double poolBeforeGrowth(int trial, int yearFromToday, PoolType pool) {
        return required(poolsBeforeGrowth)[trial][yearFromToday * POOLS + pool.ordinal()];
    }

    double poolAfterGrowth(int trial, int yearFromToday, PoolType pool) {
        return required(poolsAfterGrowth)[trial][yearFromToday * POOLS + pool.ordinal()];
    }

    // UseVarargs: the argument is a trial-by-year grid, not a variable argument list.
    @SuppressWarnings("PMD.UseVarargs")
    private static double[][] required(@Nullable double[][] pools) {
        if (pools == null) {
            throw new IllegalStateException("no pre-retirement years were recorded");
        }
        return pools;
    }
}
