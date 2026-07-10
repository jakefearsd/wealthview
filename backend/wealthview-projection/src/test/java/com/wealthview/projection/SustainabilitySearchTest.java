package com.wealthview.projection;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SustainabilitySearchTest {

    @Test
    void verifyEssentialFloor_ampleBalance_returnsConstantRealFloorEveryYear() {
        int years = 5;
        int trialCount = 4;
        double essentialFloor = 10_000;
        double[][] paths = flatPaths(trialCount, years, 1_000_000);
        double[] income = new double[years];

        double[] floors = SustainabilitySearch.verifyEssentialFloor(
                paths, income, essentialFloor, 0.90, years, trialCount);

        // Real terms: the essential floor is held constant real (today's dollars) every year.
        for (int y = 0; y < years; y++) {
            assertThat(floors[y]).isEqualTo(essentialFloor);
        }
    }

    @Test
    void verifyEssentialFloor_depletedPortfolio_clampsFloorToIncomeCapacity() {
        int years = 3;
        int trialCount = 1;
        double[][] paths = flatPaths(trialCount, years, 5_000);
        double[] income = {2_000, 2_000, 2_000};

        double[] floors = SustainabilitySearch.verifyEssentialFloor(
                paths, income, 10_000, 0.90, years, trialCount);

        // The 5k portfolio is wiped out by the first 8k net floor withdrawal, so every
        // year's affordable floor collapses to the income-only capacity of 2k.
        assertThat(floors).containsExactly(2_000, 2_000, 2_000);
    }

    @Test
    void verifyEssentialFloor_matchesYearByYearReferenceSimulation() {
        int years = 25;
        int trialCount = 20;
        double essentialFloor = 30_000;
        double confidenceLevel = 0.85;
        var rng = new Random(42);
        double[][] paths = new double[trialCount][years + 1];
        for (int t = 0; t < trialCount; t++) {
            paths[t][0] = 500_000;
            for (int y = 0; y < years; y++) {
                paths[t][y + 1] = paths[t][y] * (0.9 + rng.nextDouble() * 0.25);
            }
        }
        double[] income = new double[years];
        for (int y = 0; y < years; y++) {
            income[y] = 5_000 + 1_000 * (y % 7);
        }

        double[] floors = SustainabilitySearch.verifyEssentialFloor(
                paths, income, essentialFloor, confidenceLevel, years, trialCount);

        double[] expected = referenceEssentialFloor(
                paths, income, essentialFloor, confidenceLevel, years, trialCount);
        assertThat(floors).containsExactly(expected);
    }

    private static double[][] flatPaths(int trialCount, int years, double balance) {
        double[][] paths = new double[trialCount][years + 1];
        for (double[] path : paths) {
            Arrays.fill(path, balance);
        }
        return paths;
    }

    /**
     * Independent oracle: re-simulates every trial from year 0 for each target year,
     * mirroring the definition of the affordable essential floor directly.
     */
    private static double[] referenceEssentialFloor(double[][] paths, double[] income,
                                                    double essentialFloor, double confidenceLevel,
                                                    int years, int trialCount) {
        double[] floors = new double[years];
        int confidenceIndex = (int) Math.ceil((1 - confidenceLevel) * trialCount) - 1;
        confidenceIndex = Math.max(0, Math.min(confidenceIndex, trialCount - 1));

        // Real terms: the essential floor is constant real across years.
        double[] inflatedFloors = new double[years];
        double[] floorWithdrawals = new double[years];
        for (int y = 0; y < years; y++) {
            inflatedFloors[y] = essentialFloor;
            floorWithdrawals[y] = Math.max(0, essentialFloor - income[y]);
        }

        for (int y = 0; y < years; y++) {
            double[] balancesAtYear = new double[trialCount];
            for (int t = 0; t < trialCount; t++) {
                double balance = paths[t][0];
                for (int py = 0; py <= y; py++) {
                    balance *= paths[t][py + 1] / paths[t][py];
                    balance -= floorWithdrawals[py];
                    balance = Math.max(0, balance);
                }
                balancesAtYear[t] = balance;
            }
            Arrays.sort(balancesAtYear);
            double capacityForFloor = balancesAtYear[confidenceIndex] + income[y];
            floors[y] = Math.min(inflatedFloors[y], Math.max(0, capacityForFloor));
        }
        return floors;
    }
}
