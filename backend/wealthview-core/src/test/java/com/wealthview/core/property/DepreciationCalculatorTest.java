package com.wealthview.core.property;

import com.wealthview.core.property.dto.CostSegAllocation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    // --- Cost Segregation tests ---

    @Test
    void computeCostSegregation_100percentBonus_allBonusInYear1() {
        // $250k depreciable basis split across classes, 100% bonus
        // 5yr: $15k, 7yr: $8k, 15yr: $22k, 27.5yr: $205k
        var allocations = List.of(
                new CostSegAllocation("5yr", new BigDecimal("15000")),
                new CostSegAllocation("7yr", new BigDecimal("8000")),
                new CostSegAllocation("15yr", new BigDecimal("22000")),
                new CostSegAllocation("27_5yr", new BigDecimal("205000")));

        var schedule = calculator.computeCostSegregation(
                allocations, BigDecimal.ONE, LocalDate.of(2024, 7, 1), null);

        // Year 1 should include 100% bonus on 5yr+7yr+15yr = 45000
        // plus prorated structural for 2024
        var year1 = schedule.get(2024);
        assertThat(year1).isNotNull();
        // Bonus portion: 15000 + 8000 + 22000 = 45000
        // Structural: 205000/27.5 * (5.5/12) mid-month July
        var structuralAnnual = new BigDecimal("205000").divide(new BigDecimal("27.5"), 4, java.math.RoundingMode.HALF_UP);
        var structuralFirstYear = structuralAnnual.multiply(new BigDecimal("5.5"))
                .divide(new BigDecimal("12"), 4, java.math.RoundingMode.HALF_UP);
        assertThat(year1).isEqualByComparingTo(new BigDecimal("45000").add(structuralFirstYear));

        // Year 2 should be structural annual only (no bonus)
        assertThat(schedule.get(2025)).isEqualByComparingTo(structuralAnnual);
    }

    @Test
    void computeCostSegregation_80percentBonus_remainderOnStraightLine() {
        // 80% bonus on a single 5yr class: $20k allocation
        var allocations = List.of(
                new CostSegAllocation("5yr", new BigDecimal("20000")));

        var schedule = calculator.computeCostSegregation(
                allocations, new BigDecimal("0.80"), LocalDate.of(2024, 1, 1), null);

        // Bonus: 20000 * 0.80 = 16000 in year 1
        // Remaining: 4000 over 5 years straight-line = 800/yr
        // Year 1 (Jan in-service, mid-month = 11.5/12): bonus + prorated SL
        var slAnnual = new BigDecimal("4000").divide(new BigDecimal("5"), 4, java.math.RoundingMode.HALF_UP);
        var slFirstYear = slAnnual.multiply(new BigDecimal("11.5"))
                .divide(new BigDecimal("12"), 4, java.math.RoundingMode.HALF_UP);
        assertThat(schedule.get(2024)).isEqualByComparingTo(new BigDecimal("16000").add(slFirstYear));

        // Full years 2-5
        assertThat(schedule.get(2025)).isEqualByComparingTo(slAnnual);
        assertThat(schedule.get(2026)).isEqualByComparingTo(slAnnual);
    }

    @Test
    void computeCostSegregation_onlyStructural_matchesStraightLine() {
        // Only 27.5yr component — should match computeStraightLine exactly
        var allocations = List.of(
                new CostSegAllocation("27_5yr", new BigDecimal("275000")));

        var costSegSchedule = calculator.computeCostSegregation(
                allocations, BigDecimal.ONE, LocalDate.of(2022, 7, 1), null);
        var straightLineSchedule = calculator.computeStraightLine(
                new BigDecimal("275000"), BigDecimal.ZERO,
                LocalDate.of(2022, 7, 1), new BigDecimal("27.5"));

        assertThat(costSegSchedule).isEqualTo(straightLineSchedule);
    }

    @Test
    void computeCostSegregation_withStudyYear_catchUpDeduction() {
        // Property in service 2020, study done in 2024
        // 5yr class: $30k. With 100% bonus, should-have-taken in 2020 = $30k
        // Actually took under straight-line 27.5yr for the 5yr portion: 30000/27.5 * prorated
        // Catch-up = shouldHaveTaken - priorStraightLine → added to 2024
        var allocations = List.of(
                new CostSegAllocation("5yr", new BigDecimal("30000")),
                new CostSegAllocation("27_5yr", new BigDecimal("220000")));

        var schedule = calculator.computeCostSegregation(
                allocations, BigDecimal.ONE, LocalDate.of(2020, 1, 1), 2024);

        // Catch-up year should exist and be significantly larger than structural annual
        var catchUpYear = schedule.get(2024);
        assertThat(catchUpYear).isNotNull();

        // The bonus-eligible portion was $30k. Under 27.5yr straight-line from 2020-2023:
        // annual = 30000/27.5 = ~1090.91/yr, first year prorated 11.5/12 = ~1045.83
        // Total prior SL ≈ 1045.83 + 1090.91*3 = ~4318.56
        // Should-have-taken (100% bonus in 2020) = 30000
        // 481(a) adjustment = 30000 - 4318.56 = ~25681.44
        // Study year = structural 2024 + catch-up
        var structuralAnnual = new BigDecimal("220000").divide(new BigDecimal("27.5"), 4, java.math.RoundingMode.HALF_UP);
        assertThat(catchUpYear.compareTo(structuralAnnual)).isGreaterThan(0);

        // Total depreciation across all years should equal total allocations
        var totalDepr = schedule.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDepr).isEqualByComparingTo("250000");
    }

    @Test
    void computeCostSegregation_totalEqualsDepreciableBasis() {
        var allocations = List.of(
                new CostSegAllocation("5yr", new BigDecimal("15000")),
                new CostSegAllocation("7yr", new BigDecimal("8000")),
                new CostSegAllocation("15yr", new BigDecimal("22000")),
                new CostSegAllocation("27_5yr", new BigDecimal("205000")));

        var schedule = calculator.computeCostSegregation(
                allocations, new BigDecimal("0.60"), LocalDate.of(2023, 3, 1), null);

        var total = schedule.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("250000");
    }

    @Test
    void computeCostSegregation_emptyAllocations_returnsEmpty() {
        var schedule = calculator.computeCostSegregation(
                List.of(), BigDecimal.ONE, LocalDate.of(2024, 1, 1), null);

        assertThat(schedule).isEmpty();
    }
}
