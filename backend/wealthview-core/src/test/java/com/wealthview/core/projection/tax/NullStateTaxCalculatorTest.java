package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NullStateTaxCalculatorTest {

    private final NullStateTaxCalculator calculator = new NullStateTaxCalculator();

    @Test
    void computeTax_anyIncome_returnsZero() {
        BigDecimal tax = calculator.computeTax(new BigDecimal("100000"), 2025, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getStandardDeduction_returnsZero() {
        assertThat(calculator.getStandardDeduction(2025, FilingStatus.SINGLE))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void stateCode_returnsEmpty() {
        assertThat(calculator.stateCode()).isEmpty();
    }

    @Test
    void taxesCapitalGainsAsOrdinaryIncome_returnsFalse() {
        assertThat(calculator.taxesCapitalGainsAsOrdinaryIncome()).isFalse();
    }

    @Test
    void exemptsSocialSecurity_returnsFalse() {
        // Explicit override (not the interface's default true) -- no state tax exists to exempt
        // anything from, so this null-object's flags stay uniformly "off".
        assertThat(calculator.exemptsSocialSecurity()).isFalse();
    }
}
