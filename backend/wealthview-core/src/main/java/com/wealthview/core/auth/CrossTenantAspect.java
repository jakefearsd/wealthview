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
 * Disables the {@code tenantFilter} for the duration of a method annotated
 * {@link CrossTenant}, then re-enables it on return (including on exception).
 *
 * <p>This aspect is ordered {@code LOWEST_PRECEDENCE} (same as
 * {@link TenantFilterAspect}) but lives in its own class so its precedence
 * relative to {@code TenantFilterAspect} is unambiguous: we declare it
 * <em>after</em> the tenant-filter aspect so that, by Spring's convention
 * for equal-precedence aspects, this one runs <em>inside</em> the tenant
 * filter aspect — i.e., the filter has already been enabled by
 * {@link TenantFilterAspect} when we run, and we cleanly disable it.
 *
 * <p>The split into two classes is required: with both advices in the same
 * aspect class, AspectJ orders them by method name, which produced the
 * wrong nesting (cross-tenant disable wrapping outside the tx, where the
 * EntityManager isn't bound yet).
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class CrossTenantAspect {

    private final TenantFilterActivator activator;
    private final EntityManagerFactory entityManagerFactory;

    public CrossTenantAspect(TenantFilterActivator activator,
                             EntityManagerFactory entityManagerFactory) {
        this.activator = activator;
        this.entityManagerFactory = entityManagerFactory;
    }

    @Around("@annotation(com.wealthview.core.auth.CrossTenant)")
    public Object aroundCrossTenant(ProceedingJoinPoint pjp) throws Throwable {
        EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        boolean wasEnabled = em != null && activator.disable(em);
        try {
            return pjp.proceed();
        } finally {
            if (wasEnabled) {
                try {
                    activator.reEnable(em);
                } catch (RuntimeException ignored) {
                    // Session may already be closed at this point; safe to ignore.
                }
            }
        }
    }
}
