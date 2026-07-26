package com.wealthview.core.price;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.wealthview.core.config.CacheConfig;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.repository.PriceRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link LatestPriceLookup#latestFor} is actually served from the
 * {@code latestPrices} cache (not just decorated with an inert annotation) and that the cache key
 * is canonical: the same set of symbols in a different order must hit the same entry. Exercised
 * through a real Spring context so the {@code @Cacheable} proxy is active — mirrors
 * {@code ExchangeRateConversionCacheTest}, the precedent for this shape.
 */
class LatestPriceCacheTest {

    private AnnotationConfigApplicationContext context;
    private PriceRepository priceRepository;
    private LatestPriceLookup lookup;

    @BeforeEach
    void setUp() {
        priceRepository = mock(PriceRepository.class);

        context = new AnnotationConfigApplicationContext();
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean(PlatformTransactionManager.class, NoOpTransactionManager::new);
        context.registerBean(PriceRepository.class, () -> priceRepository);
        context.register(CacheConfig.class);
        context.register(LatestPriceLookup.class);
        context.refresh();

        lookup = context.getBean(LatestPriceLookup.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void latestFor_repeatedSameSymbolSet_hitsRepositoryOnce() {
        var aapl = new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual");
        when(priceRepository.findLatestBySymbolIn(List.of("AAPL")))
                .thenReturn(List.of(aapl));

        var first = lookup.latestFor(List.of("AAPL"));
        var second = lookup.latestFor(List.of("AAPL"));

        assertThat(first).containsEntry("AAPL", new BigDecimal("200.00"));
        assertThat(second).containsEntry("AAPL", new BigDecimal("200.00"));
        verify(priceRepository, times(1)).findLatestBySymbolIn(List.of("AAPL"));
    }

    @Test
    void latestFor_sameSymbolsDifferentOrder_hitsSameCacheEntry() {
        // Canonical key: {AAPL, GOOG} and {GOOG, AAPL} must resolve to the same cache slot.
        var aapl = new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual");
        var goog = new PriceEntity("GOOG", LocalDate.of(2025, 3, 1), new BigDecimal("150.00"), "manual");
        when(priceRepository.findLatestBySymbolIn(List.of("AAPL", "GOOG")))
                .thenReturn(List.of(aapl, goog));

        lookup.latestFor(List.of("AAPL", "GOOG"));
        lookup.latestFor(List.of("GOOG", "AAPL"));

        verify(priceRepository, times(1)).findLatestBySymbolIn(List.of("AAPL", "GOOG"));
    }

    @Test
    void latestFor_differentSymbolSet_triggersFreshLookup() {
        var aapl = new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual");
        var msft = new PriceEntity("MSFT", LocalDate.of(2025, 3, 1), new BigDecimal("400.00"), "manual");
        when(priceRepository.findLatestBySymbolIn(List.of("AAPL")))
                .thenReturn(List.of(aapl));
        when(priceRepository.findLatestBySymbolIn(List.of("MSFT")))
                .thenReturn(List.of(msft));

        lookup.latestFor(List.of("AAPL"));
        lookup.latestFor(List.of("MSFT"));

        verify(priceRepository, times(1)).findLatestBySymbolIn(List.of("AAPL"));
        verify(priceRepository, times(1)).findLatestBySymbolIn(List.of("MSFT"));
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
