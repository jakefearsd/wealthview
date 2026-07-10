package com.wealthview.persistence.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionAccountAllocationIntegrationTest extends AbstractIntegrationTest {

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
    // allocation jsonb + nullable expected_return — round-trip
    // -------------------------------------------------------------------------

    @Test
    void allocationJsonb_roundTrips() {
        var entity = new ProjectionAccountEntity(scenario, null,
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(6_000), new BigDecimal("0.07"));
        entity.setAllocation(Map.of("us_stock", new BigDecimal("0.6"), "bond", new BigDecimal("0.4")));
        entity.setExpectedReturn(null);

        var saved = projectionAccountRepository.saveAndFlush(entity);
        em.clear();

        var reloaded = projectionAccountRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getAllocation()).containsEntry("us_stock", new BigDecimal("0.6"));
        assertThat(reloaded.getAllocation()).containsEntry("bond", new BigDecimal("0.4"));
        assertThat(reloaded.getExpectedReturn()).isNull();
    }

    @Test
    void allocationJsonb_null_persistsAsNull() {
        var entity = new ProjectionAccountEntity(scenario, null,
                BigDecimal.valueOf(50_000), BigDecimal.valueOf(3_000), new BigDecimal("0.05"));

        var saved = projectionAccountRepository.saveAndFlush(entity);
        em.clear();

        var reloaded = projectionAccountRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getAllocation()).isNull();
        assertThat(reloaded.getExpectedReturn()).isEqualByComparingTo("0.05");
    }
}
