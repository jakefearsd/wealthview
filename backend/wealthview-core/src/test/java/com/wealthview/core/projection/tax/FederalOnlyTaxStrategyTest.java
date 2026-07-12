package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.projection.household.HouseholdContext;

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

    // === Household task 7: household-aware age threading (spec §4 step 6) ===

    @Test
    void computeTotalTax_householdBothAliveMfj_passesBothFilerAges() {
        var household = HouseholdContext.of(1958, 85, 1966, 90, 2065);
        var householdStrategy = new FederalOnlyTaxStrategy(federalTaxCalculator, null, household);
        var grossIncome = new BigDecimal("100000");
        when(federalTaxCalculator.computeTax(grossIncome, 2042, FilingStatus.MARRIED_FILING_JOINTLY, 84, 76))
                .thenReturn(new BigDecimal("9000"));

        var result = householdStrategy.computeTotalTax(grossIncome, 2042, FilingStatus.MARRIED_FILING_JOINTLY);

        assertThat(result).isEqualByComparingTo(bd("9000"));
        verify(federalTaxCalculator)
                .computeTax(grossIncome, 2042, FilingStatus.MARRIED_FILING_JOINTLY, 84, 76);
    }

    @Test
    void computeTotalTax_householdFilingSingleWhileBothAlive_noSecondAgeEvenThoughSpouseExists() {
        var household = HouseholdContext.of(1958, 85, 1966, 90, 2065);
        var householdStrategy = new FederalOnlyTaxStrategy(federalTaxCalculator, null, household);
        var grossIncome = new BigDecimal("100000");
        when(federalTaxCalculator.computeTax(grossIncome, 2042, FilingStatus.SINGLE, 84, null))
                .thenReturn(new BigDecimal("15000"));

        householdStrategy.computeTotalTax(grossIncome, 2042, FilingStatus.SINGLE);

        verify(federalTaxCalculator).computeTax(grossIncome, 2042, FilingStatus.SINGLE, 84, null);
    }

    @Test
    void computeTotalTax_householdPostTransitionSurvivorSpouse_usesSurvivorAgeNotDeceasedPrimaryAge() {
        // Primary (born 1958) dies at 85 in 2043; the survivor (spouse, born 1966) is 79 in 2045.
        var household = HouseholdContext.of(1958, 85, 1966, 90, 2065);
        var householdStrategy = new FederalOnlyTaxStrategy(federalTaxCalculator, null, household);
        var grossIncome = new BigDecimal("60000");
        when(federalTaxCalculator.computeTax(grossIncome, 2045, FilingStatus.SINGLE, 79, null))
                .thenReturn(new BigDecimal("7000"));

        householdStrategy.computeTotalTax(grossIncome, 2045, FilingStatus.SINGLE);

        verify(federalTaxCalculator).computeTax(grossIncome, 2045, FilingStatus.SINGLE, 79, null);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
