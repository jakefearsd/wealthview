package com.wealthview.app.config;

import com.wealthview.importmodule.zillow.ZillowScraperClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.zillow.enabled", havingValue = "true")
public class ZillowConfig {

    @Bean
    public ZillowScraperClient zillowScraperClient(
            @Value("${app.zillow.timeout-ms:10000}") int timeoutMs) {
        return new ZillowScraperClient(timeoutMs);
    }
}
