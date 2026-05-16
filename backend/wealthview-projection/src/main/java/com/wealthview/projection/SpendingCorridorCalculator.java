package com.wealthview.projection;

import java.util.Arrays;

/**
 * Computes and smooths the per-year guardrail spending corridor (a low/high band
 * around the recommended spend) from Monte Carlo portfolio paths.
 *
 * <p>Extracted from {@code MonteCarloSpendingOptimizer} during the Phase 3 decomposition.
 * The corridor and smoothing arithmetic is unchanged — this class isolates the
 * corridor-aggregation concern from the optimizer's orchestration.
 */
final class SpendingCorridorCalculator {

    private SpendingCorridorCalculator() {
    }

    /**
     * Computes the spending corridor (low/high bands) for each retirement year from
     * pre-generated portfolio paths. Returns a {@code [2][years]} array — index 0 is
     * the corridor low, index 1 is the corridor high.
     */
    static double[][] computeCorridors(double[][] paths, double[] income,
                                        double[] floors, double[] discretionary,
                                        int years, int trialCount) {
        double[] corridorLow = new double[years];
        double[] corridorHigh = new double[years];

        // Maintain running balances per trial, updated incrementally each year.
        // This avoids re-simulating all prior years for each year (O(n²) → O(n)).
        double[] runningBalance = new double[trialCount];
        for (int t = 0; t < trialCount; t++) {
            runningBalance[t] = paths[t][0];
        }

        double[] sustainableAtYear = new double[trialCount];
        for (int y = 0; y < years; y++) {
            double baseSpending = floors[y] + discretionary[y];

            for (int t = 0; t < trialCount; t++) {
                double gf = paths[t][y + 1] / paths[t][y];
                double availableThisYear = runningBalance[t] * gf + income[y];
                sustainableAtYear[t] = availableThisYear;
            }
            Arrays.sort(sustainableAtYear);

            double p10 = PercentileCalculator.percentile(sustainableAtYear, 0.10);
            double p90 = PercentileCalculator.percentile(sustainableAtYear, 0.90);

            corridorLow[y] = Math.max(floors[y], Math.min(p10, baseSpending));
            corridorHigh[y] = Math.max(baseSpending, Math.min(p90, baseSpending * 3));

            // Advance running balances: apply growth and withdraw spending
            double withdrawal = Math.max(0, baseSpending - income[y]);
            for (int t = 0; t < trialCount; t++) {
                double gf = paths[t][y + 1] / paths[t][y];
                runningBalance[t] = Math.max(0, runningBalance[t] * gf - withdrawal);
            }
        }

        return new double[][]{corridorLow, corridorHigh};
    }

    /**
     * Smooths the corridor low/high bands in place with a 3-point moving average,
     * keeping the endpoints fixed. Collapses any inverted band to its midpoint.
     * A no-op when there are fewer than three years.
     */
    static void smoothCorridors(double[] corridorLow, double[] corridorHigh, int years) {
        if (years < 3) {
            return;
        }
        double[] smoothLow = new double[years];
        double[] smoothHigh = new double[years];

        smoothLow[0] = corridorLow[0];
        smoothHigh[0] = corridorHigh[0];
        smoothLow[years - 1] = corridorLow[years - 1];
        smoothHigh[years - 1] = corridorHigh[years - 1];

        for (int y = 1; y < years - 1; y++) {
            smoothLow[y] = (corridorLow[y - 1] + corridorLow[y] + corridorLow[y + 1]) / 3.0;
            smoothHigh[y] = (corridorHigh[y - 1] + corridorHigh[y] + corridorHigh[y + 1]) / 3.0;
        }

        System.arraycopy(smoothLow, 0, corridorLow, 0, years);
        System.arraycopy(smoothHigh, 0, corridorHigh, 0, years);

        for (int y = 0; y < years; y++) {
            if (corridorLow[y] > corridorHigh[y]) {
                double avg = (corridorLow[y] + corridorHigh[y]) / 2.0;
                corridorLow[y] = avg;
                corridorHigh[y] = avg;
            }
        }
    }
}
