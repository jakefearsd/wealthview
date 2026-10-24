package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.BondTentAssumptions;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.BondTentReplayOutcome;
import com.wealthview.core.projection.dto.BondTentReplayYear;
import com.wealthview.core.projection.dto.HistoricalReturnPath;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.dto.YearReturns;
import tools.jackson.databind.json.JsonMapper;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeterministicProjectionEngineReplayTest {

    private static final String CASH_MIX = """
            {"name": "Cash", "shares": {"ultrashort": 1, "short": 0, "intermediate": 0, "long": 0}}""";
    private static final String SHORT_MIX = """
            {"name": "Short", "shares": {"ultrashort": 0.5, "short": 0.5, "intermediate": 0, "long": 0}}""";
    private static final String LONG_MIX = """
            {"name": "Long", "shares": {"ultrashort": 0, "short": 0, "intermediate": 0.5, "long": 0.5}}""";
    private static final String YIELDS = """
            {"ultrashort": 0.025, "short": 0.03, "intermediate": 0.04, "long": 0.05}""";

    private DeterministicProjectionEngine engine;

    @BeforeEach
    void setUp() {
        engine = newEngine();
    }

    private static DeterministicProjectionEngine newEngine() {
        var assumptions = mock(BondTentAssumptions.class);
        when(assumptions.realReturns()).thenReturn(zeroMaturities());
        return new DeterministicProjectionEngine(null, null, null, null, null, null, null, assumptions);
    }

    private static Map<BondMaturity, Double> zeroMaturities() {
        var zeros = new EnumMap<BondMaturity, Double>(BondMaturity.class);
        for (var maturity : BondMaturity.values()) {
            zeros.put(maturity, 0.0);
        }
        return zeros;
    }

    private static YearReturns year(Integer historyYear, double usStock) {
        var assets = new EnumMap<AssetClass, Double>(AssetClass.class);
        for (var assetClass : AssetClass.values()) {
            assets.put(assetClass, assetClass == AssetClass.US_STOCK ? usStock : 0.0);
        }
        return new YearReturns(historyYear, assets, zeroMaturities());
    }

    private static HistoricalReturnPath crashPath() {
        return new HistoricalReturnPath(Map.of(2026, year(2008, -0.30), 2027, year(2009, 0.10)),
                year(null, 0.05));
    }

    private static String params(int coverage, int buildUp, String inflationFreeExtras, String... mixes) {
        return """
                {"birth_year": 1961, "fee_rate": 0, "dividend_yield": 0, "interest_yield": 0%s,
                 "bond_tent": {"coverage_years": %d, "build_up_start_years": %d, "mixes": [%s], "yields": %s}}"""
                .formatted(inflationFreeExtras, coverage, buildUp, String.join(",", mixes), YIELDS);
    }

    private static ProjectionInput input(String paramsJson, int retirementYear, int endAge, BigDecimal inflation,
                                         ProjectionAccountInput account) {
        return new ProjectionInput(UUID.nameUUIDFromBytes("replay".getBytes()), "Replay",
                LocalDate.of(retirementYear, 1, 1), endAge, inflation, paramsJson, List.of(account),
                new SpendingProfileInput(bd("40000"), bd("0"), "[]"), 2026, List.of(), null, List.of());
    }

    private static ProjectionAccountInput taxable(String contribution) {
        return new HypotheticalAccountInput(bd("1000000"), bd(contribution), AssetAllocation.ALL_US,
                Optional.empty(), "taxable");
    }

    private static ProjectionInput standard(String... mixes) {
        return input(params(2, 0, "", mixes), 2026, 72, bd("0.025"), taxable("0"));
    }

    private static BondTentReplayYear row(BondTentReplayOutcome outcome, int year) {
        return outcome.years().stream().filter(y -> y.year() == year).findFirst().orElseThrow();
    }

    @Test
    void replayBondTent_crashAtRetirement_tentPaysTheFirstTwoYears() {
        var outcomes = engine.replayBondTent(standard(CASH_MIX), crashPath());

        var noTent = outcomes.get(0);
        var cash = outcomes.get(1);
        assertThat(noTent.name()).isEqualTo("No tent");
        assertThat(noTent.tentTarget()).isNull();
        assertThat(noTent.yearsCovered()).isNull();
        assertThat(row(noTent, 2026).balance()).isEqualByComparingTo("660000.00");
        assertThat(row(noTent, 2027).balance()).isEqualByComparingTo("686000.00");
        assertThat(row(noTent, 2028).balance()).isEqualByComparingTo("680300.00");
        assertThat(row(noTent, 2026).stocksSoldWhileDown()).isEqualByComparingTo("40000.00");
        assertThat(row(noTent, 2027).stocksSoldWhileDown()).isEqualByComparingTo("40000.00");
        assertThat(row(noTent, 2028).stocksSoldWhileDown()).isEqualByComparingTo("40000.00");
        assertThat(cash.name()).isEqualTo("Cash");
        assertThat(cash.tentTarget()).isEqualByComparingTo("80000.00");
        assertThat(row(cash, 2026).balance()).isEqualByComparingTo("684000.00");
        assertThat(row(cash, 2027).balance()).isEqualByComparingTo("708400.00");
        assertThat(row(cash, 2028).balance()).isEqualByComparingTo("703820.00");
        assertThat(row(cash, 2026).tentDrawn()).isEqualByComparingTo("40000.00");
        assertThat(row(cash, 2027).tentDrawn()).isEqualByComparingTo("40000.00");
        assertThat(row(cash, 2028).tentDrawn()).isEqualByComparingTo("0.00");
        assertThat(row(cash, 2026).stocksSoldWhileDown()).isEqualByComparingTo("0.00");
        assertThat(row(cash, 2027).stocksSoldWhileDown()).isEqualByComparingTo("0.00");
        assertThat(row(cash, 2028).stocksSoldWhileDown()).isEqualByComparingTo("40000.00");
        assertThat(cash.yearsCovered()).isEqualByComparingTo("2.00");
        assertThat(row(cash, 2026).stocksBelowHigh()).isTrue();
    }

    @Test
    void replayBondTent_outcomes_noTentThenEachMixInOrder() {
        var outcomes = engine.replayBondTent(standard(CASH_MIX, SHORT_MIX, LONG_MIX), crashPath());

        assertThat(outcomes).extracting(BondTentReplayOutcome::name)
                .containsExactly("No tent", "Cash", "Short", "Long");
    }

    @Test
    void replayBondTent_eachMix_isSizedByItsOwnYields() throws Exception {
        var input = standard(CASH_MIX, SHORT_MIX, LONG_MIX);
        var config = ScenarioParams.parseOrEmpty(JsonMapper.builder().build(), input.paramsJson()).bondTent();
        var sizing = engine.sizeBondTent(input, config);

        var outcomes = engine.replayBondTent(input, crashPath());

        assertThat(outcomes).hasSize(4);
        for (int i = 0; i < 3; i++) {
            assertThat(outcomes.get(i + 1).tentTarget()).isEqualByComparingTo(sizing.mixes().get(i).target());
        }
        assertThat(outcomes.get(2).tentTarget()).isNotEqualByComparingTo(outcomes.get(1).tentTarget());
    }

    @Test
    void replayBondTent_recoveryAboveTheHigh_neverDrawsTheTent() {
        var rising = new HistoricalReturnPath(Map.of(), year(null, 0.10));

        var cash = engine.replayBondTent(standard(CASH_MIX), rising).get(1);

        assertThat(cash.years()).isNotEmpty().allSatisfy(y -> {
            assertThat(y.tentDrawn()).isEqualByComparingTo("0.00");
            assertThat(y.stocksBelowHigh()).isFalse();
        });
    }

    @Test
    void replayBondTent_crashDuringBuildUp_topsUpOnlyFromContributions() {
        var input = input(params(2, 3, "", CASH_MIX), 2029, 72, bd("0.025"), taxable("10000"));

        var cash = engine.replayBondTent(input, crashPath()).get(1);

        assertThat(row(cash, 2026).tentBalance()).isEqualByComparingTo("0.00");
        assertThat(row(cash, 2027).tentBalance()).isEqualByComparingTo("10000.00");
        assertThat(row(cash, 2028).tentBalance()).isEqualByComparingTo("20000.00");
    }

    @Test
    void replayBondTent_noRefill_spentTentStaysSpent() {
        var cash = engine.replayBondTent(standard(CASH_MIX), crashPath()).get(1);

        assertThat(row(cash, 2027).tentBalance()).isEqualByComparingTo("0.00");
        assertThat(row(cash, 2028).tentBalance()).isEqualByComparingTo("0.00");
        assertThat(row(cash, 2029).tentBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void replayBondTent_horizonEndsBefore90_hasNoBalanceAt90() {
        var cash = engine.replayBondTent(standard(CASH_MIX), crashPath()).get(1);

        assertThat(cash.balanceAt90()).isNull();
        assertThat(cash.tentLeftAtCheckpoint()).isEqualByComparingTo("0.00");
    }

    @Test
    void replayBondTent_horizonEndsBeforeTheCheckpoint_hasNoTentLeft() {
        var input = input(params(2, 0, "", CASH_MIX), 2026, 68, bd("0.025"), taxable("0"));

        var cash = engine.replayBondTent(input, crashPath()).get(1);

        assertThat(cash.tentLeftAtCheckpoint()).isNull();
    }

    @Test
    void replayBondTent_horizonReaches90_reportsBalanceEnteringNinety() {
        var input = input(params(2, 0, "", CASH_MIX), 2026, 95, bd("0.025"), taxable("0"));

        var noTent = engine.replayBondTent(input, crashPath()).get(0);

        assertThat(noTent.balanceAt90()).isEqualByComparingTo(row(noTent, 1961 + 89).balance());
    }

    @Test
    void replayBondTent_overrideAccount_keepsItsFixedReturn() {
        var override = new HypotheticalAccountInput(bd("1000000"), bd("0"), AssetAllocation.ALL_US,
                Optional.of(bd("0.05")), "taxable");
        var input = input(params(2, 0, "", CASH_MIX), 2026, 72, BigDecimal.ZERO, override);

        var noTent = engine.replayBondTent(input, crashPath()).get(0);

        assertThat(row(noTent, 2026).balance()).isEqualByComparingTo("1010000.00");
        assertThat(row(noTent, 2027).balance()).isEqualByComparingTo("1020500.00");
        assertThat(row(noTent, 2026).stocksSoldWhileDown()).isEqualByComparingTo("0.00");
    }

    @Test
    void replayBondTent_historyYear_isCarriedOntoEachRow() {
        var cash = engine.replayBondTent(standard(CASH_MIX), crashPath()).get(1);

        assertThat(row(cash, 2026).historyYear()).isEqualTo(2008);
        assertThat(row(cash, 2027).historyYear()).isEqualTo(2009);
        assertThat(row(cash, 2028).historyYear()).isNull();
    }

    @Test
    void replayBondTent_rowsCarryAgeAndYear() {
        var cash = engine.replayBondTent(standard(CASH_MIX), crashPath()).get(1);

        assertThat(cash.years().getFirst().year()).isEqualTo(2026);
        assertThat(cash.years().getFirst().age()).isEqualTo(65);
    }

    @Test
    void replayBondTent_noTent_throws() {
        var noTent = input("""
                {"birth_year": 1961, "fee_rate": 0}""", 2026, 72, bd("0.025"), taxable("0"));

        assertThatThrownBy(() -> engine.replayBondTent(noTent, crashPath()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A bond tent analysis needs a spending plan and a bond tent");
    }

    @Test
    void replayBondTent_withoutAssumptions_throws() {
        var bare = new DeterministicProjectionEngine(null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> bare.replayBondTent(standard(CASH_MIX), crashPath()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runDetailed_isUnchangedByTheReplaySeam() {
        var input = input(params(2, 3, "", CASH_MIX), 2029, 80, bd("0.025"), taxable("10000"));
        engine.replayBondTent(input, crashPath());

        var afterReplay = engine.runDetailed(input);

        var fresh = newEngine().runDetailed(input);
        assertThat(afterReplay).isEqualTo(fresh);
        assertThat(afterReplay.bondTent()).isNotNull();
    }
}
