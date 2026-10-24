package com.wealthview.projection;

import org.springframework.lang.Nullable;

/**
 * Monte Carlo return trajectories for one optimization run.
 *
 * <p>The three per-pool arrays are {@code [trial][year]} <em>real</em> (today's-dollars) returns —
 * each pool grows
 * at its own allocation/override-driven sequence, drawn from the shared joint-bootstrap index
 * sequence per trial. {@code portfolioPaths} is a {@code [trial][year+1]} cumulative
 * total-portfolio balance path (balance-weighted blend of all accounts) retained for the essential-
 * floor verification and spending-corridor calculators, which reason about the whole portfolio.
 *
 * <p>{@code startBalances} holds each trial's balances at retirement start after its own
 * pre-retirement accumulation; it is {@code null} exactly when there are no pre-retirement years.
 *
 * <p>{@code tentPaths} is non-null only when a bond tent is configured (maturity data supplied).
 */
record PortfolioReturnPaths(
        double[][] taxableReturns,
        double[][] traditionalReturns,
        double[][] rothReturns,
        double[][] portfolioPaths,
        @Nullable TrialStartBalances[] startBalances,
        @Nullable TentPaths tentPaths) {

    /** Paths without tent data: {@code tentPaths} is {@code null}. */
    // UseVarargs: portfolioPaths is a [trial][year] matrix, not a variable argument list.
    @SuppressWarnings("PMD.UseVarargs")
    PortfolioReturnPaths(double[][] taxableReturns, double[][] traditionalReturns, double[][] rothReturns,
                         double[][] portfolioPaths, @Nullable TrialStartBalances[] startBalances) {
        this(taxableReturns, traditionalReturns, rothReturns, portfolioPaths, startBalances, null);
    }

    /** Paths with no pre-retirement prefix: {@code startBalances} is {@code null}. */
    // UseVarargs: portfolioPaths is a [trial][year] matrix, not a variable argument list.
    @SuppressWarnings("PMD.UseVarargs")
    PortfolioReturnPaths(double[][] taxableReturns, double[][] traditionalReturns, double[][] rothReturns,
                         double[][] portfolioPaths) {
        this(taxableReturns, traditionalReturns, rothReturns, portfolioPaths, null, null);
    }
}
