package com.wealthview.projection;

/**
 * MC simulation run parameters — how many trials, over what time horizon, with what returns.
 *
 * <p>{@code portfolioPaths} is the blended cumulative total-portfolio path (floor/corridor calcs);
 * {@code taxableReturns}/{@code traditionalReturns}/{@code rothReturns} are the per-trial per-pool
 * nominal return sequences the trial simulator grows each pool at.
 */
record SimulationParameters(
        int retirementYear, int retirementAge, int endAge, int years,
        int trialCount, double confidenceLevel, double inflationRate,
        double[][] portfolioPaths,
        double[][] taxableReturns, double[][] traditionalReturns, double[][] rothReturns
) {}
