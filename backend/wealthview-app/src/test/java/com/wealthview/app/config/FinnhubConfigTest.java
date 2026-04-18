package com.wealthview.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The @Bean factory methods on FinnhubConfig accept @Value-injected properties and
 * build collaborators. These tests exercise the bean methods directly (no Spring
 * context needed) to pin their wiring semantics.
 */
class FinnhubConfigTest {

    private final FinnhubConfig config = new FinnhubConfig();

    @Test
    void finnhubRestClient_returnsNonNullRestClient() {
        var client = config.finnhubRestClient("https://finnhub.io");

        assertThat(client).isNotNull();
    }

    @Test
    void finnhubClient_returnsConfiguredFinnhubClient() {
        RestClient restClient = config.finnhubRestClient("https://finnhub.io");

        var finnhubClient = config.finnhubClient(restClient, "test-api-key");

        assertThat(finnhubClient).isNotNull();
    }
}
