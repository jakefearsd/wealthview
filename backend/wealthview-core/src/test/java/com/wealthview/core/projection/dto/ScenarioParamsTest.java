package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.strategy.WithdrawalOrder;
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
                null, null, null, null, null, null, null, null, null, null, null, null);

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
                new BigDecimal("0.003"), Boolean.TRUE, new BigDecimal("0.045"), null, null, null, null, null);

        var parsed = ScenarioParams.parseOrEmpty(mapper, original.toJson(mapper));

        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void toJson_dividendYieldPresent_writesSnakeCaseKey() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, new BigDecimal("0.021"), null, null, null, null, null, null, null, null);

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("dividend_yield").decimalValue()).isEqualByComparingTo("0.021");
    }

    @Test
    void from_dividendYieldPresent_passesThrough() {
        var request = scenarioRequestWith(new BigDecimal("0.021"), null);

        var params = ScenarioParams.from(request);

        assertThat(params.dividendYield()).isEqualByComparingTo("0.021");
    }

    @Test
    void from_dividendYieldNull_staysNullForDefault() {
        var request = scenarioRequestWith(null, null);

        assertThat(ScenarioParams.from(request).dividendYield()).isNull();
    }

    @Test
    void toJson_feeRatePresent_writesSnakeCaseKey() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, new BigDecimal("0.003"), null, null, null, null, null, null, null);

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("fee_rate").decimalValue()).isEqualByComparingTo("0.003");
    }

    @Test
    void from_feeRatePresent_passesThrough() {
        var request = scenarioRequestWith(null, new BigDecimal("0.003"));

        var params = ScenarioParams.from(request);

        assertThat(params.feeRate()).isEqualByComparingTo("0.003");
    }

    @Test
    void from_feeRateNull_staysNullForDefault() {
        var request = scenarioRequestWith(null, null);

        assertThat(ScenarioParams.from(request).feeRate()).isNull();
    }

    // C1 (2026-07-12 audit): bond-sleeve interest yield -- mirrors the feeRate tests above exactly.

    @Test
    void toJson_interestYieldPresent_writesSnakeCaseKey() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, new BigDecimal("0.04"), null, null, null, null, null);

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("interest_yield").decimalValue()).isEqualByComparingTo("0.04");
    }

    @Test
    void from_interestYieldPresent_passesThrough() {
        var request = scenarioRequestWithInterestYield(new BigDecimal("0.04"));

        var params = ScenarioParams.from(request);

        assertThat(params.interestYield()).isEqualByComparingTo("0.04");
    }

    @Test
    void from_interestYieldNull_staysNullForDefault() {
        var request = scenarioRequestWithInterestYield(null);

        assertThat(ScenarioParams.from(request).interestYield()).isNull();
    }

    private ScenarioRequest scenarioRequestWithInterestYield(BigDecimal interestYield) {
        return new ScenarioRequest(
                "Plan", null, 90, new BigDecimal("0.03"), 1970, null,
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                null, null, null, interestYield,
                null, null, null, null, null,
                List.of(), null, null, null);
    }

    @Test
    void toJson_includeDepressionYearsPresent_writesSnakeCaseKey() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, Boolean.TRUE, null, null, null, null, null, null);

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("include_depression_years").asBoolean()).isTrue();
    }

    @Test
    void from_includeDepressionYearsPresent_passesThrough() {
        var request = scenarioRequestWithIncludeDepressionYears(Boolean.TRUE);

        var params = ScenarioParams.from(request);

        assertThat(params.includeDepressionYears()).isTrue();
    }

    @Test
    void from_includeDepressionYearsNull_staysNullForDefault() {
        var request = scenarioRequestWithIncludeDepressionYears(null);

        assertThat(ScenarioParams.from(request).includeDepressionYears()).isNull();
    }

    private ScenarioRequest scenarioRequestWith(BigDecimal dividendYield, BigDecimal feeRate) {
        return new ScenarioRequest(
                "Plan", null, 90, new BigDecimal("0.03"), 1970, null,
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                dividendYield, feeRate, null, null, null, null);
    }

    private ScenarioRequest scenarioRequestWithIncludeDepressionYears(Boolean includeDepressionYears) {
        return new ScenarioRequest(
                "Plan", null, 90, new BigDecimal("0.03"), 1970, null,
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                null, null, includeDepressionYears, null, null, null, null);
    }

    // Household/survivor modeling (sub-project A, T3): mirrors the interestYield/
    // includeDepressionYears test pattern exactly for each new field.

    @Test
    void toJson_householdFieldsPresent_writesSnakeCaseKeys() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                1972, 88, 90, new BigDecimal("0.8"), Boolean.TRUE);

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("spouse_birth_year").asInt()).isEqualTo(1972);
        assertThat(node.get("primary_death_age").asInt()).isEqualTo(88);
        assertThat(node.get("spouse_death_age").asInt()).isEqualTo(90);
        assertThat(node.get("survivor_spending_factor").decimalValue()).isEqualByComparingTo("0.8");
        assertThat(node.get("community_property").asBoolean()).isTrue();
    }

    @Test
    void from_spouseBirthYearPresent_passesThrough() {
        var request = scenarioRequestWithHousehold(1972, null, null, null, null);

        assertThat(ScenarioParams.from(request).spouseBirthYear()).isEqualTo(1972);
    }

    @Test
    void from_spouseBirthYearNull_staysNullForDefault() {
        var request = scenarioRequestWithHousehold(null, null, null, null, null);

        assertThat(ScenarioParams.from(request).spouseBirthYear()).isNull();
    }

    @Test
    void from_primaryDeathAgePresent_passesThrough() {
        var request = scenarioRequestWithHousehold(1972, 88, null, null, null);

        assertThat(ScenarioParams.from(request).primaryDeathAge()).isEqualTo(88);
    }

    @Test
    void from_primaryDeathAgeNull_staysNullForDefault() {
        var request = scenarioRequestWithHousehold(1972, null, null, null, null);

        assertThat(ScenarioParams.from(request).primaryDeathAge()).isNull();
    }

    @Test
    void from_spouseDeathAgePresent_passesThrough() {
        var request = scenarioRequestWithHousehold(1972, null, 90, null, null);

        assertThat(ScenarioParams.from(request).spouseDeathAge()).isEqualTo(90);
    }

    @Test
    void from_spouseDeathAgeNull_staysNullForDefault() {
        var request = scenarioRequestWithHousehold(1972, null, null, null, null);

        assertThat(ScenarioParams.from(request).spouseDeathAge()).isNull();
    }

    @Test
    void from_survivorSpendingFactorPresent_passesThrough() {
        var request = scenarioRequestWithHousehold(1972, null, null, new BigDecimal("0.8"), null);

        assertThat(ScenarioParams.from(request).survivorSpendingFactor()).isEqualByComparingTo("0.8");
    }

    @Test
    void from_survivorSpendingFactorNull_staysNullForDefault() {
        var request = scenarioRequestWithHousehold(1972, null, null, null, null);

        assertThat(ScenarioParams.from(request).survivorSpendingFactor()).isNull();
    }

    @Test
    void from_communityPropertyPresent_passesThrough() {
        var request = scenarioRequestWithHousehold(1972, null, null, null, Boolean.TRUE);

        assertThat(ScenarioParams.from(request).communityProperty()).isTrue();
    }

    @Test
    void from_communityPropertyNull_staysNullForDefault() {
        var request = scenarioRequestWithHousehold(1972, null, null, null, null);

        assertThat(ScenarioParams.from(request).communityProperty()).isNull();
    }

    private ScenarioRequest scenarioRequestWithHousehold(Integer spouseBirthYear, Integer primaryDeathAge,
                                                          Integer spouseDeathAge, BigDecimal survivorSpendingFactor,
                                                          Boolean communityProperty) {
        return new ScenarioRequest(
                "Plan", null, 90, new BigDecimal("0.03"), 1970, null,
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                null, null, null, null,
                spouseBirthYear, primaryDeathAge, spouseDeathAge, survivorSpendingFactor, communityProperty,
                List.of(), null, null, null);
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
