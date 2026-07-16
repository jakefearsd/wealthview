package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sub-project B (stochastic mortality), task 7: unit tests for {@link
 * StochasticMortalitySummary#from} -- the pure aggregation of one stochastic evaluation pass
 * ({@link StochasticMortalityEvaluation}) into the user-facing summary. No optimizer/trial
 * machinery involved; every case here is a hand-computable arithmetic fixture.
 */
class StochasticMortalitySummaryTest {

    @Test
    void from_countsEarlyDeathAsLifetimeSuccessAndFiltersLongevity() {
        boolean[] success = {true, true, false, true};
        int[] firstDeath = {78, 82, 80, 85};
        int[] secondDeath = {88, 96, 92, 97};   // survivor ages

        var s = StochasticMortalitySummary.from(success, firstDeath, secondDeath, 95);

        assertThat(s.lifetimeSuccessProbability()).isEqualTo(0.75);          // 3 of 4
        // trials reaching survivor age >= 95: indices 1 (96) and 3 (97); both success -> 1.0 over 0.5 of trials
        assertThat(s.longevityConditional().age()).isEqualTo(95);
        assertThat(s.longevityConditional().probability()).isEqualTo(1.0);
        assertThat(s.longevityConditional().trialFraction()).isEqualTo(0.5);
        assertThat(s.secondDeathAge().median()).isEqualTo(94);              // median of {88,92,96,97}
    }

    // === edge: no trial reaches the longevity age -- probability/trialFraction must be 0, not NaN ===

    @Test
    void from_noTrialReachesLongevityAge_probabilityAndFractionAreZeroNotNaN() {
        boolean[] success = {true, true, false, true};
        int[] firstDeath = {70, 72, 74, 76};
        int[] secondDeath = {80, 82, 84, 86};  // none reach 95

        var s = StochasticMortalitySummary.from(success, firstDeath, secondDeath, 95);

        assertThat(s.longevityConditional().age()).isEqualTo(95);
        assertThat(s.longevityConditional().probability()).isEqualTo(0.0);
        assertThat(s.longevityConditional().trialFraction()).isEqualTo(0.0);
    }

    // === degenerate distribution: every trial dies at the same age -- percentiles collapse to it ===

    @Test
    void from_allTrialsIdenticalDeathAges_distributionCollapsesToThatAge() {
        boolean[] success = {true, true, true};
        int[] firstDeath = {80, 80, 80};
        int[] secondDeath = {90, 90, 90};

        var s = StochasticMortalitySummary.from(success, firstDeath, secondDeath, 95);

        assertThat(s.firstDeathAge().p10()).isEqualTo(80);
        assertThat(s.firstDeathAge().median()).isEqualTo(80);
        assertThat(s.firstDeathAge().p90()).isEqualTo(80);
        assertThat(s.secondDeathAge().median()).isEqualTo(90);
    }
}
