package com.wealthview.app.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class ZillowHealthIndicatorTest {

    @Test
    void doHealthCheck_enabled_reportsUpWithEnabled() {
        var indicator = new ZillowHealthIndicator(true);
        var builder = new Health.Builder();

        indicator.doHealthCheck(builder);
        var health = builder.build();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("enabled", true);
        assertThat(health.getDetails()).doesNotContainKey("reason");
    }

    @Test
    void doHealthCheck_disabled_reportsUpWithDisabledReason() {
        var indicator = new ZillowHealthIndicator(false);
        var builder = new Health.Builder();

        indicator.doHealthCheck(builder);
        var health = builder.build();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("enabled", false);
        assertThat(health.getDetails()).containsEntry("reason", "Zillow integration disabled");
    }
}
