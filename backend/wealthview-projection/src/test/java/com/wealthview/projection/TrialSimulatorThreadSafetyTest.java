package com.wealthview.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thread-safety / purity safety-net for {@link TrialSimulator}.
 *
 * <p>This is a prerequisite for parallelizing the Monte Carlo trial loop in
 * {@link SustainabilitySearch#isSustainable}. {@code TrialSimulator} is stateless
 * (only {@code static final} constants, no instance fields) and {@code simulateTrial}
 * only reads its input arrays and writes a fresh local {@code pools} array. These tests
 * prove that a single shared instance produces correct, interference-free results when
 * called concurrently from many threads — both with identical inputs and with several
 * distinct fixed inputs interleaved across threads.
 */
class TrialSimulatorThreadSafetyTest {

    private static final int YEARS = 28;
    private final TrialSimulator simulator = new TrialSimulator();

    /** A realistic pooled fixture (taxable + traditional + roth) keyed by a per-fixture multiplier. */
    private TrialSimulator.SimulationConfig pooledConfig(double scale) {
        double[] marginalRates = new double[YEARS];
        double[] conversionByYear = new double[YEARS];
        double[] conversionTaxByYear = new double[YEARS];
        double[] dsCeiling = new double[YEARS];
        for (int y = 0; y < YEARS; y++) {
            marginalRates[y] = 0.22;
        }
        return new TrialSimulator.SimulationConfig(
                800_000 * scale, 600_000 * scale, 100_000 * scale,
                "taxable_first", marginalRates,
                conversionByYear, conversionTaxByYear, 62,
                dsCeiling, 5, 0.02, false);
    }

    /** Deterministic per-fixture nominal-return sequence (alternating up/down years). */
    private double[] returns(double scale) {
        double[] r = new double[YEARS];
        for (int y = 0; y < YEARS; y++) {
            r[y] = (y % 3 == 0 ? -0.08 : 0.09) * scale;
        }
        return r;
    }

    private double[] floors() {
        double[] f = new double[YEARS];
        for (int y = 0; y < YEARS; y++) {
            f[y] = 40_000;
        }
        return f;
    }

    private double[] discretionary() {
        double[] d = new double[YEARS];
        for (int y = 0; y < YEARS; y++) {
            d[y] = 30_000;
        }
        return d;
    }

    private double[] income() {
        return new double[YEARS];
    }

    private double[] surplusTax() {
        return new double[YEARS];
    }

    private TrialSimulator.TrialResult runOnce(double scale) {
        return simulator.simulateTrial(returns(scale), income(), surplusTax(),
                floors(), discretionary(), YEARS, pooledConfig(scale));
    }

    @Test
    void simulateTrial_manyThreadsIdenticalInput_everyResultIdentical() throws Exception {
        TrialSimulator.TrialResult expected = runOnce(1.0);

        int threads = 16;
        int callsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<TrialSimulator.TrialResult>> tasks = new ArrayList<>();
            // Each task captures its OWN input arrays (mirrors the per-trial allocation the
            // parallel loop will do) so this isolates simulator purity from input sharing.
            for (int i = 0; i < threads * callsPerThread; i++) {
                tasks.add(() -> runOnce(1.0));
            }
            List<Future<TrialSimulator.TrialResult>> futures = pool.invokeAll(tasks);

            for (Future<TrialSimulator.TrialResult> f : futures) {
                TrialSimulator.TrialResult actual = f.get();
                assertThat(actual.finalBalance()).isEqualTo(expected.finalBalance());
                assertThat(actual.minBalance()).isEqualTo(expected.minBalance());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void simulateTrial_manyThreadsDistinctInputsInterleaved_eachInputYieldsItsOwnResult()
            throws Exception {
        double[] scales = {0.5, 1.0, 1.5, 2.0};
        // Golden result for each distinct input, computed single-threaded up front.
        double[] expectedFinal = new double[scales.length];
        double[] expectedMin = new double[scales.length];
        for (int s = 0; s < scales.length; s++) {
            TrialSimulator.TrialResult r = runOnce(scales[s]);
            expectedFinal[s] = r.finalBalance();
            expectedMin[s] = r.minBalance();
        }

        int threads = 16;
        int callsPerScale = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<int[]>> tasks = new ArrayList<>();
            List<Double> resultFinals = new ArrayList<>();
            // Interleave the distinct fixtures across submission order; each task re-verifies
            // its own fixture's result against the golden value, proving no cross-call bleed.
            for (int call = 0; call < callsPerScale; call++) {
                for (int s = 0; s < scales.length; s++) {
                    final int scaleIdx = s;
                    tasks.add(() -> {
                        TrialSimulator.TrialResult r = runOnce(scales[scaleIdx]);
                        assertThat(r.finalBalance()).isEqualTo(expectedFinal[scaleIdx]);
                        assertThat(r.minBalance()).isEqualTo(expectedMin[scaleIdx]);
                        return new int[]{scaleIdx};
                    });
                }
            }
            List<Future<int[]>> futures = pool.invokeAll(tasks);
            for (Future<int[]> f : futures) {
                f.get(); // propagate any assertion failure thrown on a worker thread
            }
            assertThat(futures).hasSize(callsPerScale * scales.length);
            assertThat(resultFinals).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void simulateTrial_distinctInputs_produceDistinctResults() {
        // Sanity check that the fixtures are actually distinguishable — otherwise the
        // interleaved test above would pass vacuously.
        assertThat(runOnce(0.5).finalBalance()).isNotEqualTo(runOnce(2.0).finalBalance());
    }
}
