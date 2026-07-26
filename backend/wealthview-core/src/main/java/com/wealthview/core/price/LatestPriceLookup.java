package com.wealthview.core.price;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.repository.PriceRepository;

/**
 * Resolves the latest close price for a set of symbols, cached in the {@code latestPrices} cache.
 *
 * <p>This is a separate bean (rather than a private method on each caller) so the
 * {@code @Cacheable} proxy is actually applied: a self-invoked {@code @Cacheable} method bypasses
 * the proxy entirely (same rationale as {@link com.wealthview.core.exchangerate.ExchangeRateResolver},
 * the precedent for this shape). {@link #latestFor} replaces the identical
 * {@code findLatestBySymbolIn(...).stream().collect(toMap(getSymbol, getClosePrice))} idiom that
 * was previously duplicated across {@code AccountService} (two sites), {@code HoldingService}, and
 * {@code SecurityClassificationService}.
 *
 * <p>The cache key is the distinct symbol set, sorted and comma-joined, so the same set of symbols
 * in any order (or with duplicates) hits the same cache entry. Evicted (allEntries=true) via
 * {@code EvictPriceDerivedCaches} whenever prices or splits change, so a cached price cannot go
 * stale.
 */
@Component
public class LatestPriceLookup {

    private final PriceRepository priceRepository;

    public LatestPriceLookup(PriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    @Cacheable(value = "latestPrices",
            key = "T(String).join(',', #symbols.stream().distinct().sorted().toList())")
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> latestFor(Collection<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        var distinctSymbols = symbols.stream().distinct().toList();
        return priceRepository.findLatestBySymbolIn(distinctSymbols).stream()
                .collect(Collectors.toMap(PriceEntity::getSymbol, PriceEntity::getClosePrice));
    }
}
