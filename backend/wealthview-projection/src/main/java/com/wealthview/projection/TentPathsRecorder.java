package com.wealthview.projection;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.BondMaturityReturnsProvider.MaturityReturnMatrix;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.PoolType;

/**
 * Collects {@link TentPaths} while {@link PortfolioPathGenerator} runs its trials. It only reads the index
 * sequences the generator already drew, so it adds no random draw. Used only when a bond tent is configured.
 */
final class TentPathsRecorder {

    private static final int MATURITIES = BondMaturity.values().length;
    private static final int POOLS = PoolType.values().length;

    private final MaturityReturnMatrix maturities;
    private final double feeRate;
    private final int preYears;
    private final double[][] sleeveReturns;
    private final double[][] poolsBefore;
    private final double[][] poolsAfter;

    private TentPathsRecorder(MaturityReturnMatrix maturities, int trialCount, int preYears, double feeRate) {
        this.maturities = maturities;
        this.feeRate = feeRate;
        this.preYears = preYears;
        this.sleeveReturns = new double[trialCount][];
        this.poolsBefore = poolGrid(trialCount, preYears);
        this.poolsAfter = poolGrid(trialCount, preYears);
    }

    // ReturnEmptyCollectionRatherThanNull: null is the documented 'no pre-retirement years' value of
    // TentPaths' pool grids, not an empty-collection stand-in.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    @Nullable
    private static double[][] poolGrid(int trialCount, int preYears) {
        if (preYears > 0) {
            return new double[trialCount][];
        }
        return null;
    }

    /**
     * A recorder for the run, or {@code null} when no maturity data is supplied.
     *
     * @throws IllegalArgumentException if the maturity matrix does not have one row per base-matrix row
     */
    @Nullable
    static TentPathsRecorder create(@Nullable MaturityReturnMatrix maturities, int trialCount, int preYears,
                                    int historicalSize, double feeRate) {
        if (maturities == null) {
            return null;
        }
        if (maturities.realReturns().length != historicalSize) {
            throw new IllegalArgumentException("Maturity return matrix has " + maturities.realReturns().length
                    + " rows but the base return matrix has " + historicalSize);
        }
        return new TentPathsRecorder(maturities, trialCount, preYears, feeRate);
    }

    /** Records trial {@code trial}: its sleeve returns for {@code sequence}, plus fresh pool rows if any pre-years. */
    // UseVarargs: the argument is a trial's bootstrap index sequence, not a variable argument list.
    @SuppressWarnings("PMD.UseVarargs")
    void startTrial(int trial, int[] sequence) {
        double[] row = new double[sequence.length * MATURITIES];
        for (int y = 0; y < sequence.length; y++) {
            double[] source = maturities.realReturns()[sequence[y]];
            for (int m = 0; m < MATURITIES; m++) {
                row[y * MATURITIES + m] = source[m] - feeRate;
            }
        }
        sleeveReturns[trial] = row;
        if (poolsBefore != null) {
            poolsBefore[trial] = new double[preYears * POOLS];
            poolsAfter[trial] = new double[preYears * POOLS];
        }
    }

    /** Adds one account's year into its pool's before-growth (after contributions) and after-growth totals. */
    void addAccountYear(int trial, int year, PoolType pool, double before, double after) {
        if (poolsBefore != null) {
            poolsBefore[trial][year * POOLS + pool.ordinal()] += before;
            poolsAfter[trial][year * POOLS + pool.ordinal()] += after;
        }
    }

    TentPaths build() {
        return new TentPaths(sleeveReturns, poolsBefore, poolsAfter);
    }
}
