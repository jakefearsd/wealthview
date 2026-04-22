package com.wealthview.app.config;

import com.wealthview.importmodule.finnhub.FinnhubClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@ConditionalOnExpression("!'${app.finnhub.api-key:}'.isEmpty()")
public class FinnhubConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    public RestClient finnhubRestClient(@Value("${app.finnhub.base-url:https://finnhub.io}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(HttpClientFactory.withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT))
                .build();
    }

    @Bean
    public FinnhubClient finnhubClient(RestClient finnhubRestClient,
                                       @Value("${app.finnhub.api-key}") String apiKey) {
        return new FinnhubClient(finnhubRestClient, apiKey);
    }
}
