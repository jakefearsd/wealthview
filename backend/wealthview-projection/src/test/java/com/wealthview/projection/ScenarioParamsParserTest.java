package com.wealthview.projection;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.SpendingProfileInput;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioParamsParserTest {

    private final ScenarioParamsParser parser = new ScenarioParamsParser();

    @Test
    void dividendYield_absentFromParams_defaultsToPoint018() {
        assertThat(parser.dividendYield(ScenarioParams.EMPTY)).isEqualByComparingTo("0.018");
    }

    @Test
    void dividendYield_presentInParsedParams_returnsParsedValue() {
        var params = parser.parseParams("""
                {"dividend_yield": 0.025}
                """);

        assertThat(params.dividendYield()).isEqualByComparingTo("0.025");
        assertThat(parser.dividendYield(params)).isEqualByComparingTo("0.025");
    }

    @Test
    void dividendYield_explicitZero_isNotTreatedAsAbsent() {
        var params = parser.parseParams("""
                {"dividend_yield": 0}
                """);

        assertThat(parser.dividendYield(params)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // B1 (2026-07-11 audit): investment fees -- mirrors the dividendYield tests above exactly.

    @Test
    void feeRate_absentFromParams_defaultsToPoint0025() {
        assertThat(parser.feeRate(ScenarioParams.EMPTY)).isEqualByComparingTo("0.0025");
    }

    @Test
    void feeRate_presentInParsedParams_returnsParsedValue() {
        var params = parser.parseParams("""
                {"fee_rate": 0.008}
                """);

        assertThat(params.feeRate()).isEqualByComparingTo("0.008");
        assertThat(parser.feeRate(params)).isEqualByComparingTo("0.008");
    }

    @Test
    void feeRate_explicitZero_isNotTreatedAsAbsent() {
        var params = parser.parseParams("""
                {"fee_rate": 0}
                """);

        assertThat(parser.feeRate(params)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // C1 (2026-07-12 audit): bond-sleeve interest yield -- mirrors the feeRate tests above exactly.

    @Test
    void interestYield_absentFromParams_defaultsToPoint04() {
        assertThat(parser.interestYield(ScenarioParams.EMPTY)).isEqualByComparingTo("0.04");
    }

    @Test
    void interestYield_presentInParsedParams_returnsParsedValue() {
        var params = parser.parseParams("""
                {"interest_yield": 0.05}
                """);

        assertThat(params.interestYield()).isEqualByComparingTo("0.05");
        assertThat(parser.interestYield(params)).isEqualByComparingTo("0.05");
    }

    @Test
    void interestYield_explicitZero_isNotTreatedAsAbsent() {
        var params = parser.parseParams("""
                {"interest_yield": 0}
                """);

        assertThat(parser.interestYield(params)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // C10 (2026-07-12 audit): optional pre-1972 (Depression-era) capital-market window.

    @Test
    void includeDepressionYears_absentFromParams_defaultsToFalse() {
        assertThat(parser.includeDepressionYears(ScenarioParams.EMPTY)).isFalse();
    }

    @Test
    void includeDepressionYears_presentAndTrueInParsedParams_returnsTrue() {
        var params = parser.parseParams("""
                {"include_depression_years": true}
                """);

        assertThat(params.includeDepressionYears()).isTrue();
        assertThat(parser.includeDepressionYears(params)).isTrue();
    }

    @Test
    void includeDepressionYears_explicitFalse_returnsFalse() {
        var params = parser.parseParams("""
                {"include_depression_years": false}
                """);

        assertThat(parser.includeDepressionYears(params)).isFalse();
    }

    // Stochastic mortality (sub-project B, T3): snake_case params_json keys map onto the record's
    // camelCase fields via ScenarioParams' Jackson SnakeCaseStrategy -- no ScenarioParamsParser
    // resolution helper needed (mirrors household fields, which are also read raw downstream).

    @Test
    void parseParams_stochasticMortalityFields_mapSnakeCaseKeysToRecordFields() {
        var params = parser.parseParams("""
                {"stochastic_mortality": true, "primary_sex": "male", "spouse_sex": "female",
                 "longevity_conditional_age": 100}
                """);

        assertThat(params.stochasticMortality()).isTrue();
        assertThat(params.primarySex()).isEqualTo("male");
        assertThat(params.spouseSex()).isEqualTo("female");
        assertThat(params.longevityConditionalAge()).isEqualTo(100);
    }

    @Test
    void parseParams_stochasticMortalityFieldsAbsent_areNull() {
        var params = parser.parseParams("{}");

        assertThat(params.stochasticMortality()).isNull();
        assertThat(params.primarySex()).isNull();
        assertThat(params.spouseSex()).isNull();
        assertThat(params.longevityConditionalAge()).isNull();
    }

    // === Spending-tier parsing ===
    //
    // parseTierBasedPlan had no test at all. It accepts BOTH camelCase and snake_case keys for
    // every tier field, and the API serialises snake_case globally — so the snake_case arm is the
    // production path and it was the untested one. A key that silently misses its branch falls
    // through to the default (zero expenses, start age 0), which does not fail: it quietly plans a
    // retirement that spends nothing in that tier.

    private static SpendingProfileInput profile(String tiersJson) {
        return new SpendingProfileInput(new BigDecimal("40000"), new BigDecimal("20000"), tiersJson);
    }

    @Test
    void parseTierBasedPlan_camelCaseKeys_populateEveryTierField() {
        var plan = parser.parseTierBasedPlan(profile("""
                [{"name":"Go-go","startAge":65,"endAge":75,
                  "essentialExpenses":45000,"discretionaryExpenses":25000}]
                """));

        assertThat(plan).isNotNull();
        assertThat(plan.spendingTiers()).singleElement().satisfies(tier -> {
            assertThat(tier.name()).isEqualTo("Go-go");
            assertThat(tier.startAge()).isEqualTo(65);
            assertThat(tier.endAge()).isEqualTo(75);
            assertThat(tier.essentialExpenses()).isEqualByComparingTo("45000");
            assertThat(tier.discretionaryExpenses()).isEqualByComparingTo("25000");
        });
    }

    @Test
    void parseTierBasedPlan_snakeCaseKeys_produceTheIdenticalTier() {
        var camel = parser.parseTierBasedPlan(profile("""
                [{"name":"Go-go","startAge":65,"endAge":75,
                  "essentialExpenses":45000,"discretionaryExpenses":25000}]
                """));
        var snake = parser.parseTierBasedPlan(profile("""
                [{"name":"Go-go","start_age":65,"end_age":75,
                  "essential_expenses":45000,"discretionary_expenses":25000}]
                """));

        assertThat(snake).isNotNull();
        assertThat(snake.spendingTiers())
                .as("the two key styles must be interchangeable, not one silently defaulting")
                .usingRecursiveComparison()
                .isEqualTo(camel.spendingTiers());
    }

    @Test
    void parseTierBasedPlan_tierMissingEveryOptionalField_fallsBackToZeroesAndNoEndAge() {
        var plan = parser.parseTierBasedPlan(profile("[{}]"));

        assertThat(plan.spendingTiers()).singleElement().satisfies(tier -> {
            assertThat(tier.name()).isEmpty();
            assertThat(tier.startAge()).isZero();
            assertThat(tier.endAge()).as("an open-ended tier carries a null end age").isNull();
            assertThat(tier.essentialExpenses()).isEqualByComparingTo("0");
            assertThat(tier.discretionaryExpenses()).isEqualByComparingTo("0");
        });
    }

    @Test
    void parseTierBasedPlan_explicitJsonNulls_areTreatedAsAbsentRatherThanParsed() {
        var plan = parser.parseTierBasedPlan(profile("""
                [{"name":null,"startAge":null,"endAge":null,
                  "essentialExpenses":null,"discretionaryExpenses":null}]
                """));

        assertThat(plan.spendingTiers()).singleElement().satisfies(tier -> {
            assertThat(tier.startAge()).isZero();
            assertThat(tier.endAge()).isNull();
            assertThat(tier.essentialExpenses()).isEqualByComparingTo("0");
        });
    }

    @Test
    void parseTierBasedPlan_nullProfile_returnsNoPlanAtAll() {
        assertThat(parser.parseTierBasedPlan(null)).isNull();
    }

    @Test
    void parseTierBasedPlan_absentBlankOrEmptyArrayTiers_yieldAPlanWithNoTiers() {
        for (String tiers : new String[]{null, "", "   ", "[]", " [] "}) {
            var plan = parser.parseTierBasedPlan(profile(tiers));

            assertThat(plan).as("tiers=%s", tiers).isNotNull();
            assertThat(plan.spendingTiers()).as("tiers=%s", tiers).isEmpty();
            assertThat(plan.essentialExpenses()).isEqualByComparingTo("40000");
        }
    }

    @Test
    void parseTierBasedPlan_malformedTierJson_fallsBackToTheBaseProfileInsteadOfThrowing() {
        // A corrupt spending_tiers column must not take down the whole projection; the profile's
        // base essential/discretionary figures still apply.
        var plan = parser.parseTierBasedPlan(profile("{not valid json"));

        assertThat(plan).isNotNull();
        assertThat(plan.spendingTiers()).isEmpty();
        assertThat(plan.essentialExpenses()).isEqualByComparingTo("40000");
        assertThat(plan.discretionaryExpenses()).isEqualByComparingTo("20000");
    }

    @Test
    void parseTierBasedPlan_multipleTiers_arePreservedInOrder() {
        var plan = parser.parseTierBasedPlan(profile("""
                [{"name":"Go-go","start_age":65,"end_age":74,"essential_expenses":45000},
                 {"name":"Slow-go","start_age":75,"end_age":84,"essential_expenses":40000},
                 {"name":"No-go","start_age":85,"essential_expenses":38000}]
                """));

        assertThat(plan.spendingTiers()).hasSize(3)
                .extracting(t -> t.name()).containsExactly("Go-go", "Slow-go", "No-go");
        assertThat(plan.spendingTiers().get(2).endAge())
                .as("the final open-ended tier keeps a null end age")
                .isNull();
    }

    @Test
    void defaultParams_isTheEmptyParams() {
        assertThat(parser.defaultParams()).isSameAs(ScenarioParams.EMPTY);
    }
}
