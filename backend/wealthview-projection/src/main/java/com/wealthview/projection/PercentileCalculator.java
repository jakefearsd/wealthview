package com.wealthview.projection;

/**
 * Computes linear-interpolated percentiles over a pre-sorted {@code double[]}.
 *
 * <p>Extracted verbatim from {@code MonteCarloSpendingOptimizer} during the Phase 3
 * decomposition. The interpolation formula is unchanged — callers must pass an
 * already-sorted array, exactly as before.
 */
final class PercentileCalculator {

    private PercentileCalculator() {
    }

    /**
     * Returns the {@code p}-quantile (0.0–1.0) of an already-sorted array using
     * linear interpolation between the two nearest ranks. Returns 0 for an empty array.
     */
    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        double index = p * (sorted.length - 1);
        int lower = (int) Math.floor(index);
        int upper = Math.min(lower + 1, sorted.length - 1);
        double fraction = index - lower;
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower]);
    }
}
