package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.BondTentAssumptions;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.BondTentYear;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionRunDetail;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import tools.jackson.databind.json.JsonMapper;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeterministicProjectionEngineBondTentTest {

    private static final int CURRENT_YEAR = 2026;
    private static final BigDecimal FEE = new BigDecimal("0.002");
    private static final String PLAN_MIX = """
            {"name": "Plan", "shares": {"ultrashort": 0.2, "short": 0.3, "intermediate": 0.5, "long": 0}}""";
    private static final String YIELDS = """
            {"ultrashort": 0.0394, "short": 0.0465, "intermediate": 0.048, "long": 0.0539}""";
    private static final Map<BondMaturity, Double> REAL_RETURNS = new EnumMap<>(Map.of(
            BondMaturity.ULTRASHORT, 0.005, BondMaturity.SHORT, 0.013,
            BondMaturity.INTERMEDIATE, 0.018, BondMaturity.LONG, 0.026));

    private CapitalMarketAssumptionsProvider cma;
    private DeterministicProjectionEngine engine;

    @BeforeEach
    void setUp() {
        cma = mock(CapitalMarketAssumptionsProvider.class);
        var assumptions = mock(BondTentAssumptions.class);
        when(assumptions.realReturns()).thenReturn(REAL_RETURNS);
        engine = new DeterministicProjectionEngine(null, null, null, null, null, cma, null, assumptions);
    }

    private static String tentJson(String coverage, int buildUp, String... mixes) {
        return """
                "bond_tent": {"coverage_years": %s, "build_up_start_years": %d, "mixes": [%s], "yields": %s}"""
                .formatted(coverage, buildUp, String.join(",", mixes), YIELDS);
    }

    private static ProjectionInput input(String extraParams, int retirementYear, SpendingProfileInput spending) {
        var params = """
                {"birth_year": 1969, "fee_rate": 0.002%s}""".formatted(extraParams);
        return new ProjectionInput(UUID.nameUUIDFromBytes("tent".getBytes()), "Tent",
                LocalDate.of(retirementYear, 1, 1), 90, bd("0.025"), params,
                List.of(acct("600000", "0", "0.05", "taxable"), acct("400000", "0", "0.05", "traditional")),
                spending, CURRENT_YEAR, List.of(), null, List.of());
    }

    private static ProjectionInput tentInput(String coverage, int buildUp, int retirementYear) {
        return input(", " + tentJson(coverage, buildUp, PLAN_MIX), retirementYear, spending());
    }

    private static SpendingProfileInput spending() {
        return new SpendingProfileInput(bd("20000"), bd("5000"), "[]");
    }

    private static BondTentYear yearOf(ProjectionRunDetail detail, int year) {
        return detail.bondTent().years().stream().filter(y -> y.year() == year).findFirst().orElseThrow();
    }

    private static BigDecimal planTarget(ProjectionRunDetail detail) {
        return detail.bondTent().sizing().mixes().getFirst().target();
    }

    @Test
    void runDetailed_noTent_hasNoBondTentResult() {
        var detail = engine.runDetailed(input("", 2029, spending()));

        assertThat(detail.bondTent()).isNull();
    }

    @Test
    void runDetailed_tentWithoutSpendingPlan_ignoresTent() {
        var withTent = engine.runDetailed(input(", " + tentJson("3", 3, PLAN_MIX), 2029, null));
        var without = engine.runDetailed(input("", 2029, null));

        assertThat(withTent.bondTent()).isNull();
        assertThat(withTent.result().yearlyData()).isEqualTo(without.result().yearlyData());
    }

    @Test
    void runDetailed_tent_reachesTargetAtRetirement() {
        // No ramp, so the whole reservation is made in one top-up at the mix shares.
        var detail = engine.runDetailed(tentInput("3", 0, 2029));

        var year = yearOf(detail, 2029);
        double blended = 0.2 * (1 + 0.005 - 0.002) + 0.3 * (1 + 0.013 - 0.002) + 0.5 * (1 + 0.018 - 0.002);
        assertThat(planTarget(detail)).isPositive();
        assertThat(year.buildUpTarget()).isEqualByComparingTo(planTarget(detail));
        assertThat(year.total().doubleValue())
                .isCloseTo(planTarget(detail).doubleValue() * blended, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void runDetailed_buildUpThreeYears_followsStraightLine() {
        var detail = engine.runDetailed(tentInput("3", 3, 2029));

        var target = planTarget(detail);
        assertThat(yearOf(detail, 2026).buildUpTarget()).isEqualByComparingTo("0");
        assertThat(yearOf(detail, 2027).buildUpTarget())
                .isEqualByComparingTo(target.divide(bd("3"), 2, java.math.RoundingMode.HALF_UP));
        assertThat(yearOf(detail, 2028).buildUpTarget())
                .isEqualByComparingTo(target.multiply(bd("2")).divide(bd("3"), 2, java.math.RoundingMode.HALF_UP));
        assertThat(yearOf(detail, 2029).buildUpTarget()).isEqualByComparingTo(target);
        assertThat(yearOf(detail, 2030).buildUpTarget()).isNull();
    }

    @Test
    void runDetailed_buildUpStartBeforeToday_compressesRamp() {
        var detail = engine.runDetailed(tentInput("3", 10, 2030));

        var target = planTarget(detail);
        assertThat(yearOf(detail, 2026).buildUpTarget()).isEqualByComparingTo("0");
        assertThat(yearOf(detail, 2027).buildUpTarget())
                .isEqualByComparingTo(target.divide(bd("4"), 2, java.math.RoundingMode.HALF_UP));
        assertThat(yearOf(detail, 2028).buildUpTarget())
                .isEqualByComparingTo(target.divide(bd("2"), 2, java.math.RoundingMode.HALF_UP));
        assertThat(yearOf(detail, 2030).buildUpTarget()).isEqualByComparingTo(target);
    }

    @Test
    void runDetailed_retiredAlready_fundsFullTargetInFirstYear() {
        var detail = engine.runDetailed(tentInput("3", 3, 2020));

        var first = detail.bondTent().years().getFirst();
        assertThat(first.year()).isEqualTo(CURRENT_YEAR);
        assertThat(first.buildUpTarget()).isEqualByComparingTo(planTarget(detail));
        assertThat(first.total()).isPositive();
        assertThat(yearOf(detail, 2027).buildUpTarget()).isNull();
    }

    @Test
    void runDetailed_tentNeverDrawnInGoodProjection() {
        var detail = engine.runDetailed(tentInput("3", 3, 2029));

        var retired = detail.bondTent().years().stream().filter(y -> y.year() >= 2029).toList();
        for (int i = 1; i < retired.size(); i++) {
            assertThat(retired.get(i).total()).isGreaterThanOrEqualTo(retired.get(i - 1).total());
        }
    }

    @Test
    void runDetailed_tentCostsGrowth() {
        var withTent = engine.runDetailed(tentInput("3", 3, 2029));
        var without = engine.runDetailed(input("", 2029, spending()));

        assertThat(withTent.result().finalBalance()).isLessThan(without.result().finalBalance());
    }

    @Test
    void runDetailed_tentWithDepressionYearsFlag_usesDefaultWindow() {
        var input = input(", \"include_depression_years\": true, " + tentJson("3", 3, PLAN_MIX), 2029, spending());

        var detail = engine.runDetailed(input);

        assertThat(detail.bondTent()).isNotNull();
        verify(cma, never()).geometricMeans(true);
        verify(cma, atLeastOnce()).geometricMeans(false);
    }

    @Test
    void runDetailed_sizingCoversEveryMix() {
        var second = """
                {"name": "Short", "shares": {"ultrashort": 1, "short": 0, "intermediate": 0, "long": 0}}""";
        var third = """
                {"name": "Long", "shares": {"ultrashort": 0, "short": 0, "intermediate": 0, "long": 1}}""";
        var input = input(", " + tentJson("3", 3, PLAN_MIX, second, third), 2029, spending());

        var detail = engine.runDetailed(input);

        assertThat(detail.bondTent().sizing().mixes()).extracting("name").containsExactly("Plan", "Short", "Long");
        assertThat(detail.bondTent().sizing().coveredYears()).hasSize(3);
    }

    @Test
    void runDetailed_tentConfiguredWithoutAssumptions_throws() {
        var bare = new DeterministicProjectionEngine(null, null, null, null, null, cma, null);

        assertThatThrownBy(() -> bare.runDetailed(tentInput("3", 3, 2029)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A bond tent is configured but bond return assumptions are not wired");
    }

    @Test
    void sizeBondTent_matchesRunDetailedSizing() {
        var input = tentInput("3", 3, 2029);
        var config = ScenarioParams.parseOrEmpty(JsonMapper.builder().build(), input.paramsJson()).bondTent();

        var sizing = engine.sizeBondTent(input, config);

        assertThat(sizing).isEqualTo(engine.runDetailed(input).bondTent().sizing());
    }
}
