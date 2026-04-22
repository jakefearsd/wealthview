package com.wealthview.app.config;

import com.wealthview.importmodule.yahoo.YahooFinanceClient;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class YahooConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    public RestClient yahooRestClient(
            @Value("${app.yahoo.base-url:https://query1.finance.yahoo.com}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(HttpClientFactory.withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT))
                .build();
    }

    @Bean
    public YahooFinanceClient yahooFinanceClient(
            RestClient yahooRestClient,
            @Value("${app.yahoo.rate-limit-ms:500}") long rateLimitMs) {
        return new YahooFinanceClient(yahooRestClient, rateLimitMs);
    }
}
