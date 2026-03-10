package com.wealthview.core.projection.tax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SocialSecurityTaxCalculatorTest {

    private final SocialSecurityTaxCalculator calculator = new SocialSecurityTaxCalculator();

    // --- Single filer tests ---

    @Test
    void single_belowFirstThreshold_zeroTaxable() {
        // Other income = $20k, SS = $10k
        // Provisional = 20000 + 5000 = 25000 (exactly at threshold, not over)
        var result = calculator.computeTaxableAmount(
                new BigDecimal("10000"), new BigDecimal("20000"), "single");

        assertThat(result).isEqualByComparingTo("0");
    }

    @Test
    void single_betweenThresholds_50PercentTaxable() {
        // Other income = $28k, SS = $20k
        // Provisional = 28000 + 10000 = 38000 (> 25000, < 34000? No, > 34000)
        // Actually provisional = 38000 > 34000 → 85% tier
        // Let me pick better numbers.
        // Other income = $22k, SS = $12k
        // Provisional = 22000 + 6000 = 28000 → between 25000 and 34000
        var result = calculator.computeTaxableAmount(
                new BigDecimal("12000"), new BigDecimal("22000"), "single");

        // Taxable = min(50% of SS, 50% of (provisional - 25000))
        // = min(6000, 50% of 3000) = min(6000, 1500) = 1500
        assertThat(result).isEqualByComparingTo("1500");
    }

    @Test
    void single_aboveSecondThreshold_85PercentTaxable() {
        // Other income = $50k, SS = $30k
        // Provisional = 50000 + 15000 = 65000 → well above 34000
        var result = calculator.computeTaxableAmount(
                new BigDecimal("30000"), new BigDecimal("50000"), "single");

        // Tier 1 amount: 50% of (34000 - 25000) = 50% of 9000 = 4500
        // Tier 2 amount: 85% of (65000 - 34000) = 85% of 31000 = 26350
        // Total = 4500 + 26350 = 30850
        // Cap: 85% of 30000 = 25500
        // Taxable = min(30850, 25500) = 25500
        assertThat(result).isEqualByComparingTo("25500");
    }

    @Test
    void single_lowIncome_noTax() {
        // Other income = $10k, SS = $15k
        // Provisional = 10000 + 7500 = 17500 → below 25000
        var result = calculator.computeTaxableAmount(
                new BigDecimal("15000"), new BigDecimal("10000"), "single");

        assertThat(result).isEqualByComparingTo("0");
    }

    // --- Married filing jointly tests ---

    @Test
    void mfj_belowFirstThreshold_zeroTaxable() {
        // Other income = $25k, SS = $14k
        // Provisional = 25000 + 7000 = 32000 (exactly at MFJ threshold, not over)
        var result = calculator.computeTaxableAmount(
                new BigDecimal("14000"), new BigDecimal("25000"), "married_filing_jointly");

        assertThat(result).isEqualByComparingTo("0");
    }

    @Test
    void mfj_betweenThresholds_50PercentTaxable() {
        // Other income = $30k, SS = $20k
        // Provisional = 30000 + 10000 = 40000 → between 32000 and 44000
        var result = calculator.computeTaxableAmount(
                new BigDecimal("20000"), new BigDecimal("30000"), "married_filing_jointly");

        // Taxable = min(50% of SS, 50% of (provisional - 32000))
        // = min(10000, 50% of 8000) = min(10000, 4000) = 4000
        assertThat(result).isEqualByComparingTo("4000");
    }

    @Test
    void mfj_aboveSecondThreshold_85PercentTaxable() {
        // Other income = $60k, SS = $36k
        // Provisional = 60000 + 18000 = 78000 → well above 44000
        var result = calculator.computeTaxableAmount(
                new BigDecimal("36000"), new BigDecimal("60000"), "married_filing_jointly");

        // Tier 1: 50% of (44000 - 32000) = 50% of 12000 = 6000
        // Tier 2: 85% of (78000 - 44000) = 85% of 34000 = 28900
        // Total = 6000 + 28900 = 34900
        // Cap: 85% of 36000 = 30600
        // Taxable = min(34900, 30600) = 30600
        assertThat(result).isEqualByComparingTo("30600");
    }

    @Test
    void zeroSocialSecurity_returnsZero() {
        var result = calculator.computeTaxableAmount(
                BigDecimal.ZERO, new BigDecimal("50000"), "single");

        assertThat(result).isEqualByComparingTo("0");
    }

    @Test
    void single_justAboveFirstThreshold_smallTaxableAmount() {
        // Other income = $24k, SS = $4k
        // Provisional = 24000 + 2000 = 26000 → just above 25000
        var result = calculator.computeTaxableAmount(
                new BigDecimal("4000"), new BigDecimal("24000"), "single");

        // Taxable = min(50% of 4000, 50% of (26000 - 25000))
        // = min(2000, 500) = 500
        assertThat(result).isEqualByComparingTo("500");
    }
}
