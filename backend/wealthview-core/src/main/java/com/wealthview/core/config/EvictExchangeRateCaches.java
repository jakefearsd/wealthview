package com.wealthview.core.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

/**
 * Composed cache-evict for methods that mutate a tenant's exchange rates (create, update, delete):
 * evicts the tenant-scoped {@code exchangeRates} and {@code accountBalances} entries (keyed by
 * {@code tenantId}), plus the whole {@code exchangeRateConversions} cache (keyed by
 * {@code (tenantId, currency)} in {@code ExchangeRateResolver}, so a mutation to any one currency
 * must clear every entry for the tenant, not just the mutated currency).
 *
 * <p>Requires the annotated method to have a {@code UUID tenantId} parameter — the composed
 * {@code key = "#tenantId"} SpEL expression is evaluated against the annotated method's own
 * arguments, not this annotation's. Replaces the identical {@code @Caching(evict = {...})} block
 * duplicated across {@code ExchangeRateService#create/update/delete}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Caching(evict = {
        @CacheEvict(value = {"exchangeRates", "accountBalances"}, key = "#tenantId"),
        @CacheEvict(value = "exchangeRateConversions", allEntries = true)
})
public @interface EvictExchangeRateCaches {
}
