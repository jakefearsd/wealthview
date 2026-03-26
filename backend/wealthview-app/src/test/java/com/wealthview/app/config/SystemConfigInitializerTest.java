package com.wealthview.app.config;

import com.wealthview.core.config.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SystemConfigInitializerTest {

    @Mock
    private SystemConfigService configService;

    @InjectMocks
    private SystemConfigInitializer initializer;

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, String>> mapCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
    }

    @Test
    void run_seedsRateLimitDefaults_whenApiKeyIsBlank() throws Exception {
        initializer.setFinnhubApiKey("");
        initializer.setFinnhubRateLimit("1100");
        initializer.setYahooRateLimit("500");

        initializer.run();

        var captor = mapCaptor();
        verify(configService).seedDefaults(captor.capture());

        Map<String, String> seeded = captor.getValue();
        assertThat(seeded).containsKey("finnhub.rate-limit-ms");
        assertThat(seeded).containsKey("yahoo.rate-limit-ms");
        assertThat(seeded).doesNotContainKey("finnhub.api-key");
    }

    @Test
    void run_seedsApiKey_whenApiKeyIsPresent() throws Exception {
        initializer.setFinnhubApiKey("test-api-key-value");
        initializer.setFinnhubRateLimit("1100");
        initializer.setYahooRateLimit("500");

        initializer.run();

        var captor = mapCaptor();
        verify(configService).seedDefaults(captor.capture());

        Map<String, String> seeded = captor.getValue();
        assertThat(seeded).containsEntry("finnhub.api-key", "test-api-key-value");
        assertThat(seeded).containsEntry("finnhub.rate-limit-ms", "1100");
        assertThat(seeded).containsEntry("yahoo.rate-limit-ms", "500");
    }

    @Test
    void run_usesDefaultRateLimits_whenValuesAreDefault() throws Exception {
        initializer.setFinnhubApiKey("");
        initializer.setFinnhubRateLimit("1100");
        initializer.setYahooRateLimit("500");

        initializer.run();

        var captor = mapCaptor();
        verify(configService).seedDefaults(captor.capture());

        Map<String, String> seeded = captor.getValue();
        assertThat(seeded).containsEntry("finnhub.rate-limit-ms", "1100");
        assertThat(seeded).containsEntry("yahoo.rate-limit-ms", "500");
    }
}
