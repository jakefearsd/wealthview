package com.wealthview.persistence.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionAccountOwnerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProjectionAccountRepository projectionAccountRepository;

    @Autowired
    private TestEntityManager em;

    private ProjectionScenarioEntity scenario;

    @BeforeEach
    void setUp() {
        var tenant = em.persistAndFlush(new TenantEntity("Tenant A"));
        scenario = em.persistAndFlush(new ProjectionScenarioEntity(
                tenant, "Retirement Plan", LocalDate.of(2050, 1, 1), 90,
                new BigDecimal("0.03"), null));
    }

    // -------------------------------------------------------------------------
    // owner text (V078) — round-trip + legacy default
    // -------------------------------------------------------------------------

    @Test
    void owner_roundTrips() {
        var entity = new ProjectionAccountEntity(scenario, null,
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(6_000), new BigDecimal("0.07"));
        entity.setOwner("spouse");

        var saved = projectionAccountRepository.saveAndFlush(entity);
        em.clear();

        var reloaded = projectionAccountRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getOwner()).isEqualTo("spouse");
    }

    @Test
    void owner_joint_roundTrips() {
        var entity = new ProjectionAccountEntity(scenario, null,
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(6_000), new BigDecimal("0.07"));
        entity.setOwner("joint");

        var saved = projectionAccountRepository.saveAndFlush(entity);
        em.clear();

        var reloaded = projectionAccountRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getOwner()).isEqualTo("joint");
    }

    @Test
    void owner_defaultsToPrimary_onLegacyInsert() {
        var entity = new ProjectionAccountEntity(scenario, null,
                BigDecimal.valueOf(50_000), BigDecimal.valueOf(3_000), new BigDecimal("0.05"));

        var saved = projectionAccountRepository.saveAndFlush(entity);
        em.clear();

        var reloaded = projectionAccountRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getOwner()).isEqualTo("primary");
    }
}
