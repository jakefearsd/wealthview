package com.wealthview.core.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantFilterAspectTest {

    @Mock
    private TenantFilterActivator activator;

    @Mock
    private EntityManagerFactory entityManagerFactory;

    @Mock
    private ProceedingJoinPoint joinPoint;

    private TenantFilterAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new TenantFilterAspect(activator, entityManagerFactory);
    }

    @AfterEach
    void unbind() {
        if (TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
        }
    }

    private EntityManager bindEntityManager() {
        var em = org.mockito.Mockito.mock(EntityManager.class);
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(em));
        return em;
    }

    @Test
    void aroundTransactional_whenAspectEnabledFilter_disablesItAfterwards() throws Throwable {
        var em = bindEntityManager();
        when(activator.enableForCurrentRequest(em)).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        var result = aspect.aroundTransactional(joinPoint);

        assertThat(result).isEqualTo("ok");
        verify(activator).enableForCurrentRequest(em);
        // We enabled it, so we must disable it on the way out.
        verify(activator).disable(em);
    }

    @Test
    void aroundTransactional_whenFilterAlreadyEnabled_doesNotDisableIt() throws Throwable {
        // enableForCurrentRequest returning false means a nested call already
        // owns the filter; this aspect invocation must NOT disable it.
        var em = bindEntityManager();
        when(activator.enableForCurrentRequest(em)).thenReturn(false);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.aroundTransactional(joinPoint);

        verify(activator, never()).disable(em);
    }

    @Test
    void aroundTransactional_whenProceedThrows_stillDisablesFilter() throws Throwable {
        var em = bindEntityManager();
        when(activator.enableForCurrentRequest(em)).thenReturn(true);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.aroundTransactional(joinPoint))
                .isInstanceOf(IllegalStateException.class);

        verify(activator).disable(em);
    }

    @Test
    void aroundTransactional_withNoBoundEntityManager_skipsActivatorEntirely() throws Throwable {
        // No transactional EM bound (e.g. a @Scheduled job): the aspect must
        // not call the activator at all.
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.aroundTransactional(joinPoint);

        verify(activator, never()).enableForCurrentRequest(org.mockito.ArgumentMatchers.any());
    }
}
