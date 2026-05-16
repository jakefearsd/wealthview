package com.wealthview.projection;

/**
 * Outcome of a single Roth-conversion simulation run for a fixed conversion fraction.
 *
 * <p>Shared between {@link ConversionSimulator} (producer), {@link FractionSearch}
 * (consumer during the grid scan), and {@link RothConversionOptimizer} (assembles
 * the public {@code RothConversionSchedule} from it).
 */
record SimResult(
        double[] conversionByYear,
        double[] conversionTaxByYear,
        double[] traditionalBalance,
        double[] rothBalance,
        double[] taxableBalance,
        double[] projectedRmd,
        double lifetimeTax,
        int exhaustionAge
) {}
