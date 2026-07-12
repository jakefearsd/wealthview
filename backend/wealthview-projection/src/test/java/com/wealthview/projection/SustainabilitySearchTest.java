package com.wealthview.projection;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SustainabilitySearchTest {

    /**
     * A deterministic (zero-volatility) SearchContext with 10 trials: 2 "bad" trials
     * start with just enough balance to fund the essential floor alone (no discretionary
     * headroom), 8 "good" trials start with ample balance. At discretionary=0 all 10
     * trials fund the floor every year (successRate=1.0); at any positive discretionary
     * up to 5,000/year the 2 bad trials fail to fund the floor in the final year while
     * the 8 good trials still succeed (successRate=0.8). This brackets a target
     * confidenceLevel of 0.90.
     */
    private static SustainabilitySearch.SearchContext bracketingContext() {
        int years = 3;
        int trialCount = 10;

        // Flat (zero-growth) return arrays isolate the effect of spending on depletion.
        double[][] paths = new double[trialCount][years + 1];
        double[][] taxableReturns = new double[trialCount][years];
        double[][] traditionalReturns = new double[trialCount][years];
        double[][] rothReturns = new double[trialCount][years];
        for (int t = 0; t < trialCount; t++) {
            double startingBalance = t < 2 ? 30_000 : 1_000_000;
            Arrays.fill(paths[t], startingBalance);
        }

        double[] income = new double[years];
        double[] surplusTax = new double[years];

        return new SustainabilitySearch.SearchContext(
                paths, income, surplusTax,
                0.0, 65, years, trialCount,
                0.90, 0.0,
                0, 0.0,
                null, null, null,
                null,
                taxableReturns, traditionalReturns, rothReturns, Integer.MAX_VALUE,
                0.0, null, 0.0, 0.0, 1.0,
                false, 0.0, null);   // household task 6: single-person
    }

    private static double[] bracketingFloors() {
        return new double[] { 10_000, 10_000, 10_000 };
    }

    @Test
    void isSustainable_successRateBelowTarget_returnsFalse() {
        var search = new SustainabilitySearch(new TrialSimulator());
        var ctx = bracketingContext();
        double[] floors = bracketingFloors();
        // Discretionary of 1,000/year exhausts the 2 "bad" trials' floor funding in the
        // final year (successRate drops to 8/10 = 0.80), below the 0.90 target.
        double[] tooHighDiscretionary = { 1_000, 1_000, 1_000 };

        assertThat(search.isSustainable(ctx, floors, tooHighDiscretionary)).isFalse();
    }

    /**
     * T24: a fixture shaped like production ({@code paths[t][0]} constant across trials -- the
     * invariant {@code PortfolioPathGenerator} guarantees, which {@code expectedStartBalance[0]}
     * relies on) with a minority of "crashing" trials (steep, sustained decline) and a majority of
     * "flat" trials, so the no-adaptation median trajectory (dominated by the flat majority) reads
     * as healthy while the crashing minority's OWN trajectory falls far below it -- exactly the
     * signal {@link TrialSimulator#adaptYearSpending} cuts on.
     */
    private static SustainabilitySearch.SearchContext crashMinorityContext(double maxAdjRate,
                                                                            boolean gateOnAdaptiveRules) {
        int years = 5;
        int trialCount = 10;
        int crashingTrials = 3;

        double[][] paths = new double[trialCount][years + 1];
        double[][] taxableReturns = new double[trialCount][years];
        double[][] traditionalReturns = new double[trialCount][years];
        double[][] rothReturns = new double[trialCount][years];
        for (int t = 0; t < trialCount; t++) {
            double rate = t < crashingTrials ? -0.15 : 0.0;
            paths[t][0] = 300_000;
            for (int y = 0; y < years; y++) {
                taxableReturns[t][y] = rate;
                paths[t][y + 1] = paths[t][y] * (1 + rate);
            }
        }

        double[] income = new double[years];
        double[] surplusTax = new double[years];

        return new SustainabilitySearch.SearchContext(
                paths, income, surplusTax,
                0.0, 65, years, trialCount,
                0.80, 0.0,
                0, 0.0,
                null, null, null,
                null,
                taxableReturns, traditionalReturns, rothReturns, Integer.MAX_VALUE,
                0.0, null, 0.0, 0.0, 1.0,
                gateOnAdaptiveRules, maxAdjRate, null);   // household task 6: single-person
    }

    private static double[] crashMinorityFloors() {
        return new double[] { 15_000, 15_000, 15_000, 15_000, 15_000 };
    }

    private static double[] crashMinorityDiscretionary() {
        return new double[] { 40_000, 40_000, 40_000, 40_000, 40_000 };
    }

    @Test
    void isSustainable_gateOnAdaptiveRules_rescuesCandidateThatFailsNoAdaptationGate() {
        var search = new SustainabilitySearch(new TrialSimulator());
        double[] floors = crashMinorityFloors();
        double[] discretionary = crashMinorityDiscretionary();

        // No adaptation: the 3 crashing trials run out of money and miss the floor in the final
        // year (7/10 = 0.70 success), below the 0.80 target.
        var gateOff = crashMinorityContext(1.0, false);
        assertThat(search.isSustainable(gateOff, floors, discretionary)).isFalse();

        // Same candidate, same trials, gate ON: the rule cuts the crashing trials' spending toward
        // their own (far-below-median) trajectory well before they run out, rescuing their floor
        // funding in every year -- the SAME candidate now passes the gate. A generous adjustment
        // rate (1.0 = up to 100%/year) lets the rule react in a single step.
        var gateOn = crashMinorityContext(1.0, true);
        assertThat(search.isSustainable(gateOn, floors, discretionary)).isTrue();
    }

    @Test
    void isSustainable_gateOnAdaptiveRulesButNoAdjustmentRate_fallsBackToNoAdaptationGate() {
        var search = new SustainabilitySearch(new TrialSimulator());
        double[] floors = crashMinorityFloors();
        double[] discretionary = crashMinorityDiscretionary();

        // gateOnAdaptiveRules=true but maxAnnualAdjustmentRate=0 -- the rule is a no-op, so the
        // gate must fall back to the exact no-adaptation result (still false for this candidate).
        var gateOnNoRate = crashMinorityContext(0.0, true);
        assertThat(search.isSustainable(gateOnNoRate, floors, discretionary)).isFalse();
    }

    @Test
    void isSustainable_successRateMeetsTarget_returnsTrue() {
        var search = new SustainabilitySearch(new TrialSimulator());
        var ctx = bracketingContext();
        double[] floors = bracketingFloors();
        // Zero discretionary lets even the 2 "bad" trials exactly fund the floor every
        // year (successRate = 10/10 = 1.0), meeting the 0.90 target.
        double[] safeDiscretionary = { 0, 0, 0 };

        assertThat(search.isSustainable(ctx, floors, safeDiscretionary)).isTrue();
    }

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
