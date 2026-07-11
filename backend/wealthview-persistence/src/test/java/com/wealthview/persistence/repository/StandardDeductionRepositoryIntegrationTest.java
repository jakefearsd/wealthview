package com.wealthview.persistence.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the real {@code R__seed_standard_deductions.sql} data against a live PostgreSQL
 * instance (via {@link AbstractIntegrationTest}'s Testcontainers) -- this is the only place that
 * would catch a typo in the seed SQL itself; every other test in the suite exercises
 * {@code FederalTaxCalculator} against Mockito-mocked repositories.
 */
class StandardDeductionRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StandardDeductionRepository repository;

    @Test
    void findByTaxYearAndFilingStatus_2025Single_returnsPostObbbaAmounts() {
        var deduction = repository.findByTaxYearAndFilingStatus(2025, "single").orElseThrow();

        // Post-OBBBA (One Big Beautiful Bill Act, H.R. 1, 2025) base standard deduction, plus the
        // IRS age-65+ additional amount (Rev. Proc. 2024-40).
        assertThat(deduction.getAmount()).isEqualByComparingTo("15750.0000");
        assertThat(deduction.getAdditionalAge65()).isEqualByComparingTo("2000.0000");
    }

    @Test
    void findByTaxYearAndFilingStatus_2025Mfj_returnsPostObbbaAmounts() {
        var deduction = repository.findByTaxYearAndFilingStatus(2025, "married_filing_jointly").orElseThrow();

        assertThat(deduction.getAmount()).isEqualByComparingTo("31500.0000");
        assertThat(deduction.getAdditionalAge65()).isEqualByComparingTo("1600.0000");
    }

    @Test
    void findByTaxYearAndFilingStatus_2022Single_returnsSeededAdditionalAge65Amount() {
        // Pre-OBBBA years are untouched by the base-amount fix, but now also carry the age-65
        // addition seeded alongside them.
        var deduction = repository.findByTaxYearAndFilingStatus(2022, "single").orElseThrow();

        assertThat(deduction.getAmount()).isEqualByComparingTo("12950.0000");
        assertThat(deduction.getAdditionalAge65()).isEqualByComparingTo("1750.0000");
    }

    @Test
    void findMaxTaxYear_returns2025() {
        assertThat(repository.findMaxTaxYear()).isEqualTo(2025);
    }
}
