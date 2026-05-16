package com.wealthview.projection;

import java.util.HashSet;
import java.util.List;

import com.wealthview.core.projection.dto.GuardrailPhaseInput;

/**
 * Post-processing of an allocated discretionary-spending plan: phase-boundary
 * blending and year-over-year change smoothing.
 *
 * <p>Extracted from {@code MonteCarloSpendingOptimizer} during the Phase 3 decomposition.
 * Both methods mutate the {@code discretionary} array in place exactly as before;
 * the smoothing arithmetic is unchanged.
 */
final class SpendingSmoother {

    private SpendingSmoother() {
    }

    /**
     * Linearly blends total spending across each phase boundary so the transition
     * between phases is gradual rather than a step change. Mutates {@code discretionary}
     * in place.
     */
    static void applyPhaseBlending(double[] discretionary, double[] floors,
                                    List<GuardrailPhaseInput> phases,
                                    int retirementAge, int years, int blendYears) {
        double[] totalSpending = new double[years];
        for (int y = 0; y < years; y++) {
            totalSpending[y] = floors[y] + discretionary[y];
        }

        for (int p = 1; p < phases.size(); p++) {
            int boundaryAge = phases.get(p).startAge();
            int boundaryYear = boundaryAge - retirementAge;
            if (boundaryYear <= 0 || boundaryYear >= years) {
                continue;
            }

            int windowStart = Math.max(0, boundaryYear - blendYears);
            int windowEnd = Math.min(years - 1, boundaryYear + blendYears - 1);
            int windowLen = windowEnd - windowStart + 1;
            if (windowLen <= 1) {
                continue;
            }

            double startSpend = totalSpending[windowStart];
            double endSpend = totalSpending[windowEnd];

            for (int y = windowStart; y <= windowEnd; y++) {
                double t = (double) (y - windowStart) / (windowLen - 1);
                double blended = startSpend + t * (endSpend - startSpend);
                totalSpending[y] = blended;
                discretionary[y] = Math.max(0, blended - floors[y]);
            }
        }
    }

    /**
     * Caps year-over-year change in total spending to {@code maxRate}, except across
     * phase boundaries where spending is allowed to jump to the phase's allocated level.
     * Mutates {@code discretionary} in place.
     */
    static void applyYearOverYearSmoothing(double[] discretionary, double[] floors,
                                            double maxRate, int years,
                                            List<GuardrailPhaseInput> phases,
                                            int retirementAge) {
        // Build set of year indices where a new phase starts (skip first phase since
        // there's no prior year to smooth from)
        var phaseStartYears = new HashSet<Integer>();
        if (phases != null && phases.size() > 1) {
            for (int i = 1; i < phases.size(); i++) {
                int yearIdx = phases.get(i).startAge() - retirementAge;
                if (yearIdx > 0 && yearIdx < years) {
                    phaseStartYears.add(yearIdx);
                }
            }
        }

        double[] totalSpending = new double[years];
        for (int y = 0; y < years; y++) {
            totalSpending[y] = floors[y] + discretionary[y];
        }

        for (int y = 1; y < years; y++) {
            // At phase boundaries, allow spending to jump to the phase's allocated level
            if (phaseStartYears.contains(y)) {
                continue;
            }
            double maxUp = totalSpending[y - 1] * (1 + maxRate);
            double maxDown = totalSpending[y - 1] * (1 - maxRate);
            totalSpending[y] = Math.max(maxDown, Math.min(maxUp, totalSpending[y]));
            discretionary[y] = Math.max(0, totalSpending[y] - floors[y]);
        }
    }
}
