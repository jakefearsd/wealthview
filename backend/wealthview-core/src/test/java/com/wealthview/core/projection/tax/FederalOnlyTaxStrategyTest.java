package com.wealthview.core.projection.tax;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FederalOnlyTaxStrategyTest {

    @Mock
    private FederalTaxCalculator federalTaxCalculator;

    private FederalOnlyTaxStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new FederalOnlyTaxStrategy(federalTaxCalculator);
    }

    @Test
    void computeTotalTax_withPositiveIncome_delegatesToFederalTaxCalculator() {
        var grossIncome = new BigDecimal("75000");
        var expectedTax = new BigDecimal("8760.5000");
        when(federalTaxCalculator.computeTax(grossIncome, 2025, FilingStatus.SINGLE))
                .thenReturn(expectedTax);

        var result = strategy.computeTotalTax(grossIncome, 2025, FilingStatus.SINGLE);

        assertThat(result).isEqualByComparingTo(expectedTax);
        verify(federalTaxCalculator).computeTax(grossIncome, 2025, FilingStatus.SINGLE);
    }

    @Test
    void computeTotalTax_withZeroIncome_returnsZero() {
        when(federalTaxCalculator.computeTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE))
                .thenReturn(BigDecimal.ZERO);

        var result = strategy.computeTotalTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeMaxIncomeForTargetRate_delegatesToFederalTaxCalculator() {
        var targetRate = new BigDecimal("0.1200");
        var expectedCeiling = new BigDecimal("63475");
        when(federalTaxCalculator.computeMaxIncomeForBracket(targetRate, 2025, FilingStatus.SINGLE))
                .thenReturn(expectedCeiling);

        var result = strategy.computeMaxIncomeForTargetRate(targetRate, 2025, FilingStatus.SINGLE);

        assertThat(result).isEqualByComparingTo(expectedCeiling);
        verify(federalTaxCalculator).computeMaxIncomeForBracket(targetRate, 2025, FilingStatus.SINGLE);
    }

    @Test
    void computeDetailedTax_withPositiveIncome_returnsResultWithZeroStateTax() {
        var grossIncome = new BigDecimal("100000");
        var federalTax = new BigDecimal("12345.6789");
        when(federalTaxCalculator.computeTax(grossIncome, 2025, FilingStatus.MARRIED_FILING_JOINTLY))
                .thenReturn(federalTax);

        var result = strategy.computeDetailedTax(grossIncome, 2025, FilingStatus.MARRIED_FILING_JOINTLY);

        assertThat(result.federalTax()).isEqualByComparingTo(federalTax);
        assertThat(result.stateTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalTax()).isEqualByComparingTo(federalTax);
        assertThat(result.saltDeduction()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.itemizedDeductions()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeDetailedTax_withPositiveIncome_returnsFalseForUsedItemized() {
        var grossIncome = new BigDecimal("150000");
        var federalTax = new BigDecimal("20000.0000");
        when(federalTaxCalculator.computeTax(grossIncome, 2025, FilingStatus.SINGLE))
                .thenReturn(federalTax);

        var result = strategy.computeDetailedTax(grossIncome, 2025, FilingStatus.SINGLE);

        assertThat(result.usedItemized()).isFalse();
    }

    @Test
    void computeDetailedTax_withZeroIncome_returnsZeroFederalTax() {
        when(federalTaxCalculator.computeTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE))
                .thenReturn(BigDecimal.ZERO);

        var result = strategy.computeDetailedTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE);

        assertThat(result.federalTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.stateTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.usedItemized()).isFalse();
    }
}
