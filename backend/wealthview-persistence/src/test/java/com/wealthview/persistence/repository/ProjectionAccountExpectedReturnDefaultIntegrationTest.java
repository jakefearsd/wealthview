package com.wealthview.persistence.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionAccountExpectedReturnDefaultIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestEntityManager em;

    @PersistenceContext
    private EntityManager entityManager;

    private ProjectionScenarioEntity scenario;

    @BeforeEach
    void setUp() {
        var tenant = em.persistAndFlush(new TenantEntity("Tenant A"));
        scenario = em.persistAndFlush(new ProjectionScenarioEntity(
                tenant, "Retirement Plan", LocalDate.of(2050, 1, 1), 90,
                new BigDecimal("0.03"), null));
    }

    // -------------------------------------------------------------------------
    // V070 — proves the legacy `DEFAULT 0.07` from V009 is gone. A raw SQL
    // INSERT that omits expected_return (the way JPA never would, but a
    // bulk-load or ad-hoc script might) must land NULL, not silently pick up
    // the old default and force the legacy override path.
    // -------------------------------------------------------------------------

    @Test
    void rawInsert_omittingExpectedReturn_persistsAsNull_notLegacyDefault() {
        entityManager.createNativeQuery(
                        "INSERT INTO projection_accounts (scenario_id, initial_balance, annual_contribution) "
                                + "VALUES (:scenarioId, :initialBalance, :annualContribution)")
                .setParameter("scenarioId", scenario.getId())
                .setParameter("initialBalance", new BigDecimal("100000.0000"))
                .setParameter("annualContribution", new BigDecimal("6000.0000"))
                .executeUpdate();

        var expectedReturn = entityManager.createNativeQuery(
                        "SELECT expected_return FROM projection_accounts WHERE scenario_id = :scenarioId")
                .setParameter("scenarioId", scenario.getId())
                .getSingleResult();

        assertThat(expectedReturn).isNull();
    }
}
