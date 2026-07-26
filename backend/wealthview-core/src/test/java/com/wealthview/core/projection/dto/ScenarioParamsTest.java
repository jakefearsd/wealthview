package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.testutil.ScenarioRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioParamsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toJson_writesSnakeCaseKeysAndOmitsNulls() throws Exception {
        var params = new ScenarioParams(
                1968, new BigDecimal("0.04"), null,
                null, null, "married_filing_jointly",
                null, null, "traditional_first",
                new BigDecimal("0.22"), null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);

        var json = params.toJson(mapper);

        var node = mapper.readTree(json);
        assertThat(node.get("birth_year").asInt()).isEqualTo(1968);
        assertThat(node.get("withdrawal_rate").decimalValue()).isEqualByComparingTo("0.04");
        assertThat(node.get("filing_status").asText()).isEqualTo("married_filing_jointly");
        assertThat(node.get("withdrawal_order").asText()).isEqualTo("traditional_first");
        assertThat(node.get("dynamic_sequencing_bracket_rate").decimalValue()).isEqualByComparingTo("0.22");
        assertThat(node.has("withdrawal_strategy")).isFalse();
        assertThat(node.has("state")).isFalse();
    }

    @Test
    void toJson_allFieldsNull_returnsNull() {
        assertThat(ScenarioParams.EMPTY.toJson(mapper)).isNull();
    }

    @Test
    void parseOrEmpty_roundTripsWhatToJsonWrote() {
        var original = new ScenarioParams(
                1970, new BigDecimal("0.035"), "dynamic",
                new BigDecimal("1.05"), new BigDecimal("0.95"), "single",
                new BigDecimal("12000"), new BigDecimal("25000"), "dynamic_sequencing",
                new BigDecimal("0.24"), "fill_bracket", new BigDecimal("0.22"), 2030,
                "CA", new BigDecimal("9000"), new BigDecimal("14000"), new BigDecimal("0.021"),
                new BigDecimal("0.003"), Boolean.TRUE, new BigDecimal("0.045"), null, null, null, null, null,
                null, null, null, null);

        var parsed = ScenarioParams.parseOrEmpty(mapper, original.toJson(mapper));

        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void toJson_dividendYieldPresent_writesSnakeCaseKey() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, new BigDecimal("0.021"), null, null, null, null, null, null, null, null,
                null, null, null, null);

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("dividend_yield").decimalValue()).isEqualByComparingTo("0.021");
    }

    @Test
    void toJson_feeRatePresent_writesSnakeCaseKey() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, new BigDecimal("0.003"), null, null, null, null, null, null, null,
                null, null, null, null);

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("fee_rate").decimalValue()).isEqualByComparingTo("0.003");
    }

    // C1 (2026-07-12 audit): bond-sleeve interest yield -- mirrors the feeRate tests above exactly.

    @Test
    void toJson_interestYieldPresent_writesSnakeCaseKey() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, new BigDecimal("0.04"), null, null, null, null, null,
                null, null, null, null);

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("interest_yield").decimalValue()).isEqualByComparingTo("0.04");
    }

    @Test
    void toJson_includeDepressionYearsPresent_writesSnakeCaseKey() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, Boolean.TRUE, null, null, null, null, null, null,
                null, null, null, null);

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("include_depression_years").asBoolean()).isTrue();
    }

    // Household/survivor modeling (sub-project A, T3): mirrors the interestYield/
    // includeDepressionYears test pattern exactly for each new field.

    @Test
    void toJson_householdFieldsPresent_writesSnakeCaseKeys() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                1972, 88, 90, new BigDecimal("0.8"), Boolean.TRUE,
                null, null, null, null);

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("spouse_birth_year").asInt()).isEqualTo(1972);
        assertThat(node.get("primary_death_age").asInt()).isEqualTo(88);
        assertThat(node.get("spouse_death_age").asInt()).isEqualTo(90);
        assertThat(node.get("survivor_spending_factor").decimalValue()).isEqualByComparingTo("0.8");
        assertThat(node.get("community_property").asBoolean()).isTrue();
    }

    // Stochastic mortality (sub-project B, T3): mirrors the household field test pattern above
    // exactly for each new field.

    @Test
    void toJson_stochasticMortalityFieldsPresent_writesSnakeCaseKeys() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null,
                Boolean.TRUE, "male", "female", 100);

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("stochastic_mortality").asBoolean()).isTrue();
        assertThat(node.get("primary_sex").asText()).isEqualTo("male");
        assertThat(node.get("spouse_sex").asText()).isEqualTo("female");
        assertThat(node.get("longevity_conditional_age").asInt()).isEqualTo(100);
    }

    // The from_XPresent_passesThrough / from_XNull_staysNullForDefault family below used to be 26
    // hand-written tests (13 fields x 2), each differing only in which ScenarioRequestBuilder with*
    // call it exercised and which ScenarioParams accessor it read back. Collapsed into two
    // parameterized tests sharing one case list -- every field the deleted tests covered still gets
    // its own "present" and "null" execution (26 total), see the Task 19 report for the mapping.

    private record PassThroughCase(
            String fieldName,
            UnaryOperator<ScenarioRequestBuilder> withValue,
            Object sampleValue,
            Function<ScenarioParams, Object> extractor) {

        @Override
        public String toString() {
            return fieldName;
        }
    }

    private static Stream<PassThroughCase> passThroughFields() {
        return Stream.of(
                new PassThroughCase("dividendYield",
                        b -> b.withDividendYield(new BigDecimal("0.021")),
                        new BigDecimal("0.021"), ScenarioParams::dividendYield),
                new PassThroughCase("feeRate",
                        b -> b.withFeeRate(new BigDecimal("0.003")),
                        new BigDecimal("0.003"), ScenarioParams::feeRate),
                new PassThroughCase("interestYield",
                        b -> b.withInterestYield(new BigDecimal("0.04")),
                        new BigDecimal("0.04"), ScenarioParams::interestYield),
                new PassThroughCase("includeDepressionYears",
                        b -> b.withIncludeDepressionYears(Boolean.TRUE),
                        Boolean.TRUE, ScenarioParams::includeDepressionYears),
                new PassThroughCase("spouseBirthYear",
                        b -> b.withSpouseBirthYear(1972),
                        1972, ScenarioParams::spouseBirthYear),
                new PassThroughCase("primaryDeathAge",
                        b -> b.withPrimaryDeathAge(88),
                        88, ScenarioParams::primaryDeathAge),
                new PassThroughCase("spouseDeathAge",
                        b -> b.withSpouseDeathAge(90),
                        90, ScenarioParams::spouseDeathAge),
                new PassThroughCase("survivorSpendingFactor",
                        b -> b.withSurvivorSpendingFactor(new BigDecimal("0.8")),
                        new BigDecimal("0.8"), ScenarioParams::survivorSpendingFactor),
                new PassThroughCase("communityProperty",
                        b -> b.withCommunityProperty(Boolean.TRUE),
                        Boolean.TRUE, ScenarioParams::communityProperty),
                new PassThroughCase("stochasticMortality",
                        b -> b.withStochasticMortality(Boolean.TRUE),
                        Boolean.TRUE, ScenarioParams::stochasticMortality),
                new PassThroughCase("primarySex",
                        b -> b.withPrimarySex("male"),
                        "male", ScenarioParams::primarySex),
                new PassThroughCase("spouseSex",
                        b -> b.withSpouseSex("female"),
                        "female", ScenarioParams::spouseSex),
                new PassThroughCase("longevityConditionalAge",
                        b -> b.withLongevityConditionalAge(100),
                        100, ScenarioParams::longevityConditionalAge));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("passThroughFields")
    void from_fieldPresent_passesThrough(PassThroughCase testCase) {
        var request = testCase.withValue().apply(ScenarioRequestBuilder.builder()).build();

        var actual = testCase.extractor().apply(ScenarioParams.from(request));

        assertThat(actual).isEqualTo(testCase.sampleValue());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("passThroughFields")
    void from_fieldNull_staysNullForDefault(PassThroughCase testCase) {
        var request = ScenarioRequestBuilder.builder().build();

        var actual = testCase.extractor().apply(ScenarioParams.from(request));

        assertThat(actual).isNull();
    }

    @Test
    void parseOrEmpty_nullBlankOrMalformed_returnsEmpty() {
        assertThat(ScenarioParams.parseOrEmpty(mapper, null)).isEqualTo(ScenarioParams.EMPTY);
        assertThat(ScenarioParams.parseOrEmpty(mapper, "  ")).isEqualTo(ScenarioParams.EMPTY);
        assertThat(ScenarioParams.parseOrEmpty(mapper, "{not json")).isEqualTo(ScenarioParams.EMPTY);
    }

    @Test
    void parseOrEmpty_ignoresUnknownFields() {
        var parsed = ScenarioParams.parseOrEmpty(mapper,
                """
                {"birth_year": 1965, "some_future_field": "x"}
                """);

        assertThat(parsed.birthYear()).isEqualTo(1965);
    }

    @Test
    void resolvedWithdrawalOrder_defaultsToTaxableFirst() {
        assertThat(ScenarioParams.EMPTY.resolvedWithdrawalOrder())
                .isEqualTo(WithdrawalOrder.TAXABLE_FIRST);
        assertThat(ScenarioParams.parseOrEmpty(mapper, """
                {"withdrawal_order": "roth_first"}
                """).resolvedWithdrawalOrder()).isEqualTo(WithdrawalOrder.ROTH_FIRST);
    }
}
