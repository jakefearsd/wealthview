package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IncomeContributionCalculatorTest {

    private IncomeContributionCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new IncomeContributionCalculator();
    }

    private ProjectionIncomeSourceInput source(String name, String amount, int startAge,
                                                Integer endAge, String inflationRate) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), name, "other",
                new BigDecimal(amount), startAge, endAge,
                new BigDecimal(inflationRate), false,
                "taxable",
                null, null, null, null, null);
    }

    private ProjectionIncomeSourceInput oneTimeSource(String name, String amount, int startAge) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), name, "other",
                new BigDecimal(amount), startAge, startAge + 1,
                new BigDecimal("0.02"), true,
                "taxable",
                null, null, null, null, null);
    }

    @Test
    void compute_noSources_returnsZero() {
        var result = calculator.compute(List.of(), 67, 2);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void compute_nullSources_returnsZero() {
        var result = calculator.compute(null, 67, 2);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void compute_singleSourceActive_returnsAmount() {
        var sources = List.of(source("SS", "30000", 65, null, "0"));

        var result = calculator.compute(sources, 67, 1);

        assertThat(result).isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    void compute_ageBeforeStartAge_returnsZero() {
        var sources = List.of(source("SS", "30000", 67, null, "0"));

        var result = calculator.compute(sources, 65, 1);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void compute_ageAtEndAge_returnsZero() {
        var sources = List.of(source("SS", "30000", 65, 70, "0"));

        var result = calculator.compute(sources, 70, 1);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void compute_nullEndAge_alwaysActive() {
        var sources = List.of(source("SS", "30000", 65, null, "0"));

        var result = calculator.compute(sources, 95, 1);

        assertThat(result).isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    void compute_withInflation_appliesCompounding() {
        // yearsInRetirement=4 -> 3 compounding years: 30000 * (1.02)^3
        var sources = List.of(source("SS", "30000", 65, null, "0.02"));

        var result = calculator.compute(sources, 68, 4);

        var expected = new BigDecimal("30000")
                .multiply(BigDecimal.ONE.add(new BigDecimal("0.02")).pow(3))
                .setScale(4, RoundingMode.HALF_UP);
        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void compute_firstYearRetirement_noInflation() {
        var sources = List.of(source("SS", "30000", 65, null, "0.02"));

        var result = calculator.compute(sources, 65, 1);

        assertThat(result).isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    void compute_zeroInflation_returnsNominal() {
        var sources = List.of(source("SS", "30000", 65, null, "0"));

        var result = calculator.compute(sources, 70, 5);

        assertThat(result).isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    void compute_multipleSources_sumsAll() {
        var sources = List.of(
                source("SS", "30000", 65, null, "0"),
                source("Pension", "20000", 60, null, "0"));

        var result = calculator.compute(sources, 67, 1);

        assertThat(result).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    void compute_multipleSources_differentRates_inflatesIndependently() {
        var sources = List.of(
                source("SS", "20000", 60, null, "0.02"),
                source("Rental", "10000", 60, null, "0.03"));

        var result = calculator.compute(sources, 67, 2);

        // Year 2: SS = 20000 * 1.02^1 = 20400, Rental = 10000 * 1.03^1 = 10300
        var expected = new BigDecimal("20400.0000").add(new BigDecimal("10300.0000"));
        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void compute_oneTimeSource_noInflation() {
        // One-time sources should not have inflation applied, even if inflationRate is set
        var sources = List.of(oneTimeSource("Bonus", "50000", 65));

        var result = calculator.compute(sources, 65, 5);

        assertThat(result).isEqualByComparingTo(new BigDecimal("50000"));
    }
}
