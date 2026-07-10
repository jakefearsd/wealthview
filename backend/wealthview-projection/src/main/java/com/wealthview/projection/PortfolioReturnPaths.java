package com.wealthview.projection;

/**
 * Monte Carlo return trajectories for one optimization run.
 *
 * <p>The three per-pool arrays are {@code [trial][year]} <em>nominal</em> returns — each pool grows
 * at its own allocation/override-driven sequence, drawn from the shared joint-bootstrap index
 * sequence per trial. {@code portfolioPaths} is a {@code [trial][year+1]} cumulative
 * total-portfolio balance path (balance-weighted blend of all accounts) retained for the essential-
 * floor verification and spending-corridor calculators, which reason about the whole portfolio.
 */
record PortfolioReturnPaths(
        double[][] taxableReturns,
        double[][] traditionalReturns,
        double[][] rothReturns,
        double[][] portfolioPaths) {}
