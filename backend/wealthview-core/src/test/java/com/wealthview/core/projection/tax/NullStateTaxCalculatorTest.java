package com.wealthview.core.projection.tax;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
}
