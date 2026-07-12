package com.wealthview.persistence.repository;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.IrmaaTierEntity;

import static org.assertj.core.api.Assertions.assertThat;

class IrmaaTierRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IrmaaTierRepository repository;

    @Test
    void findByTaxYearAndFilingStatusOrderByMagiFloorAsc_forSeeded2025Single_returnsSixRowsAscending() {
        var tiers = repository.findByTaxYearAndFilingStatusOrderByMagiFloorAsc(2025, "single");

        assertThat(tiers).hasSize(6);
        assertThat(tiers).extracting(IrmaaTierEntity::getMagiFloor)
                .containsExactly(
                        new BigDecimal("0.0000"), new BigDecimal("106000.0000"), new BigDecimal("133000.0000"),
                        new BigDecimal("167000.0000"), new BigDecimal("200000.0000"), new BigDecimal("500000.0000"));
        assertThat(tiers.getFirst().getPartBSurcharge()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(tiers.getFirst().getPartDSurcharge()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(tiers.getLast().getMagiCeiling()).isNull();
        assertThat(tiers.getLast().getPartBSurcharge()).isEqualByComparingTo(new BigDecimal("443.90"));
        assertThat(tiers.getLast().getPartDSurcharge()).isEqualByComparingTo(new BigDecimal("85.80"));
    }

    @Test
    void findByTaxYearAndFilingStatusOrderByMagiFloorAsc_forSeeded2025MarriedFilingJointly_returnsSixRowsAscending() {
        var tiers = repository.findByTaxYearAndFilingStatusOrderByMagiFloorAsc(2025, "married_filing_jointly");

        assertThat(tiers).hasSize(6);
        assertThat(tiers).extracting(IrmaaTierEntity::getMagiFloor)
                .containsExactly(
                        new BigDecimal("0.0000"), new BigDecimal("212000.0000"), new BigDecimal("266000.0000"),
                        new BigDecimal("334000.0000"), new BigDecimal("400000.0000"), new BigDecimal("750000.0000"));
        // Surcharge dollar amounts are the SAME as single filers -- only the MAGI breakpoints differ.
        assertThat(tiers).extracting(IrmaaTierEntity::getPartBSurcharge)
                .containsExactly(
                        new BigDecimal("0.0000"), new BigDecimal("74.0000"), new BigDecimal("185.0000"),
                        new BigDecimal("295.9000"), new BigDecimal("406.9000"), new BigDecimal("443.9000"));
    }

    @Test
    void findByTaxYearAndFilingStatusOrderByMagiFloorAsc_unseededYear_returnsEmpty() {
        var tiers = repository.findByTaxYearAndFilingStatusOrderByMagiFloorAsc(1999, "single");

        assertThat(tiers).isEmpty();
    }

    @Test
    void findMaxTaxYear_returnsLatestSeededYear() {
        assertThat(repository.findMaxTaxYear()).isEqualTo(2025);
    }
}
