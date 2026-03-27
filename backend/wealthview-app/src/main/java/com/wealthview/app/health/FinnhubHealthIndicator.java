package com.wealthview.app.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

@Component
public class FinnhubHealthIndicator extends AbstractHealthIndicator {

    private final String apiKey;

    public FinnhubHealthIndicator(@Value("${app.finnhub.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        if (apiKey == null || apiKey.isBlank()) {
            builder.up()
                    .withDetail("apiKeyConfigured", false)
                    .withDetail("reason", "API key not configured — price sync disabled");
        } else {
            builder.up()
                    .withDetail("apiKeyConfigured", true);
        }
    }
}
