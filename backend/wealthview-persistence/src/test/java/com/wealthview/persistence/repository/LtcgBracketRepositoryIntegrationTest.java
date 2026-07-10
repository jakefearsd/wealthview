package com.wealthview.persistence.repository;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.LtcgBracketEntity;

import static org.assertj.core.api.Assertions.assertThat;

class LtcgBracketRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LtcgBracketRepository repository;

    @Test
    void findByTaxYearAndFilingStatusOrderByBracketFloorAsc_forSeeded2025Single_returnsThreeRowsAscending() {
        var brackets = repository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single");

        assertThat(brackets).hasSize(3);
        assertThat(brackets).extracting(LtcgBracketEntity::getRate)
                .containsExactly(new BigDecimal("0.0000"), new BigDecimal("0.1500"), new BigDecimal("0.2000"));
        assertThat(brackets).extracting(LtcgBracketEntity::getBracketFloor)
                .containsExactly(new BigDecimal("0.0000"), new BigDecimal("48350.0000"), new BigDecimal("533400.0000"));
        assertThat(brackets.get(2).getBracketCeiling()).isNull();
    }
}
