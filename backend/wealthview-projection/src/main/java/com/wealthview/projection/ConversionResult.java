package com.wealthview.projection;

/** Result of the Roth-conversion optimization: per-year conversions, their taxes, and the schedule. */
record ConversionResult(
        double[] byYear, double[] taxByYear,
        RothConversionOptimizer.RothConversionSchedule schedule
) {}
