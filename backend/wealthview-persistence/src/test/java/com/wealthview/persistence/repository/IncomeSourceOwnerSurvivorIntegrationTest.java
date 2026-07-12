package com.wealthview.persistence.repository;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.TenantEntity;

import static org.assertj.core.api.Assertions.assertThat;

class IncomeSourceOwnerSurvivorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IncomeSourceRepository incomeSourceRepository;

    @Autowired
    private TestEntityManager em;

    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenant = em.persistAndFlush(new TenantEntity("Tenant A"));
    }

    // -------------------------------------------------------------------------
    // owner text + survivor_percent numeric(5,4) (V079) — round-trip + legacy defaults
    // -------------------------------------------------------------------------

    @Test
    void ownerAndSurvivorPercent_roundTrip() {
        var entity = new IncomeSourceEntity(tenant, "Spouse Pension", "pension",
                new BigDecimal("24000.0000"), 65, null,
                BigDecimal.ZERO, false, "taxable");
        entity.setOwner("spouse");
        entity.setSurvivorPercent(new BigDecimal("0.5000"));

        var saved = incomeSourceRepository.saveAndFlush(entity);
        em.clear();

        var reloaded = incomeSourceRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getOwner()).isEqualTo("spouse");
        assertThat(reloaded.getSurvivorPercent()).isEqualByComparingTo("0.5000");
    }

    @Test
    void ownerAndSurvivorPercent_defaultToPrimaryAndOne_onLegacyInsert() {
        var entity = new IncomeSourceEntity(tenant, "Social Security", "social_security",
                new BigDecimal("30000.0000"), 67, null,
                BigDecimal.ZERO, false, "taxable");

        var saved = incomeSourceRepository.saveAndFlush(entity);
        em.clear();

        var reloaded = incomeSourceRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getOwner()).isEqualTo("primary");
        assertThat(reloaded.getSurvivorPercent()).isEqualByComparingTo("1.0000");
    }

    @Test
    void survivorPercent_zero_roundTrips() {
        var entity = new IncomeSourceEntity(tenant, "Part-time work", "part_time_work",
                new BigDecimal("12000.0000"), 62, 67,
                BigDecimal.ZERO, false, "taxable");
        entity.setOwner("primary");
        entity.setSurvivorPercent(BigDecimal.ZERO);

        var saved = incomeSourceRepository.saveAndFlush(entity);
        em.clear();

        var reloaded = incomeSourceRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getSurvivorPercent()).isEqualByComparingTo("0.0000");
    }
}
