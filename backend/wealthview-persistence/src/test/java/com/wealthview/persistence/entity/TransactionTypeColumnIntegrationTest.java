package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.wealthview.persistence.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that typing {@code TransactionEntity.type} as {@link TransactionType} did not change
 * a single byte in the {@code transactions.type} text column, which is what makes the change
 * migration-free and keeps the V011 {@code transactions_type_check} CHECK constraint satisfied.
 * Reads and writes the column with raw SQL so the assertion cannot be satisfied by the
 * converter agreeing with itself.
 */
class TransactionTypeColumnIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestEntityManager em;

    @PersistenceContext
    private EntityManager entityManager;

    private TenantEntity tenant;
    private AccountEntity account;

    @BeforeEach
    void setUp() {
        tenant = em.persistAndFlush(new TenantEntity("Tenant A"));
        account = em.persistAndFlush(new AccountEntity(tenant, "Portfolio", "brokerage", "Fidelity"));
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void persist_everyType_writesLowercaseWireValueToTextColumn(TransactionType type) {
        var saved = em.persistAndFlush(new TransactionEntity(account, tenant, LocalDate.of(2024, 6, 1),
                type, "VTI", new BigDecimal("1.0000"), new BigDecimal("1000.0000")));

        var raw = entityManager.createNativeQuery("SELECT type FROM transactions WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(raw).isEqualTo(type.value());
    }

    @Test
    void read_rawColumnValue_mapsBackToEnumConstant() {
        entityManager.createNativeQuery("""
                        INSERT INTO transactions (account_id, tenant_id, date, type, symbol, amount)
                        VALUES (:accountId, :tenantId, :date, 'opening_balance', 'VTI', :amount)
                        """)
                .setParameter("accountId", account.getId())
                .setParameter("tenantId", tenant.getId())
                .setParameter("date", LocalDate.of(2024, 6, 1))
                .setParameter("amount", new BigDecimal("1000.0000"))
                .executeUpdate();
        entityManager.clear();

        var loaded = entityManager.createQuery(
                "SELECT t FROM TransactionEntity t WHERE t.symbol = 'VTI'", TransactionEntity.class)
                .getSingleResult();

        assertThat(loaded.getType()).isSameAs(TransactionType.OPENING_BALANCE);
    }
}
