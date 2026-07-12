package com.wealthview.projection;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the pure simulated-guardrail spending rule
 * {@link TrialSimulator#adaptYearSpending} (audit C9). The rule adapts the year's planned total
 * spending toward the DISPLAYED corridor from the trial's portfolio state, bounded per year by the
 * user's {@code maxAnnualAdjustmentRate}, never below the essential floor, and never above the
 * planned schedule (no prosperity ratchet).
 */
class GuardrailAdaptationRuleTest {

    private static final int N = 40;

    private static TrialSimulator.GuardrailAdaptation adaptation(
            double low, double high, double expected, double rate) {
        double[] lows = new double[N];
        double[] highs = new double[N];
        double[] exp = new double[N];
        Arrays.fill(lows, low);
        Arrays.fill(highs, high);
        Arrays.fill(exp, expected);
        return new TrialSimulator.GuardrailAdaptation(lows, highs, exp, rate);
    }

    @Test
    void adaptYearSpending_trialBelowLowerGuardrail_cutsDiscretionaryByBoundedRate() {
        // Planned 70k, floor 30k, corridor [60k,200k], expected median start-balance 1.0M, rate 10%.
        var adapt = adaptation(60_000, 200_000, 1_000_000, 0.10);

        // Trial portfolio at 800k -> ratio 0.8 -> implied 56k < corridorLow 60k -> cut.
        double adapted = TrialSimulator.adaptYearSpending(adapt, 0, 70_000, 30_000, 800_000, 70_000);

        // Bounded down by the rate: prev*(1-rate) = 63k (the 10% max cut), not all the way to 56k.
        assertThat(adapted).isEqualTo(63_000.0, within(1e-6));
    }

    @Test
    void adaptYearSpending_trialWithinCorridor_holdsAtPlanNoCut() {
        var adapt = adaptation(60_000, 200_000, 1_000_000, 0.10);

        // Trial portfolio at 950k -> ratio 0.95 -> implied 66.5k >= corridorLow 60k -> no cut.
        double adapted = TrialSimulator.adaptYearSpending(adapt, 0, 70_000, 30_000, 950_000, 70_000);

        assertThat(adapted).isEqualTo(70_000.0, within(1e-6));
    }

    @Test
    void adaptYearSpending_sustainedCrash_neverSpendsBelowEssentialFloor() {
        // Deep, sustained crash: portfolio collapses; the rule must never take spending below floor.
        var adapt = adaptation(60_000, 200_000, 1_000_000, 0.10);
        double floor = 30_000;
        double planned = 70_000;
        double prev = planned;

        for (int y = 0; y < N; y++) {
            double trialStart = 1_000_000 * Math.pow(0.5, y);   // halves every year
            double adapted = TrialSimulator.adaptYearSpending(adapt, y, planned, floor, trialStart, prev);
            assertThat(adapted).isGreaterThanOrEqualTo(floor);
            prev = adapted;
        }

        // And after the sustained crash the discretionary has been fully cut down to the floor.
        assertThat(prev).isEqualTo(floor, within(1.0));
    }

    @Test
    void adaptYearSpending_anyYear_boundsYearOverYearChangeByMaxRate() {
        // Adversarial signals both directions; assert |adapted - prev| <= rate*prev every step.
        var adapt = adaptation(60_000, 200_000, 1_000_000, 0.10);
        double floor = 30_000;
        double planned = 70_000;
        double prev = 55_000;   // start mid-band (already partly cut)
        double[] signals = {200_000, 1_500_000, 300_000, 1_200_000, 250_000, 1_000_000};

        for (int i = 0; i < signals.length; i++) {
            double adapted = TrialSimulator.adaptYearSpending(adapt, i, planned, floor, signals[i], prev);
            assertThat(Math.abs(adapted - prev)).isLessThanOrEqualTo(0.10 * prev + 1e-6);
            prev = adapted;
        }
    }

    @Test
    void adaptYearSpending_recoveryAfterCrash_returnsTowardPlanButNeverExceedsIt() {
        var adapt = adaptation(60_000, 200_000, 1_000_000, 0.10);
        double floor = 30_000;
        double planned = 70_000;
        double prev = 40_000;   // deeply cut from a prior crash

        // Portfolio fully recovered (well above expected median) for many years running.
        double adapted = prev;
        for (int y = 0; y < 20; y++) {
            adapted = TrialSimulator.adaptYearSpending(adapt, y, planned, floor, 2_000_000, prev);
            assertThat(adapted).isLessThanOrEqualTo(planned);   // never exceed plan, every step
            prev = adapted;
        }

        // Recovery has climbed back to exactly the planned schedule and parked there.
        assertThat(adapted).isEqualTo(planned, within(1e-6));
    }
}
