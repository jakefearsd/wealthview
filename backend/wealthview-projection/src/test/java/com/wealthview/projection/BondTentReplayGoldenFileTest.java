package com.wealthview.projection;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import com.wealthview.core.projection.dto.HistoricalCrash;
import com.wealthview.projection.testutil.GoldenScenarios;
import com.wealthview.projection.testutil.SeedReturns;

/**
 * Pins the early-retiree scenario replayed through 2007-09: the crash's first year (2008) lands on the
 * retirement year (2029), so the seeded history runs through 2046 and long-run averages take over after it.
 */
class BondTentReplayGoldenFileTest {

    private static final String SCENARIO = "bond-tent-gfc-replay";

    @Test
    void bondTentGfcReplay_matchesGolden() throws Exception {
        var input = GoldenScenarios.loadInput(SCENARIO);
        var calcs = GoldenScenarios.calculators();
        var capitalMarket = SeedReturns.capitalMarket();
        var maturityReturns = SeedReturns.maturityReturns();
        var paths = SeedReturns.crashReplayPaths(capitalMarket, maturityReturns);
        var engine = new DeterministicProjectionEngine(calcs.federal(), null, calcs.capitalGains(),
                calcs.irmaa(), null, capitalMarket, null, SeedReturns.tentAssumptions(capitalMarket, maturityReturns));
        int birthYear = 1969;
        var path = paths.build(HistoricalCrash.FINANCIAL_CRISIS_2008.firstYear(), 2029, 2026, birthYear + 92);

        var outcomes = engine.replayBondTent(input, path);

        assertGolden(SCENARIO + ".json", GoldenScenarios.MAPPER.writeValueAsString(outcomes));
    }

    private static void assertGolden(String fileName, String actualJson) throws Exception {
        var path = Path.of("src/test/resources/golden/" + fileName);
        if (Boolean.getBoolean("update.golden") || !Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, actualJson);
            return;
        }
        JSONAssert.assertEquals(Files.readString(path), actualJson, JSONCompareMode.STRICT);
    }
}
