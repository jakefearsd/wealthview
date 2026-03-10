package com.wealthview.core.property;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepreciationCalculatorTest {

    private final DepreciationCalculator calculator = new DepreciationCalculator();

    @Test
    void computeStraightLine_fullYear_returnsCorrectAnnualAmount() {
        // $300k purchase, $50k land, 27.5 years, placed in service Jan 1
        var schedule = calculator.computeStraightLine(
                new BigDecimal("300000"), new BigDecimal("50000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("27.5"));

        // Depreciable basis = 300000 - 50000 = 250000
        // Annual = 250000 / 27.5 = 9090.9090909...
        // Jan mid-month: 11.5/12 of first year
        var firstYear = schedule.get(2020);
        assertThat(firstYear).isEqualByComparingTo("8712.1212");

        // Full year 2
        var secondYear = schedule.get(2021);
        assertThat(secondYear).isEqualByComparingTo("9090.9091");

        // 27.5 years: partial first year + 26 full years + partial last year = 28 entries
        assertThat(schedule).hasSize(28);
    }

    @Test
    void computeStraightLine_midYear_proratesFirstYear() {
        // Placed in service July 15 → mid-month convention = July treated as half month
        // Months remaining = 5.5 (Jul half + Aug-Dec full = 5 + 0.5)
        var schedule = calculator.computeStraightLine(
                new BigDecimal("275000"), new BigDecimal("0"),
                LocalDate.of(2022, 7, 1), new BigDecimal("27.5"));

        // Annual = 275000 / 27.5 = 10000
        // First year: 5.5/12 * 10000 = 4583.3333
        assertThat(schedule.get(2022)).isEqualByComparingTo("4583.3333");

        // Full year
        assertThat(schedule.get(2023)).isEqualByComparingTo("10000.0000");
    }

    @Test
    void computeStraightLine_lastYearGetsRemainder() {
        var schedule = calculator.computeStraightLine(
                new BigDecimal("275000"), new BigDecimal("0"),
                LocalDate.of(2022, 7, 1), new BigDecimal("27.5"));

        // Total depreciation should equal depreciable basis
        var total = schedule.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("275000.0000");
    }

    @Test
    void computeStraightLine_commercialProperty_uses39Years() {
        var schedule = calculator.computeStraightLine(
                new BigDecimal("500000"), new BigDecimal("100000"),
                LocalDate.of(2023, 1, 1), new BigDecimal("39"));

        // Depreciable basis = 400000, annual = 400000/39 = 10256.4102564...
        var fullYear = schedule.get(2024);
        assertThat(fullYear).isEqualByComparingTo("10256.4103");

        var total = schedule.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("400000.0000");
    }

    @Test
    void computeStraightLine_zeroBasis_returnsEmptySchedule() {
        var schedule = calculator.computeStraightLine(
                new BigDecimal("100000"), new BigDecimal("100000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("27.5"));

        assertThat(schedule).isEmpty();
    }

    @Test
    void getDepreciationForYear_straightLine_returnsCorrectAmount() {
        var result = calculator.getDepreciationForYear(
                "straight_line",
                new BigDecimal("300000"), new BigDecimal("50000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("27.5"),
                2021, Map.of());

        assertThat(result).isEqualByComparingTo("9090.9091");
    }

    @Test
    void getDepreciationForYear_costSegregation_usesProvidedSchedule() {
        var costSegSchedule = Map.of(
                2020, new BigDecimal("50000"),
                2021, new BigDecimal("30000"),
                2022, new BigDecimal("20000"));

        var result = calculator.getDepreciationForYear(
                "cost_segregation",
                new BigDecimal("300000"), new BigDecimal("50000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("27.5"),
                2021, costSegSchedule);

        assertThat(result).isEqualByComparingTo("30000");
    }

    @Test
    void getDepreciationForYear_costSegregation_missingYear_returnsZero() {
        var costSegSchedule = Map.of(2020, new BigDecimal("50000"));

        var result = calculator.getDepreciationForYear(
                "cost_segregation",
                new BigDecimal("300000"), new BigDecimal("50000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("27.5"),
                2025, costSegSchedule);

        assertThat(result).isEqualByComparingTo("0");
    }

    @Test
    void getDepreciationForYear_none_returnsZero() {
        var result = calculator.getDepreciationForYear(
                "none",
                new BigDecimal("300000"), new BigDecimal("50000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("27.5"),
                2021, Map.of());

        assertThat(result).isEqualByComparingTo("0");
    }

    @Test
    void getDepreciationForYear_beforeInServiceDate_returnsZero() {
        var result = calculator.getDepreciationForYear(
                "straight_line",
                new BigDecimal("300000"), new BigDecimal("50000"),
                LocalDate.of(2020, 6, 1), new BigDecimal("27.5"),
                2019, Map.of());

        assertThat(result).isEqualByComparingTo("0");
    }
}
