package com.wealthview.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigTest {

    private static final String[] EXPECTED_CACHES = {
            "accountBalances", "latestPrices", "exchangeRates", "exchangeRateConversions",
            "taxBrackets", "standardDeductions"
    };

    private static CacheManager initialised(CacheManager manager) {
        ((SimpleCacheManager) manager).afterPropertiesSet();
        return manager;
    }

    @Test
    void cacheManager_registersCaffeineMetricsForEveryCache() {
        var registry = new SimpleMeterRegistry();

        var manager = initialised(new CacheConfig().cacheManager(registry));

        for (var cacheName : EXPECTED_CACHES) {
            assertThat(manager.getCache(cacheName)).isInstanceOf(CaffeineCache.class);
            // CaffeineCacheMetrics emits cache.gets / cache.puts / cache.size with cache=<name>.
            assertThat(registry.find("cache.gets").tag("cache", cacheName).meters())
                    .as("cache.gets meter for %s", cacheName)
                    .isNotEmpty();
            assertThat(registry.find("cache.size").tag("cache", cacheName).meters())
                    .as("cache.size meter for %s", cacheName)
                    .isNotEmpty();
        }
    }

    @Test
    void cacheManager_recordsHitMissOnUsage() {
        var registry = new SimpleMeterRegistry();
        var manager = initialised(new CacheConfig().cacheManager(registry));
        var cache = manager.getCache("accountBalances");

        cache.put("k1", "v1");
        cache.get("k1");        // hit
        cache.get("k-missing"); // miss

        var hits = registry.find("cache.gets").tag("cache", "accountBalances").tag("result", "hit").functionCounter();
        var misses = registry.find("cache.gets").tag("cache", "accountBalances").tag("result", "miss").functionCounter();
        assertThat(hits).isNotNull();
        assertThat(misses).isNotNull();
        assertThat(hits.count()).isGreaterThanOrEqualTo(1.0);
        assertThat(misses.count()).isGreaterThanOrEqualTo(1.0);
    }
}
