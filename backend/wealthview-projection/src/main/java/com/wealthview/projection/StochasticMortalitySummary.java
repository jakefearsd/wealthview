package com.wealthview.projection;

import java.util.Arrays;

/**
 * Sub-project B (stochastic mortality), task 7: aggregates one {@link StochasticMortalityEvaluation}
 * pass (the raw per-trial success flags + sampled death ages) into the user-facing summary -- the
 * lifetime (unconditional) success probability, the longevity-conditional success rate, and the
 * first/second death-age distributions. {@link #from} is a pure, read-only aggregation over
 * already-computed per-trial arrays: it runs no trials and never touches the recommendation.
 *
 * @param lifetimeSuccessProbability the UNCONDITIONAL success rate across every trial -- a trial
 *         whose household dies early (well inside the horizon) still counts as a success provided
 *         that trial's essential floor was met over its own (shorter, truncated) horizon; see
 *         {@link StochasticMortalityEvaluation#success}. Early death is never a "failure" by
 *         itself.
 * @param longevityConditional the success rate restricted to trials whose survivor reaches at
 *         least a given age ("if we live this long, how likely are we to be fine"), plus what
 *         fraction of all trials qualify for that subset -- see {@link LongevityConditional}.
 * @param firstDeathAge the distribution of the RAW sampled first-death age across every trial.
 * @param secondDeathAge the distribution of the RAW sampled survivor (second) death age across
 *         every trial.
 */
record StochasticMortalitySummary(
        double lifetimeSuccessProbability,
        LongevityConditional longevityConditional,
        AgeDistribution firstDeathAge,
        AgeDistribution secondDeathAge) {

    /**
     * A percentile summary of an age distribution, reported as whole years.
     *
     * <p>Percentile convention: the SAME linear-interpolation-between-nearest-ranks formula
     * already used elsewhere in this module for {@code percentile10Final} (see {@link
     * PercentileCalculator#percentile}) -- {@code index = p * (n - 1)} into the ascending-sorted
     * array, interpolating between the two bracketing elements, then rounded to the nearest whole
     * year (ages are reported as whole years, unlike the dollar figures {@link
     * PercentileCalculator} was originally written for). {@code p10}/{@code median}/{@code p90}
     * are {@code p = 0.10}/{@code 0.50}/{@code 0.90} respectively.
     */
    record AgeDistribution(int p10, int median, int p90) {}

    /**
     * The success rate conditional on the household surviving to a given age.
     *
     * @param age the age threshold ("at least one spouse alive at this age") that defined the
     *         subset -- {@code GuardrailOptimizationInput.longevityConditionalAge()}, or the
     *         engine's default (95) when the request omits it.
     * @param probability the success rate WITHIN the subset of trials whose survivor reached
     *         {@code age}; {@code 0} (never {@code NaN}) when no trial reached it -- an empty
     *         subset has no successes to divide by, not an undefined rate.
     * @param trialFraction what share of ALL trials qualified for the subset -- how much sample
     *         weight the {@code probability} figure itself carries (a fraction near 0 makes the
     *         conditional probability a thin, less-trustworthy sample); {@code 0} when no trials
     *         qualify.
     */
    record LongevityConditional(int age, double probability, double trialFraction) {}

    /**
     * Aggregates one stochastic-mortality evaluation pass. {@code success}/{@code firstDeathAge}/
     * {@code secondDeathAge} are index-aligned per-trial arrays (same length, one entry per Monte
     * Carlo trial) -- see {@link StochasticMortalityEvaluation}.
     */
    static StochasticMortalitySummary from(boolean[] success, int[] firstDeathAge,
                                            int[] secondDeathAge, int longevityAge) {
        int total = success.length;

        int lifetimeSuccesses = 0;
        for (boolean trialSuccess : success) {
            if (trialSuccess) {
                lifetimeSuccesses++;
            }
        }
        double lifetimeSuccessProbability = total > 0 ? (double) lifetimeSuccesses / total : 0.0;

        int longevityTrials = 0;
        int longevitySuccesses = 0;
        for (int t = 0; t < total; t++) {
            if (secondDeathAge[t] >= longevityAge) {
                longevityTrials++;
                if (success[t]) {
                    longevitySuccesses++;
                }
            }
        }
        double longevityProbability = longevityTrials > 0
                ? (double) longevitySuccesses / longevityTrials : 0.0;
        double trialFraction = total > 0 ? (double) longevityTrials / total : 0.0;

        return new StochasticMortalitySummary(
                lifetimeSuccessProbability,
                new LongevityConditional(longevityAge, longevityProbability, trialFraction),
                distribution(firstDeathAge),
                distribution(secondDeathAge));
    }

    // UseVarargs: ages is one trial-indexed array (firstDeathAge/secondDeathAge), not a variable
    // argument list.
    @SuppressWarnings("PMD.UseVarargs")
    private static AgeDistribution distribution(int[] ages) {
        int[] sorted = ages.clone();
        Arrays.sort(sorted);
        double[] sortedAges = Arrays.stream(sorted).asDoubleStream().toArray();
        return new AgeDistribution(
                percentileAge(sortedAges, 0.10),
                percentileAge(sortedAges, 0.50),
                percentileAge(sortedAges, 0.90));
    }

    private static int percentileAge(double[] sortedAges, double p) {
        return (int) Math.round(PercentileCalculator.percentile(sortedAges, p));
    }
}
