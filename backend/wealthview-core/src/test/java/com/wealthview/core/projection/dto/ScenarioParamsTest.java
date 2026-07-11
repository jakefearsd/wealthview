package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

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
                null, null, null, null, null);

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
                new BigDecimal("0.003"));

        var parsed = ScenarioParams.parseOrEmpty(mapper, original.toJson(mapper));

        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void toJson_dividendYieldPresent_writesSnakeCaseKey() throws Exception {
        var params = new ScenarioParams(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, new BigDecimal("0.021"), null);

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
                null, null, null, null, new BigDecimal("0.003"));

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

    private ScenarioRequest scenarioRequestWith(BigDecimal dividendYield, BigDecimal feeRate) {
        return new ScenarioRequest(
                "Plan", null, 90, new BigDecimal("0.03"), 1970, null,
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                dividendYield, feeRate, null, null, null, null);
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
