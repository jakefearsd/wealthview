package com.wealthview.projection;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.wealthview.projection.testutil.GoldenFiles;
import com.wealthview.projection.testutil.GoldenScenarios;

class ProjectionGoldenFileTest {

    @ParameterizedTest(name = "golden file: {0}")
    @ValueSource(strings = {
            "simple-preretirement",
            "tiered-spending-with-income",
            "multi-pool-roth-conversion",
            // T18b: ss-rmd-retiree pins taxable+traditional accounts with an SS-typed income
            // source that covers/exceeds spending (T2/A2 zero-need RMD force-out + T7 SS
            // convergence), MFJ filing, and a birth year that crosses RMD age mid-retirement.
            "ss-rmd-retiree",
            // T18b: accumulation-gap-pension pins a 15-year accumulation phase into retirement
            // at age 58, a 0%-COLA pension (audit C7's base-anchored deflation clock), and
            // traditional_first draws before age 60 (the IRC 72(t) early-withdrawal penalty field).
            "accumulation-gap-pension",
            // Household task 10: household-survivor pins the full first-death cliff year-by-year
            // (spec 2026-07-12 §4): an age-gap couple (1958/1966), two per-owner RMD streams that
            // merge at the 2043 spousal rollover, Social Security keep-larger (38k over 22k), a
            // 50%-survivor pension, a joint-taxable basis step-up, survivor spending x0.75 on a
            // tiered profile, the MFJ-to-single filing flip, and second-death truncation at 2056.
            "household-survivor"
    })
    void run_matchesGoldenFile(String scenario) throws Exception {
        var input = GoldenScenarios.loadInput(scenario);
        var calcs = GoldenScenarios.calculators();

        var engine = new DeterministicProjectionEngine(
                calcs.federal(), null, calcs.capitalGains(), calcs.irmaa());
        var result = engine.run(input);

        var actualJson = GoldenScenarios.MAPPER.writeValueAsString(result);

        GoldenFiles.assertMatches(scenario + ".json", actualJson);
    }
}
