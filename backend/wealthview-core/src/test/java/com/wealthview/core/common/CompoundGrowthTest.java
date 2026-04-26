package com.wealthview.core.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CompoundGrowthTest {

    @Test
    void factor_bigDecimal_returnsOnePowZero() {
        var result = CompoundGrowth.factor(new BigDecimal("0.03"), 0);

        assertThat(result).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void factor_bigDecimal_compoundsRateOverYears() {
        var result = CompoundGrowth.factor(new BigDecimal("0.05"), 3);

        // 1.05^3 = 1.157625
        assertThat(result).isEqualByComparingTo(new BigDecimal("1.157625"));
    }

    @Test
    void factor_double_returnsOnePowZero() {
        var result = CompoundGrowth.factor(0.05, 0);

        assertThat(result).isEqualTo(1.0);
    }

    @Test
    void factor_double_compoundsRateOverYears() {
        var result = CompoundGrowth.factor(0.05, 3);

        assertThat(result).isCloseTo(1.157625, within(1e-9));
    }

    @Test
    void inflate_bigDecimal_scalesPrincipalByCompoundFactor() {
        var result = CompoundGrowth.inflate(new BigDecimal("100"), new BigDecimal("0.05"), 3);

        // 100 * 1.05^3 = 115.7625
        assertThat(result).isEqualByComparingTo(new BigDecimal("115.7625"));
    }

    @Test
    void inflate_double_scalesPrincipalByCompoundFactor() {
        var result = CompoundGrowth.inflate(100.0, 0.05, 3);

        assertThat(result).isCloseTo(115.7625, within(1e-9));
    }

    @Test
    void factor_matchesInlinedBigDecimalFormulaItReplaces() {
        var rate = new BigDecimal("0.025");
        var years = 17;

        var helper = CompoundGrowth.factor(rate, years);
        var inline = BigDecimal.ONE.add(rate).pow(years);

        assertThat(helper).isEqualTo(inline);
    }

    @Test
    void factor_matchesInlinedDoubleFormulaItReplaces() {
        var rate = 0.025;
        var years = 17;

        var helper = CompoundGrowth.factor(rate, years);
        var inline = Math.pow(1 + rate, years);

        assertThat(helper).isEqualTo(inline);
    }
}
