package com.wealthview.core.exchangerate;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.repository.ExchangeRateRepository;

/**
 * Resolves a currency's to-USD exchange rate, cached in the
 * {@code exchangeRateConversions} cache.
 *
 * <p>This is a separate bean (rather than a method on {@link ExchangeRateService})
 * so the {@code @Cacheable} proxy is actually applied: a self-invoked
 * {@code @Cacheable} method bypasses the proxy entirely. {@code convertToUsd} is
 * called once per chart data point inside the loops of
 * {@code CombinedPortfolioHistoryService} and {@code TheoreticalPortfolioService}
 * (performance survey finding 1.1), so without caching each data point issued a
 * fresh single-row SELECT.
 *
 * <p>The cache is keyed on {@code (tenantId, currency)} and evicted by
 * {@link ExchangeRateService} whenever a rate is created, updated, or deleted, so
 * a cached rate cannot go stale.
 */
@Component
public class ExchangeRateResolver {

    private final ExchangeRateRepository exchangeRateRepository;

    public ExchangeRateResolver(ExchangeRateRepository exchangeRateRepository) {
        this.exchangeRateRepository = exchangeRateRepository;
    }

    @Cacheable(value = "exchangeRateConversions", key = "#tenantId + ':' + #currency")
    @Transactional(readOnly = true)
    public BigDecimal resolveRateToUsd(UUID tenantId, String currency) {
        return exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, currency)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No exchange rate found for " + currency
                                + " — add one in Settings before using this currency"))
                .getRateToUsd();
    }
}
