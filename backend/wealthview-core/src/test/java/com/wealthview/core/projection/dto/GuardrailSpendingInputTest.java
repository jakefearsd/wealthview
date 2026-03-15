package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailSpendingInputTest {

    @Test
    void byYear_returnsMapKeyedByYear() {
        var y1 = new GuardrailYearlySpending(2030, 62, new BigDecimal("75000"),
                new BigDecimal("62000"), new BigDecimal("91000"), new BigDecimal("30000"),
                new BigDecimal("45000"), BigDecimal.ZERO, new BigDecimal("75000"), "Early");
        var y2 = new GuardrailYearlySpending(2031, 63, new BigDecimal("76000"),
                new BigDecimal("63000"), new BigDecimal("92000"), new BigDecimal("30000"),
                new BigDecimal("46000"), BigDecimal.ZERO, new BigDecimal("76000"), "Early");

        var input = new GuardrailSpendingInput(List.of(y1, y2));
        var map = input.byYear();

        assertThat(map).hasSize(2);
        assertThat(map.get(2030)).isEqualTo(y1);
        assertThat(map.get(2031)).isEqualTo(y2);
        assertThat(map.get(2032)).isNull();
    }

    @Test
    void byYear_emptyList_returnsEmptyMap() {
        var input = new GuardrailSpendingInput(List.of());
        var map = input.byYear();

        assertThat(map).isEmpty();
    }
}
