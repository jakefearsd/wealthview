package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Household task 6: verifies {@link TrialSimulator}'s owner-aware double[5] pools mirror the T4/T5
 * deterministic economics in double-land — two per-owner RMD streams (age-gap correctness), the
 * first-death transition (spousal rollover + joint-taxable basis step-up, once, at the year
 * boundary), the two-stream ordinary-stack composition identity, and second-death truncation.
 * Single-person byte-identity is the anchor pinned by {@link TrialSimulatorReturnTest}; here every
 * config carries a {@link TrialSimulator.HouseholdSim}.
 */
class TrialSimulatorHouseholdTest {

    private final TrialSimulator simulator = new TrialSimulator();

    /** Builds a household SimulationConfig (taxable_first, tracked, 0% cash return). */
    // ExcessiveParameterList mirrors the SimulationConfig canonical shape for readable test call sites.
    @SuppressWarnings("PMD.ExcessiveParameterList")
    private static TrialSimulator.SimulationConfig cfg(
            double initTaxable, double initTraditional, double initRoth,
            String order, OrdinaryTaxTable[] tables, double[] base,
            double[] conv, double[] convTax, int retirementAge, int cashReserveYears,
            double[] taxRet, double[] tradRet, double[] rothRet, int rmdStartAge,
            double initTaxableBasis, LtcgTaxTable[] ltcg, TrialSimulator.HouseholdSim household) {
        return new TrialSimulator.SimulationConfig(
                initTaxable, initTraditional, initRoth, order,
                tables, base, conv, convTax, retirementAge, null,
                cashReserveYears, 0.0, true,
                taxRet, tradRet, rothRet, rmdStartAge,
                initTaxableBasis, ltcg, 0.0, null, null, 0.0, 1.0, household);
    }

    /** Two real brackets, no deduction beyond the $10k standard: [0,100k) @10%, [100k,inf) @30%. */
    private static OrdinaryTaxTable twoBracketTable() {
        return new OrdinaryTaxTable(0,
                new double[]{0, 100_000}, new double[]{0.10, 0.30}, new double[]{0, 10_000});
    }

    // === Per-owner RMD streams (age-gap oracles, mirroring T4's pinned deterministic cases) ===

    @Test
    void simulateTrial_twoRmdStreams_ageGap_bothFireFromOwnBalanceAndOwnAge() {
        // primary age 80 (trad 100k), spouse age 75 (trad 50k), both past their RMD start (73).
        // Each stream keys off its OWN balance and OWN Uniform-Lifetime divisor -- the age-gap
        // correctness the feature exists for. Flat 20% ordinary table, no growth/spending/conversion:
        // the only outflow is each forced RMD's own tax.
        var household = new TrialSimulator.HouseholdSim(
                50_000.0, 0.0, /*spouseAgeOffset=*/-5, /*spouseRmdStartAge=*/73,
                /*transitionYearIndex=*/-1, /*survivorIsPrimary=*/true,
                /*stepUpFactor=*/0.0, /*truncateYearIndex=*/1);
        var config = cfg(0.0, 150_000.0, 0.0, "taxable_first",
                new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.20)}, new double[]{0.0},
                null, null, /*retirementAge=*/80, 0,
                new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, /*rmdStartAge=*/73,
                0.0, null, household);

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, 1, config);

        double rmdP = 100_000.0 / RmdCalculator.distributionPeriod(80);
        double rmdS = 50_000.0 / RmdCalculator.distributionPeriod(75);
        double finalBalance = 150_000.0 - (rmdP + rmdS) * 0.20;
        assertThat(result.finalBalance()).isEqualTo(finalBalance, within(1e-4));
    }

    @Test
    void simulateTrial_twoRmdStreams_spouseBelowStartAge_onlyPrimaryStreamFires() {
        // Same primary (age 80) but the spouse is age 70 -- below the RMD start age (73), so the
        // spouse stream contributes nothing. Only the primary RMD's tax leaves the portfolio.
        var household = new TrialSimulator.HouseholdSim(
                50_000.0, 0.0, /*spouseAgeOffset=*/-10, /*spouseRmdStartAge=*/73,
                -1, true, 0.0, 1);
        var config = cfg(0.0, 150_000.0, 0.0, "taxable_first",
                new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.20)}, new double[]{0.0},
                null, null, 80, 0,
                new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, 73,
                0.0, null, household);

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, 1, config);

        double rmdP = 100_000.0 / RmdCalculator.distributionPeriod(80);
        assertThat(result.finalBalance()).isEqualTo(150_000.0 - rmdP * 0.20, within(1e-4));
    }

    @Test
    void simulateTrial_twoRmdStreamsCompositionIdentity_telescopesAcrossBothRmdConvAndDraw() {
        // COMPOSITION IDENTITY updated for TWO RMD streams: with base income, BOTH owners' forced
        // RMDs, a Roth conversion, and a traditional spending draw all live in one year, the summed
        // ordinary tax telescopes EXACTLY, in the pinned order base+interest → RMD-P → RMD-S →
        // conversion → draw:
        //   rmdPTax + rmdSTax + convTax + wdTax = taxAt(base+rmdP+rmdS+conv+draw) - taxAt(base).
        // primary trad 200k, spouse trad 100k, both age 75 (divisor 24.6). base 60k, conv 50k
        // (crosses 100k mid-conversion), draw 5k. conversionTaxByYear is 0 (deliberately WRONG) to
        // prove the engine reprices the conversion from the table.
        var table = twoBracketTable();
        var household = new TrialSimulator.HouseholdSim(
                100_000.0, 0.0, /*spouseAgeOffset=*/0, /*spouseRmdStartAge=*/73,
                -1, true, 0.0, 1);
        var config = cfg(500_000.0, 300_000.0, 0.0, "traditional_first",
                new OrdinaryTaxTable[]{table}, new double[]{60_000.0},
                new double[]{50_000.0}, new double[]{0.0}, 75, 0,
                new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, 73,
                500_000.0, null, household);

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{5_000.0}, new double[]{0.0}, 1, config);

        double rmdP = 200_000.0 / 24.6;
        double rmdS = 100_000.0 / 24.6;
        double telescopedTax = table.taxAt(60_000.0 + rmdP + rmdS + 50_000.0 + 5_000.0)
                - table.taxAt(60_000.0);
        double initTotal = 500_000.0 + 300_000.0;
        assertThat(initTotal - result.finalBalance())
                .isEqualTo(5_000.0 + telescopedTax, within(1e-6));
    }

    // === First-death transition: spousal rollover (routing + conservation) ===

    @Test
    void simulateTrial_firstDeathTransition_rollsDeceasedTraditionalIntoSurvivorPrimary() {
        // survivor = primary. Transition at year 0 rolls the spouse's traditional (50k) into the
        // primary's (100k). Year 0 is pre-RMD for both; year 1 the primary hits its RMD start (80)
        // and RMDs off the FULL rolled-up 150k -- provable only if the rollover routed to the
        // primary. The spouse is below RMD age at year 1 (and its pool is 0 anyway), so the sole
        // outflow is the primary's RMD tax.
        var household = new TrialSimulator.HouseholdSim(
                50_000.0, 0.0, /*spouseAgeOffset=*/-10, /*spouseRmdStartAge=*/80,
                /*transitionYearIndex=*/0, /*survivorIsPrimary=*/true,
                /*stepUpFactor=*/0.0, /*truncateYearIndex=*/2);
        var config = cfg(0.0, 150_000.0, 0.0, "taxable_first",
                new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.20), OrdinaryTaxTable.flat(0.20)},
                new double[]{0.0, 0.0}, null, null, /*retirementAge=*/79, 0,
                new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, new double[]{0.0, 0.0},
                /*rmdStartAge=*/80, 0.0, null, household);

        var result = simulator.simulateTrial(
                new double[]{0.0, 0.0}, new double[]{0.0, 0.0},
                new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, 2, config);

        // Year 1 primary RMD off the rolled-up 150k at age 80 (divisor 20.2); tax is the only leak.
        double rmdP1 = 150_000.0 / RmdCalculator.distributionPeriod(80);
        assertThat(result.finalBalance()).isEqualTo(150_000.0 - rmdP1 * 0.20, within(1e-4));
    }

    @Test
    void simulateTrial_firstDeathTransition_survivorSpouse_rollsIntoSpousePools() {
        // Mirror image: survivor = spouse. The primary's traditional (100k) rolls into the spouse's
        // (50k); the spouse RMDs off the full 150k at year 1, the primary contributes nothing.
        var household = new TrialSimulator.HouseholdSim(
                50_000.0, 0.0, /*spouseAgeOffset=*/0, /*spouseRmdStartAge=*/80,
                /*transitionYearIndex=*/0, /*survivorIsPrimary=*/false,
                0.0, /*truncateYearIndex=*/2);
        // Primary below its own RMD start (99) at both years so it never RMDs; spouse age == primary
        // age here (offset 0), reaching its start (80) at year 1.
        var config = cfg(0.0, 150_000.0, 0.0, "taxable_first",
                new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.20), OrdinaryTaxTable.flat(0.20)},
                new double[]{0.0, 0.0}, null, null, /*retirementAge=*/79, 0,
                new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, new double[]{0.0, 0.0},
                /*rmdStartAge=*/99, 0.0, null, household);

        var result = simulator.simulateTrial(
                new double[]{0.0, 0.0}, new double[]{0.0, 0.0},
                new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, 2, config);

        double rmdS1 = 150_000.0 / RmdCalculator.distributionPeriod(80);
        assertThat(result.finalBalance()).isEqualTo(150_000.0 - rmdS1 * 0.20, within(1e-4));
    }

    @Test
    void simulateTrial_firstDeathTransition_conservesTotalWhenNoTaxOrSpending() {
        // Rollover is conservation-preserving: with no tax table, no spending, no RMD, the transition
        // only shuffles trad/roth between owner slots -- the total is untouched.
        var household = new TrialSimulator.HouseholdSim(
                50_000.0, 20_000.0, 0, 99, /*transitionYearIndex=*/0, true, 0.0, 1);
        var config = cfg(0.0, 150_000.0, 50_000.0, "taxable_first",
                null, null, null, null, 60, 0,
                new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, 99,
                0.0, null, household);

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(200_000.0, within(1e-6));
    }

    // === First-death transition: joint-taxable basis step-up ===

    @Test
    void simulateTrial_firstDeathTransition_stepsUpJointTaxableBasisLoweringLaterLtcg() {
        // Joint taxable 100k with a 50k embedded gain (basis 50k). A 40k spending sale realizes FIFO
        // gain taxed at a flat 15% LTCG rate. A full step-up (factor 1.0) at the transition zeroes the
        // embedded gain BEFORE the sale, so no LTCG is realized -- vs. no step-up (factor 0.0), where
        // the 20k realized gain costs 3k of LTCG tax. The 3k delta proves the step-up fired.
        var ltcg = new LtcgTaxTable(0,
                new double[]{0}, new double[]{Double.POSITIVE_INFINITY},
                new double[]{0.15}, Double.POSITIVE_INFINITY);
        TrialSimulator.HouseholdSim stepUp = new TrialSimulator.HouseholdSim(
                0.0, 0.0, 0, 99, /*transitionYearIndex=*/0, true, /*stepUpFactor=*/1.0, 1);
        TrialSimulator.HouseholdSim noStepUp = new TrialSimulator.HouseholdSim(
                0.0, 0.0, 0, 99, 0, true, /*stepUpFactor=*/0.0, 1);

        var withStepUp = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{40_000.0}, new double[]{0.0}, 1,
                cfg(100_000.0, 0.0, 0.0, "taxable_first",
                        new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.0)}, new double[]{0.0},
                        null, null, 60, 0,
                        new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, 99,
                        50_000.0, new LtcgTaxTable[]{ltcg}, stepUp));
        var withoutStepUp = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{40_000.0}, new double[]{0.0}, 1,
                cfg(100_000.0, 0.0, 0.0, "taxable_first",
                        new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.0)}, new double[]{0.0},
                        null, null, 60, 0,
                        new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, 99,
                        50_000.0, new LtcgTaxTable[]{ltcg}, noStepUp));

        assertThat(withStepUp.finalBalance()).isEqualTo(60_000.0, within(1e-4));   // 100k - 40k spend, no LTCG
        assertThat(withoutStepUp.finalBalance()).isEqualTo(57_000.0, within(1e-4)); // 60k - 3k LTCG
        assertThat(withStepUp.finalBalance() - withoutStepUp.finalBalance()).isEqualTo(3_000.0, within(1e-4));
    }

    // === Second-death truncation ===

    @Test
    void simulateTrial_secondDeathTruncation_stopsSimulationAtBequestYear() {
        // Horizon 5, but the second death truncates the trial after 2 simulated years
        // (truncateYearIndex = 2). Each simulated year draws 30k from a 100k taxable pool, so a
        // truncated run ends at 40k; a full 5-year run would deplete to 0. Tracked year-balances
        // carry the 40k bequest forward across the unsimulated tail.
        var household = new TrialSimulator.HouseholdSim(
                0.0, 0.0, 0, 99, /*transitionYearIndex=*/-1, true, 0.0, /*truncateYearIndex=*/2);
        double[] zero5 = {0.0, 0.0, 0.0, 0.0, 0.0};
        double[] floors5 = {30_000.0, 30_000.0, 30_000.0, 30_000.0, 30_000.0};
        var config = cfg(100_000.0, 0.0, 0.0, "taxable_first",
                null, null, null, null, 60, 0,
                zero5, zero5, zero5, 99, 0.0, null, household);

        var result = simulator.simulateTrial(
                zero5, zero5, floors5, zero5, 5, config);

        assertThat(result.finalBalance()).isEqualTo(40_000.0, within(1e-6));
        assertThat(result.yearBalances()).hasSize(5);
        assertThat(result.yearBalances()[1]).isEqualTo(40_000.0, within(1e-6)); // last simulated year
        assertThat(result.yearBalances()[4]).isEqualTo(40_000.0, within(1e-6)); // carried-forward bequest
    }

    // === Null-household anchor: the 25-arg canonical with a null HouseholdSim == 24-arg back-compat ===

    @Test
    void simulateTrial_nullHousehold_matchesPreHouseholdThreePoolRunExactly() {
        double[] r = {0.10};
        var preHousehold = new TrialSimulator.SimulationConfig(
                100.0, 200.0, 50.0, "taxable_first", null, null, null, null, 62, null,
                0, 0.0, false, r, new double[]{0.02}, new double[]{0.08}, Integer.MAX_VALUE,
                100.0, null, 0.0);
        var canonicalNull = new TrialSimulator.SimulationConfig(
                100.0, 200.0, 50.0, "taxable_first", null, null, null, null, 62, null,
                0, 0.0, false, r, new double[]{0.02}, new double[]{0.08}, Integer.MAX_VALUE,
                100.0, null, 0.0, null, null, 0.0, 1.0, null);

        var a = simulator.simulateTrial(new double[]{0}, new double[]{0},
                new double[]{0}, new double[]{0}, 1, preHousehold);
        var b = simulator.simulateTrial(new double[]{0}, new double[]{0},
                new double[]{0}, new double[]{0}, 1, canonicalNull);

        assertThat(b.finalBalance()).isEqualTo(a.finalBalance());
    }
}
