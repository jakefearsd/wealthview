package com.wealthview.projection;

/**
 * Finds the conversion fraction that minimizes lifetime tax while preferring
 * feasible schedules (traditional balance at RMD start within the target).
 *
 * <p>Uses a hybrid grid scan over {@value #GRID_SIZE} candidate fractions plus
 * ternary refinement around the best grid point. Extracted from
 * {@link RothConversionOptimizer}; delegates each candidate evaluation to a
 * {@link ConversionSimulator}.
 *
 * <p>Pure deterministic search — no RNG.
 */
final class FractionSearch {

    private static final int GRID_SIZE = 50;
    private static final double REFINE_HALF_WIDTH = 0.05;
    private static final int REFINE_ITERATIONS = 20;
    private static final double FEASIBILITY_TOLERANCE = 1.05;
    private static final double INFEASIBLE_PENALTY = 1e12;

    private final ConversionSimulator simulator;
    private final double targetTraditionalBalance;
    private final int rmdYearIndex;

    FractionSearch(ConversionSimulator simulator, double targetTraditionalBalance,
                   int rmdStartAge, int retirementAge) {
        this.simulator = simulator;
        this.targetTraditionalBalance = targetTraditionalBalance;
        this.rmdYearIndex = rmdStartAge - retirementAge;
    }

    /**
     * Returns the conversion fraction that minimizes lifetime tax.
     *
     * @param baseline the no-conversion simulation result (fraction 0.0)
     */
    double findOptimalFraction(SimResult baseline) {
        double bestFraction = 0.0;
        double bestScore = baseline.lifetimeTax();
        boolean bestFeasible = isFeasible(baseline);

        for (int i = 1; i <= GRID_SIZE; i++) {
            double fraction = (double) i / GRID_SIZE;
            var result = simulator.simulateForFraction(fraction);
            boolean feasible = isFeasible(result);

            if (isBetterCandidate(result.lifetimeTax(), feasible, bestScore, bestFeasible)) {
                bestScore = result.lifetimeTax();
                bestFraction = fraction;
                bestFeasible = feasible;
            }
        }

        double lo = Math.max(0.0, bestFraction - REFINE_HALF_WIDTH);
        double hi = Math.min(1.0, bestFraction + REFINE_HALF_WIDTH);

        for (int iter = 0; iter < REFINE_ITERATIONS; iter++) {
            double m1 = lo + (hi - lo) / 3.0;
            double m2 = hi - (hi - lo) / 3.0;

            var r1 = simulator.simulateForFraction(m1);
            var r2 = simulator.simulateForFraction(m2);
            boolean f1 = isFeasible(r1);
            boolean f2 = isFeasible(r2);

            double s1 = scoringValue(r1.lifetimeTax(), f1);
            double s2 = scoringValue(r2.lifetimeTax(), f2);

            if (s1 < s2) {
                hi = m2;
            } else {
                lo = m1;
            }

            if (isBetterCandidate(r1.lifetimeTax(), f1, bestScore, bestFeasible)) {
                bestScore = r1.lifetimeTax();
                bestFraction = m1;
                bestFeasible = f1;
            }
            if (isBetterCandidate(r2.lifetimeTax(), f2, bestScore, bestFeasible)) {
                bestScore = r2.lifetimeTax();
                bestFraction = m2;
                bestFeasible = f2;
            }
        }

        return bestFraction;
    }

    /**
     * A simulation result is feasible if the traditional balance at RMD start age
     * is at or below the target balance (with 5% tolerance). If there's no target
     * (e.g., already past RMD age), any result is feasible.
     */
    private boolean isFeasible(SimResult result) {
        if (targetTraditionalBalance <= 0) {
            return true;
        }
        if (rmdYearIndex < 0 || rmdYearIndex >= result.traditionalBalance().length) {
            return true;
        }
        return result.traditionalBalance()[rmdYearIndex]
                <= targetTraditionalBalance * FEASIBILITY_TOLERANCE;
    }

    private boolean isBetterCandidate(double tax, boolean feasible, double bestTax, boolean bestFeasible) {
        if (feasible && !bestFeasible) {
            return true;
        }

        if (!feasible && bestFeasible) {
            return false;
        }
        return tax < bestTax;
    }

    private double scoringValue(double tax, boolean feasible) {
        return feasible ? tax : tax + INFEASIBLE_PENALTY;
    }
}
