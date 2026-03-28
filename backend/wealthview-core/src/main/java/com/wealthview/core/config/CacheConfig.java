package com.wealthview.core.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        var manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                buildCache("accountBalances", 5, TimeUnit.MINUTES, 200),
                buildCache("latestPrices", 10, TimeUnit.MINUTES, 500),
                buildCache("exchangeRates", 30, TimeUnit.MINUTES, 200),
                buildCache("taxBrackets", 24, TimeUnit.HOURS, 10),
                buildCache("standardDeductions", 24, TimeUnit.HOURS, 10)
        ));
        return manager;
    }

    private CaffeineCache buildCache(String name, long duration, TimeUnit unit, int maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(duration, unit)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}
