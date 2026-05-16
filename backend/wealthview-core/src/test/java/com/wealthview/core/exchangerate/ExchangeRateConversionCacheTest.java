package com.wealthview.core.exchangerate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.wealthview.core.config.CacheConfig;
import com.wealthview.persistence.entity.ExchangeRateEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.ExchangeRateRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@code ExchangeRateService.convertToUsd} resolves its rate via
 * the {@code exchangeRateConversions} cache through {@link ExchangeRateResolver}:
 * a repeated call for the same (tenant, currency) key must not hit the
 * repository again, while a different key still triggers a fresh lookup.
 * Exercised through a real Spring context so the {@code @Cacheable} proxy is
 * active (performance survey finding 1.1).
 */
class ExchangeRateConversionCacheTest {

    private AnnotationConfigApplicationContext context;
    private ExchangeRateRepository exchangeRateRepository;
    private ExchangeRateResolver resolver;

    @BeforeEach
    void setUp() {
        exchangeRateRepository = mock(ExchangeRateRepository.class);

        context = new AnnotationConfigApplicationContext();
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean(PlatformTransactionManager.class, NoOpTransactionManager::new);
        context.registerBean(ExchangeRateRepository.class, () -> exchangeRateRepository);
        context.register(CacheConfig.class);
        context.register(ExchangeRateResolver.class);
        context.refresh();

        resolver = context.getBean(ExchangeRateResolver.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void resolveRateToUsd_repeatedSameKey_hitsRepositoryOnce() {
        var tenantId = UUID.randomUUID();
        var tenant = new TenantEntity("Test");
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"))));

        var first = resolver.resolveRateToUsd(tenantId, "EUR");
        var second = resolver.resolveRateToUsd(tenantId, "EUR");

        assertThat(first).isEqualByComparingTo("1.08");
        assertThat(second).isEqualByComparingTo("1.08");
        verify(exchangeRateRepository, times(1))
                .findByTenant_IdAndCurrencyCode(eq(tenantId), eq("EUR"));
    }

    @Test
    void resolveRateToUsd_differentCurrencyKey_triggersFreshLookup() {
        var tenantId = UUID.randomUUID();
        var tenant = new TenantEntity("Test");
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"))));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "GBP"))
                .thenReturn(Optional.of(new ExchangeRateEntity(tenant, "GBP", new BigDecimal("1.27"))));

        var eur = resolver.resolveRateToUsd(tenantId, "EUR");
        var gbp = resolver.resolveRateToUsd(tenantId, "GBP");

        assertThat(eur).isEqualByComparingTo("1.08");
        assertThat(gbp).isEqualByComparingTo("1.27");
        verify(exchangeRateRepository, times(1)).findByTenant_IdAndCurrencyCode(eq(tenantId), eq("EUR"));
        verify(exchangeRateRepository, times(1)).findByTenant_IdAndCurrencyCode(eq(tenantId), eq("GBP"));
    }

    @Test
    void resolveRateToUsd_differentTenantSameCurrency_triggersFreshLookup() {
        var tenantA = UUID.randomUUID();
        var tenantB = UUID.randomUUID();
        var tenant = new TenantEntity("Test");
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantA, "EUR"))
                .thenReturn(Optional.of(new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"))));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantB, "EUR"))
                .thenReturn(Optional.of(new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.10"))));

        assertThat(resolver.resolveRateToUsd(tenantA, "EUR")).isEqualByComparingTo("1.08");
        assertThat(resolver.resolveRateToUsd(tenantB, "EUR")).isEqualByComparingTo("1.10");

        verify(exchangeRateRepository, times(1)).findByTenant_IdAndCurrencyCode(eq(tenantA), eq("EUR"));
        verify(exchangeRateRepository, times(1)).findByTenant_IdAndCurrencyCode(eq(tenantB), eq("EUR"));
    }

    /** Minimal no-op transaction manager so {@code @Transactional} proxies resolve. */
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            // no-op
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no-op
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no-op
        }
    }
}
