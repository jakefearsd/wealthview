package com.wealthview.app.config;

import com.wealthview.importmodule.finnhub.FinnhubClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnExpression("!'${app.finnhub.api-key:}'.isEmpty()")
public class FinnhubConfig {

    @Bean
    public RestClient finnhubRestClient(@Value("${app.finnhub.base-url:https://finnhub.io}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Bean
    public FinnhubClient finnhubClient(RestClient finnhubRestClient,
                                       @Value("${app.finnhub.api-key}") String apiKey) {
        return new FinnhubClient(finnhubRestClient, apiKey);
    }
}
