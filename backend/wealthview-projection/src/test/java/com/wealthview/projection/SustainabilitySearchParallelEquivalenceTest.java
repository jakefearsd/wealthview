package com.wealthview.projection;

import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parallel-vs-sequential equivalence safety-net for {@link SustainabilitySearch#isSustainable}.
 *
 * <p>This is the strongest proof that parallelizing the trial loop preserves behavior: the
 * SAME {@link SustainabilitySearch.SearchContext} is evaluated with the trial loop forced
 * sequential and forced parallel, and the boolean verdict at the binary-search boundary must
 * match exactly. Because the portfolio paths matrix is generated (seeded) before the loop and
 * each trial is a pure function of its fixed path writing to a distinct result index, the two
 * modes must agree byte-for-byte regardless of thread interleaving.
 *
 * <p>The {@link SustainabilitySearch#parallelTrials} static seam and the
 * {@code PARALLEL_MIN_TRIALS} threshold gate parallelism; this test flips the seam and uses a
 * trial count above the threshold so both modes are genuinely exercised. The seam is reset in
 * teardown to avoid cross-test leakage.
 */
class SustainabilitySearchParallelEquivalenceTest {

    private static final int YEARS = 28;
    private static final int RETIREMENT_AGE = 62;
    private final SustainabilitySearch search = new SustainabilitySearch(new TrialSimulator());

    @AfterEach
    void resetSeam() {
        SustainabilitySearch.parallelTrials = true;
    }

    /** Builds a realistic seeded SearchContext with {@code trialCount} portfolio paths. */
    private SustainabilitySearch.SearchContext context(int trialCount, boolean withPools) {
        double[] historicalReturns = HistoricalReturns.getReturns();
        double[][] paths = PortfolioPathGenerator.generatePaths(
                trialCount, YEARS, 1_500_000, historicalReturns, new Random(20260516L), 0.03);

        double[] income = new double[YEARS];
        double[] surplusTax = new double[YEARS];
        double[] conversionByYear = new double[YEARS];
        double[] conversionTaxByYear = new double[YEARS];
        double[] dsBracketCeilingByYear = new double[YEARS];

        TaxContext taxCtx = null;
        if (withPools) {
            double[] marginalRates = new double[YEARS];
            for (int y = 0; y < YEARS; y++) {
                marginalRates[y] = 0.22;
            }
            taxCtx = new TaxContext(700_000, 600_000, 200_000, "taxable_first", marginalRates);
        }

        return new SustainabilitySearch.SearchContext(
                paths, income, surplusTax,
                100_000, RETIREMENT_AGE, YEARS, trialCount,
                0.90, withPools ? 50_000 : 0,
                5, 0.02, 0.03,
                taxCtx, conversionByYear, conversionTaxByYear, dsBracketCeilingByYear);
    }

    private double[] floors() {
        double[] f = new double[YEARS];
        for (int y = 0; y < YEARS; y++) {
            f[y] = 40_000;
        }
        return f;
    }

    private double[] discretionary(double level) {
        double[] d = new double[YEARS];
        for (int y = 0; y < YEARS; y++) {
            d[y] = level;
        }
        return d;
    }

    private boolean run(SustainabilitySearch.SearchContext ctx, double[] disc, boolean parallel) {
        SustainabilitySearch.parallelTrials = parallel;
        return search.isSustainable(ctx, floors(), disc);
    }

    @Test
    void isSustainable_parallelVsSequential_agreeAcrossSpendingLevels_nonPooled() {
        var ctx = context(1024, false);
        // Sweep a range of discretionary levels straddling the sustainability boundary so we
        // exercise both true and false verdicts in both modes.
        for (double level = 0; level <= 200_000; level += 5_000) {
            double[] disc = discretionary(level);
            boolean sequential = run(ctx, disc, false);
            boolean parallel = run(ctx, disc, true);
            assertThat(parallel)
                    .as("verdict mismatch at discretionary level %.0f (non-pooled)", level)
                    .isEqualTo(sequential);
        }
    }

    @Test
    void isSustainable_parallelVsSequential_agreeAcrossSpendingLevels_pooledWithFloor() {
        var ctx = context(1024, true);
        for (double level = 0; level <= 200_000; level += 5_000) {
            double[] disc = discretionary(level);
            boolean sequential = run(ctx, disc, false);
            boolean parallel = run(ctx, disc, true);
            assertThat(parallel)
                    .as("verdict mismatch at discretionary level %.0f (pooled+floor)", level)
                    .isEqualTo(sequential);
        }
    }

    @Test
    void isSustainable_bothModes_exerciseTrueAndFalseVerdicts() {
        // Guards against a vacuous equivalence test: confirm the sweep actually produced
        // BOTH verdicts (otherwise "agreement" could be trivially all-true or all-false).
        var ctx = context(1024, false);
        boolean sawTrue = false;
        boolean sawFalse = false;
        for (double level = 0; level <= 300_000; level += 10_000) {
            boolean verdict = run(ctx, discretionary(level), true);
            sawTrue |= verdict;
            sawFalse |= !verdict;
        }
        assertThat(sawTrue).as("expected at least one sustainable verdict").isTrue();
        assertThat(sawFalse).as("expected at least one unsustainable verdict").isTrue();
    }

    @Test
    void isSustainable_trialCountOne_parallelMatchesSequential() {
        // Edge case: a single trial. PARALLEL_MIN_TRIALS will keep this sequential internally,
        // but the verdict must still be correct and identical when the seam is flipped.
        var ctx = context(1, false);
        double[] disc = discretionary(20_000);
        assertThat(run(ctx, disc, true)).isEqualTo(run(ctx, disc, false));
    }

    @Test
    void isSustainable_smallTrialCount_parallelMatchesSequential() {
        // Edge case: trialCount = 3 (below threshold) — no off-by-one / empty-stream issues.
        var ctx = context(3, false);
        for (double level = 0; level <= 100_000; level += 20_000) {
            double[] disc = discretionary(level);
            assertThat(run(ctx, disc, true))
                    .as("small-trialCount mismatch at level %.0f", level)
                    .isEqualTo(run(ctx, disc, false));
        }
    }
}
