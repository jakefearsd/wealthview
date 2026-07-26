package com.wealthview.core.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.cache.annotation.CacheEvict;

/**
 * Composed {@link CacheEvict} for methods that mutate price data (a manual price entry, a Yahoo or
 * Finnhub sync, a bulk/CSV price import, a price deletion, or a stock split apply/unapply): evicts
 * the {@code latestPrices} cache and the {@code accountBalances} cache it feeds into, both
 * allEntries, together.
 *
 * <p>Spring's caching annotations ({@code @Cacheable}, {@code @CacheEvict}, {@code @Caching}, ...)
 * are supported as meta-annotations for exactly this purpose: composing a reusable, named
 * annotation instead of repeating the same attribute list at every call site. Replaces the
 * identical {@code @CacheEvict(value = {"latestPrices", "accountBalances"}, allEntries = true)}
 * annotation that was duplicated across {@code PriceService} (5 sites), {@code StockSplitService}
 * (2 sites), and {@code PriceSyncService} (1 site).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@CacheEvict(value = {"latestPrices", "accountBalances"}, allEntries = true)
public @interface EvictPriceDerivedCaches {
}
