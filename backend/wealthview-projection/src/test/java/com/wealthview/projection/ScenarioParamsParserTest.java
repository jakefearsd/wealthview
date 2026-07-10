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
}
