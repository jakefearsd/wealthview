package com.wealthview.core.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void constants_matchHistoricalDefault() {
        assertThat(Money.SCALE).isEqualTo(4);
        assertThat(Money.ROUNDING).isEqualTo(RoundingMode.HALF_UP);
    }

    @Test
    void scale_padsLowerPrecisionToFourDecimals() {
        var value = new BigDecimal("12.5");

        var result = Money.scale(value);

        assertThat(result).isEqualByComparingTo("12.5");
        assertThat(result.scale()).isEqualTo(4);
        assertThat(result.toPlainString()).isEqualTo("12.5000");
    }

    @Test
    void scale_roundsHigherPrecisionHalfUp() {
        var value = new BigDecimal("0.12345");

        var result = Money.scale(value);

        assertThat(result.toPlainString()).isEqualTo("0.1235");
    }

    @Test
    void scale_roundsHalfTowardLargerMagnitude() {
        var value = new BigDecimal("0.00005");

        var result = Money.scale(value);

        assertThat(result.toPlainString()).isEqualTo("0.0001");
    }

    @Test
    void scale_isEquivalentToInlineSetScale() {
        var value = new BigDecimal("99.9999999");

        var helper = Money.scale(value);
        var inline = value.setScale(4, RoundingMode.HALF_UP);

        assertThat(helper).isEqualByComparingTo(inline);
        assertThat(helper.scale()).isEqualTo(inline.scale());
    }
}
