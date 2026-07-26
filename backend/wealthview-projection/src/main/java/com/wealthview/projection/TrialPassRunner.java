package com.wealthview.projection;

import java.util.function.IntFunction;

import org.springframework.lang.Nullable;

/**
 * Runs one batch of {@code trialCount} Monte Carlo trials through {@link TrialSimulator} and
 * collects every per-trial output any of the three callers needs.
 *
 * <p>Task 12 (pattern-refactor): generalizes {@code SustainabilitySearch}'s original {@code
 * TrialBatch}/{@code runTrials} so {@link GuardrailResponseBuilder} and {@link
 * StochasticMortalityEvaluator} share the same trial loop instead of each keeping its own inline
 * copy. A caller that only needs one field of {@link PassResult} (e.g. {@link
 * StochasticMortalityEvaluator} only reads {@code successFlags}) simply ignores the rest --
 * collecting the unused ones is a few extra {@code trialCount}-sized array writes per trial, not
 * a behavior change (every field {@link TrialSimulator.TrialResult} exposes is always populated
 * regardless of {@code trackYearBalances}, except {@code yearBalances} itself).
 */
final class TrialPassRunner {

    private final TrialSimulator trialSimulator;

    TrialPassRunner(TrialSimulator trialSimulator) {
        this.trialSimulator = trialSimulator;
    }

    /**
     * One batch of {@code trialCount} trial runs. {@code yearBalances} is {@code
     * [years][trialCount]} (year-major, matching {@code GuardrailResponseBuilder}'s original
     * layout) and {@code null} unless {@code trackYearBalances} was requested.
     */
    record PassResult(double[] finalBalances, double[] minBalances, boolean[] successFlags,
                       boolean[] traditionalExhaustedFlags, double[][] yearBalances) {

        int successCount() {
            int count = 0;
            for (boolean success : successFlags) {
                if (success) {
                    count++;
                }
            }
            return count;
        }

        int traditionalExhaustedCount() {
            int count = 0;
            for (boolean exhausted : traditionalExhaustedFlags) {
                if (exhausted) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Runs {@code trialCount} trials of {@code configFactory}'s pass over the given
     * income/floors/discretionary schedule under the (possibly {@code null}) {@code adaptation}
     * rule. {@code householdOverrideFn}, when non-null, is consulted for EVERY trial ({@link
     * StochasticMortalityEvaluator}'s per-trial spliced household); every other caller passes
     * {@code null} and every trial uses {@code configFactory}'s own baked-in household unchanged.
     */
    PassResult run(int trialCount, int years, TrialConfigFactory configFactory,
                   double[] income, double[] surplusTax, double[] floors, double[] discretionary,
                   @Nullable TrialSimulator.GuardrailAdaptation adaptation, boolean trackYearBalances,
                   @Nullable IntFunction<TrialSimulator.HouseholdSim> householdOverrideFn) {
        double[] finalBalances = new double[trialCount];
        double[] minBalances = new double[trialCount];
        boolean[] successFlags = new boolean[trialCount];
        boolean[] traditionalExhaustedFlags = new boolean[trialCount];
        double[][] yearBalances = trackYearBalances ? new double[years][trialCount] : null;

        for (int t = 0; t < trialCount; t++) {
            TrialSimulator.HouseholdSim override = householdOverrideFn != null
                    ? householdOverrideFn.apply(t) : null;
            var config = configFactory.configFor(t, adaptation, trackYearBalances, override);
            var result = trialSimulator.simulateTrial(income, surplusTax, floors, discretionary, years, config);
            finalBalances[t] = result.finalBalance();
            minBalances[t] = result.minBalance();
            successFlags[t] = result.success();
            traditionalExhaustedFlags[t] = result.traditionalExhausted();
            if (trackYearBalances) {
                for (int y = 0; y < years; y++) {
                    yearBalances[y][t] = result.yearBalances()[y];
                }
            }
        }
        return new PassResult(finalBalances, minBalances, successFlags, traditionalExhaustedFlags, yearBalances);
    }
}
