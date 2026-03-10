package com.wealthview.core.projection.tax;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SelfEmploymentTaxCalculatorTest {

    private final SelfEmploymentTaxCalculator calculator = new SelfEmploymentTaxCalculator();

    @Test
    void computeSETax_belowWageBase_fullSETax() {
        // Net earnings = $50,000
        // Taxable base = 50000 * 0.9235 = 46175
        // SE tax = 46175 * 0.153 = 7064.775
        var result = calculator.computeSETax(new BigDecimal("50000"), 2025);

        assertThat(result).isEqualByComparingTo("7064.7750");
    }

    @Test
    void computeSETax_aboveWageBase_capsSSPortion() {
        // Net earnings = $200,000
        // Taxable base = 200000 * 0.9235 = 184700
        // 2025 wage base = 176100
        // SS tax = 176100 * 0.124 = 21836.40
        // Medicare = 184700 * 0.029 = 5356.30
        // Total = 21836.40 + 5356.30 = 27192.70
        var result = calculator.computeSETax(new BigDecimal("200000"), 2025);

        assertThat(result).isEqualByComparingTo("27192.7000");
    }

    @Test
    void computeSETax_zeroEarnings_returnsZero() {
        var result = calculator.computeSETax(BigDecimal.ZERO, 2025);

        assertThat(result).isEqualByComparingTo("0");
    }

    @Test
    void computeSETax_negativeEarnings_returnsZero() {
        var result = calculator.computeSETax(new BigDecimal("-5000"), 2025);

        assertThat(result).isEqualByComparingTo("0");
    }

    @Test
    void deductibleAmount_isHalfOfSETax() {
        var seTax = calculator.computeSETax(new BigDecimal("50000"), 2025);
        var deductible = calculator.deductibleAmount(seTax);

        assertThat(deductible).isEqualByComparingTo("3532.3875");
    }

    @Test
    void computeSETax_smallAmount_correctCalculation() {
        // Net earnings = $1,000
        // Taxable base = 1000 * 0.9235 = 923.50
        // SE tax = 923.50 * 0.153 = 141.2955
        var result = calculator.computeSETax(new BigDecimal("1000"), 2025);

        assertThat(result).isEqualByComparingTo("141.2955");
    }
}
