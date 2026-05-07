package com.wealthview.core.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Component;

/**
 * Activates the {@code tenantFilter} on the bound persistence-context Session
 * at the start of every {@code @Transactional} service method.
 *
 * <p>The aspect runs <em>inside</em> Spring's transactional advice — Hibernate
 * filters live on the {@code Session} that {@code @Transactional} opens, so we
 * need the tx-bound EntityManager (via
 * {@link EntityManagerFactoryUtils#getTransactionalEntityManager}) before we
 * can enable the filter. The transaction interceptor is configured at
 * {@code Ordered.LOWEST_PRECEDENCE - 100} (see {@code CacheConfig}), and this
 * aspect is at {@code Ordered.LOWEST_PRECEDENCE}, so the transaction wraps
 * outside us and the bound EM is non-null when we run.
 *
 * <p>Note: Hibernate filters apply to <em>queries</em> (HQL/Criteria/JPQL),
 * not to {@code EntityManager#find} primary-key loads. Layer-1 (services
 * that filter by tenantId) remains the primary defense for direct PK
 * lookups; this filter is the backstop for query-shaped IDORs.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class TenantFilterAspect {

    private final TenantFilterActivator activator;
    private final EntityManagerFactory entityManagerFactory;

    public TenantFilterAspect(TenantFilterActivator activator,
                              EntityManagerFactory entityManagerFactory) {
        this.activator = activator;
        this.entityManagerFactory = entityManagerFactory;
    }

    @Around("@annotation(org.springframework.transaction.annotation.Transactional) "
            + "|| @within(org.springframework.transaction.annotation.Transactional)")
    public Object aroundTransactional(ProceedingJoinPoint pjp) throws Throwable {
        EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        boolean weEnabledIt = em != null && activator.enableForCurrentRequest(em);
        try {
            return pjp.proceed();
        } finally {
            if (weEnabledIt) {
                try {
                    activator.disable(em);
                } catch (RuntimeException ignored) {
                    // Session may already be closed at this point; safe to ignore.
                }
            }
        }
    }
}
