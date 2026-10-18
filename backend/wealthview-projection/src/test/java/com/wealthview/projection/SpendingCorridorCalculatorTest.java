package com.wealthview.projection;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpendingCorridorCalculatorTest {

    @Test
    void computeCorridors_zeroStartTrial_returnsFiniteCorridors() {
        int years = 3;
        int trialCount = 1;
        double[][] paths = new double[trialCount][years + 1];
        double[] income = new double[years];
        double[] floors = {10_000, 10_000, 10_000};
        double[] discretionary = {5_000, 5_000, 5_000};

        double[][] corridors = SpendingCorridorCalculator.computeCorridors(
                paths, income, floors, discretionary, years, trialCount);

        // A trial that retires with $0 has no growth to apply: 0/0 must read as 1.0, not NaN.
        assertThat(Arrays.stream(corridors).flatMapToDouble(Arrays::stream).boxed().toList())
                .allSatisfy(v -> assertThat(v).isFinite());
    }
}
