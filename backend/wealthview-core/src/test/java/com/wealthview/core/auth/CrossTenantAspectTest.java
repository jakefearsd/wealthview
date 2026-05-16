package com.wealthview.core.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrossTenantAspectTest {

    @Mock
    private TenantFilterActivator activator;

    @Mock
    private EntityManagerFactory entityManagerFactory;

    @Mock
    private ProceedingJoinPoint joinPoint;

    private CrossTenantAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new CrossTenantAspect(activator, entityManagerFactory);
    }

    @Test
    void aroundCrossTenant_whenFilterWasEnabled_disablesThenReEnablesAndReturnsResult() throws Throwable {
        // Bind a transactional EntityManager so the aspect actually runs.
        var em = bindEntityManager();
        when(activator.disable(em)).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("result");

        var result = aspect.aroundCrossTenant(joinPoint);

        assertThat(result).isEqualTo("result");
        verify(activator).disable(em);
        // Because the filter WAS enabled, it must be restored afterwards.
        verify(activator).reEnable(em);
    }

    @Test
    void aroundCrossTenant_whenFilterWasNotEnabled_doesNotReEnable() throws Throwable {
        var em = bindEntityManager();
        when(activator.disable(em)).thenReturn(false);
        when(joinPoint.proceed()).thenReturn("result");

        aspect.aroundCrossTenant(joinPoint);

        // Filter was already disabled; the aspect must NOT spuriously re-enable.
        verify(activator, never()).reEnable(em);
    }

    @Test
    void aroundCrossTenant_whenProceedThrows_stillReEnablesFilter() throws Throwable {
        var em = bindEntityManager();
        when(activator.disable(em)).thenReturn(true);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.aroundCrossTenant(joinPoint))
                .isInstanceOf(IllegalStateException.class);

        // finally-block must restore the filter even on exception.
        verify(activator).reEnable(em);
    }

    private EntityManager bindEntityManager() {
        // getTransactionalEntityManager returns null unless an EM is bound to
        // the thread; bind one for the test, unbind happens via JVM teardown.
        var em = org.mockito.Mockito.mock(EntityManager.class);
        var emHolder = new org.springframework.orm.jpa.EntityManagerHolder(em);
        org.springframework.transaction.support.TransactionSynchronizationManager
                .bindResource(entityManagerFactory, emHolder);
        return em;
    }

    @org.junit.jupiter.api.AfterEach
    void unbind() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .hasResource(entityManagerFactory)) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .unbindResource(entityManagerFactory);
        }
    }
}
