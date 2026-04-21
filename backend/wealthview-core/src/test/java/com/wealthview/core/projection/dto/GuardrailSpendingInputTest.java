package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailSpendingInputTest {

    @Test
    void conversionSchedule_viaSpendingPlanReference_exposesSchedule() {
        Map<Integer, BigDecimal> schedule = Map.of(2030, new BigDecimal("25000"));
        SpendingPlan plan = new GuardrailSpendingInput(List.of(), schedule);

        assertThat(plan.conversionSchedule())
                .hasValueSatisfying(s -> assertThat(s).containsEntry(2030, new BigDecimal("25000")));
    }

    @Test
    void conversionSchedule_viaSpendingPlanReference_emptyWhenUnset() {
        SpendingPlan plan = new GuardrailSpendingInput(List.of());

        assertThat(plan.conversionSchedule()).isEmpty();
    }


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

    @Test
    void resolveYear_yearFound_returnsPortfolioWithdrawalAndRecommended() {
        var y1 = new GuardrailYearlySpending(2030, 62, new BigDecimal("75000"),
                new BigDecimal("62000"), new BigDecimal("91000"), new BigDecimal("30000"),
                new BigDecimal("45000"), BigDecimal.ZERO, new BigDecimal("70000"), "Early");

        var input = new GuardrailSpendingInput(List.of(y1));
        var result = input.resolveYear(2030, 62, 1, new BigDecimal("0.03"), BigDecimal.ZERO);

        assertThat(result.portfolioWithdrawal()).isEqualByComparingTo(new BigDecimal("70000"));
        assertThat(result.totalSpending()).isEqualByComparingTo(new BigDecimal("75000"));
        assertThat(result.essential()).isEqualByComparingTo(new BigDecimal("30000"));
        assertThat(result.discretionary()).isEqualByComparingTo(new BigDecimal("45000"));
    }

    @Test
    void resolveYear_yearNotFound_returnsZeros() {
        var y1 = new GuardrailYearlySpending(2030, 62, new BigDecimal("75000"),
                new BigDecimal("62000"), new BigDecimal("91000"), new BigDecimal("30000"),
                new BigDecimal("45000"), BigDecimal.ZERO, new BigDecimal("70000"), "Early");

        var input = new GuardrailSpendingInput(List.of(y1));
        var result = input.resolveYear(2035, 67, 6, new BigDecimal("0.03"), BigDecimal.ZERO);

        assertThat(result.portfolioWithdrawal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalSpending()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.essential()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.discretionary()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
