package com.wealthview.app.it.isolation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.auth.CrossTenant;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.repository.AccountRepository;

/**
 * Test-only service used by tenant-filter ITs to prove the Hibernate filter
 * masks rows owned by other tenants <em>even when the repository call has no
 * tenantId predicate</em> — this is the IDOR backstop.
 *
 * <p>{@link #findByIdIgnoringTenant(UUID)} mimics a developer-written
 * {@code findById} that forgot to scope by tenantId. With the filter active,
 * the call must return {@link Optional#empty()} for any account that does
 * not belong to the authenticated tenant.
 *
 * <p>{@link #findByIdCrossTenant(UUID)} runs the same query but is marked
 * {@link CrossTenant}, proving that the bypass aspect disables the filter
 * for legitimate cross-tenant code paths.
 *
 * <p>Wired by {@link TenantFilterBackstopProbeConfig} as a Spring bean so
 * that AOP advice (transactional + tenant-filter aspect) applies normally.
 */
public class TenantFilterBackstopProbe {

    private final AccountRepository accountRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public TenantFilterBackstopProbe(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Mimics a query-style lookup with no tenantId predicate — exactly the
     * shape of every {@code findAll} / {@code findBy*} repository finder
     * that an inattentive developer might add. Hibernate filters apply to
     * <em>queries</em>, so this is the surface area the filter protects.
     */
    @Transactional(readOnly = true)
    public Optional<AccountEntity> findByIdAsQueryIgnoringTenant(UUID id) {
        var results = entityManager.createQuery(
                        "select a from AccountEntity a where a.id = :id", AccountEntity.class)
                .setParameter("id", id)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** Same pattern as the JPA query above but using Spring Data's findAll (which is also a query). */
    @Transactional(readOnly = true)
    public List<AccountEntity> findAllIgnoringTenant() {
        return accountRepository.findAll();
    }

    /**
     * The {@link CrossTenant} bypass should disable the filter for the duration
     * of this method. Same JPQL shape as the buggy probe so the comparison is
     * apples-to-apples.
     */
    @CrossTenant
    @Transactional(readOnly = true)
    public Optional<AccountEntity> findByIdAsQueryCrossTenant(UUID id) {
        var results = entityManager.createQuery(
                        "select a from AccountEntity a where a.id = :id", AccountEntity.class)
                .setParameter("id", id)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
