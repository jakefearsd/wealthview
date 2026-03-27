package com.wealthview.app.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

@Component
public class ZillowHealthIndicator extends AbstractHealthIndicator {

    private final boolean enabled;

    public ZillowHealthIndicator(@Value("${app.zillow.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.up()
                .withDetail("enabled", enabled);
        if (!enabled) {
            builder.withDetail("reason", "Zillow integration disabled");
        }
    }
}
