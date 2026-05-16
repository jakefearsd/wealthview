package com.wealthview.projection;

import java.util.Random;

/**
 * Generates Monte Carlo portfolio trajectories for the spending optimizer using
 * block-bootstrap resampling of historical real returns.
 *
 * <p>Extracted from {@code MonteCarloSpendingOptimizer} during the Phase 3 decomposition.
 * Bootstrap returns are real (CPI-adjusted); they are converted to nominal via the Fisher
 * equation so portfolio growth matches the optimizer's nominal spending/income model.
 *
 * <p>This class is stateless. Determinism is the caller's responsibility — the supplied
 * {@link Random} is the single source of randomness and must be seeded by the caller.
 */
final class PortfolioPathGenerator {

    private static final double DEFAULT_BLOCK_LENGTH = 5.0;

    private PortfolioPathGenerator() {
    }

    /**
     * Runs {@code trialCount} bootstrap trials (no withdrawals) and returns a
     * {@code [trialCount][years + 1]} array of cumulative portfolio balances —
     * column 0 is the initial portfolio, column {@code y + 1} is the balance after year {@code y}.
     */
    static double[][] generatePaths(int trialCount, int years, double initialPortfolio,
                                     double[] historicalReturns, Random rng,
                                     double inflationRate) {
        double[][] paths = new double[trialCount][years + 1];
        for (int t = 0; t < trialCount; t++) {
            var generator = new BlockBootstrapReturnGenerator(historicalReturns, DEFAULT_BLOCK_LENGTH, rng);
            double[] returnSequence = generator.generateReturnSequence(years);
            paths[t][0] = initialPortfolio;
            for (int y = 0; y < years; y++) {
                double nominalReturn = toNominal(returnSequence[y], inflationRate);
                double growthFactor = 1 + nominalReturn;
                paths[t][y + 1] = paths[t][y] * growthFactor;
            }
        }
        return paths;
    }

    /**
     * Draws a single bootstrap return sequence of length {@code years} and converts it to
     * nominal returns. Advances {@code rng} exactly as one trial of {@link #generatePaths} would.
     */
    static double[] generateNominalReturns(int years, double[] historicalReturns,
                                            Random rng, double inflationRate) {
        var generator = new BlockBootstrapReturnGenerator(historicalReturns, DEFAULT_BLOCK_LENGTH, rng);
        double[] returnSequence = generator.generateReturnSequence(years);
        double[] nominalReturns = new double[years];
        for (int y = 0; y < years; y++) {
            nominalReturns[y] = toNominal(returnSequence[y], inflationRate);
        }
        return nominalReturns;
    }

    /** Converts a real (CPI-adjusted) return to a nominal return via the Fisher equation. */
    static double toNominal(double realReturn, double inflationRate) {
        return (1 + realReturn) * (1 + inflationRate) - 1;
    }
}
