package com.wealthview.projection;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.BondTentAssumptions;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.projection.testutil.GoldenFiles;
import com.wealthview.projection.testutil.GoldenScenarios;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the bond-tent early-retiree scenario: the byte-pinned projection result and the tent side
 * channel. Fixed sleeve returns stand in for the database-backed assumptions so the golden does
 * not depend on seed data.
 */
class BondTentGoldenFileTest {

    private static final String SCENARIO = "bond-tent-early-retiree";

    @Test
    void bondTentEarlyRetiree_matchesGoldens() throws Exception {
        var input = GoldenScenarios.loadInput(SCENARIO);
        var calcs = GoldenScenarios.calculators();
        var assumptions = mock(BondTentAssumptions.class);
        when(assumptions.realReturns()).thenReturn(new EnumMap<>(Map.of(
                BondMaturity.ULTRASHORT, 0.005, BondMaturity.SHORT, 0.013,
                BondMaturity.INTERMEDIATE, 0.018, BondMaturity.LONG, 0.026)));
        var engine = new DeterministicProjectionEngine(calcs.federal(), null, calcs.capitalGains(),
                calcs.irmaa(), null, null, null, assumptions);

        var detail = engine.runDetailed(input);

        GoldenFiles.assertMatches(SCENARIO + ".json", GoldenScenarios.MAPPER.writeValueAsString(detail.result()));
        GoldenFiles.assertMatches(SCENARIO + ".tent.json",
                GoldenScenarios.MAPPER.writeValueAsString(detail.bondTent()));
    }
}
