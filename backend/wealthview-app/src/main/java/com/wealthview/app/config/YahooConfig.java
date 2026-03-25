package com.wealthview.app.config;

import com.wealthview.importmodule.yahoo.YahooFinanceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "app.yahoo.enabled", havingValue = "true")
public class YahooConfig {

    @Bean
    public RestClient yahooRestClient(
            @Value("${app.yahoo.base-url:https://query1.finance.yahoo.com}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Bean
    public YahooFinanceClient yahooFinanceClient(
            RestClient yahooRestClient,
            @Value("${app.yahoo.rate-limit-ms:500}") long rateLimitMs) {
        return new YahooFinanceClient(yahooRestClient, rateLimitMs);
    }
}
