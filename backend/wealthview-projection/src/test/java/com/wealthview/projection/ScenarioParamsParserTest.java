package com.wealthview.projection;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.ScenarioParams;

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
}
