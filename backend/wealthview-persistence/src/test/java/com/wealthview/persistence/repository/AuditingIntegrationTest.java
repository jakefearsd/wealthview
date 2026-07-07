package com.wealthview.persistence.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.TenantEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JPA auditing behavior provided by {@link com.wealthview.persistence.entity.Auditable}
 * and {@link com.wealthview.persistence.entity.CreatedAtEntity}: created_at is stamped on insert
 * and frozen thereafter, and updated_at advances on every flush of a dirty row — with no service
 * ever calling a setter.
 */
class AuditingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestEntityManager em;

    @Test
    void auditable_onInsert_stampsCreatedAndUpdated() {
        var tenant = em.persistAndFlush(new TenantEntity("Auditing Tenant"));

        var account = em.persistAndFlush(new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity"));

        assertThat(account.getCreatedAt()).isNotNull();
        assertThat(account.getUpdatedAt()).isNotNull();
        assertThat(account.getUpdatedAt()).isAfterOrEqualTo(account.getCreatedAt());
    }

    @Test
    void auditable_onUpdate_advancesUpdatedAtButNotCreatedAt() throws InterruptedException {
        var tenant = em.persistAndFlush(new TenantEntity("Auditing Tenant"));
        var account = em.persistAndFlush(new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity"));
        var createdAt = account.getCreatedAt();
        var firstUpdatedAt = account.getUpdatedAt();

        // Ensure the update lands in a distinct instant even on a fast clock.
        Thread.sleep(5);
        account.setName("Renamed Brokerage");
        em.flush();
        em.clear();

        var reloaded = em.find(AccountEntity.class, account.getId());
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isAfter(firstUpdatedAt);
    }

    @Test
    void createdAtOnly_entity_stampsCreatedAtOnInsert() {
        var price = em.persistAndFlush(new PriceEntity("ZZ_AUDIT_TEST", java.time.LocalDate.of(1999, 12, 31),
                new java.math.BigDecimal("185.50"), "manual"));

        assertThat(price.getCreatedAt()).isNotNull();
    }
}
