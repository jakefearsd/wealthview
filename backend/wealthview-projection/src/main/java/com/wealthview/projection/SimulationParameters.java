package com.wealthview.projection;

/** MC simulation run parameters — how many trials, over what time horizon, with what returns. */
record SimulationParameters(
        int retirementYear, int retirementAge, int endAge, int years,
        int trialCount, double confidenceLevel, double inflationRate,
        double[][] portfolioPaths
) {}
