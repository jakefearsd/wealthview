package com.wealthview.app.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class FinnhubHealthIndicatorTest {

    @Test
    void doHealthCheck_withApiKey_reportsUp() {
        var indicator = new FinnhubHealthIndicator("test-api-key");
        var builder = new Health.Builder();

        indicator.doHealthCheck(builder);
        var health = builder.build();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("apiKeyConfigured", true);
    }

    @Test
    void doHealthCheck_withoutApiKey_reportsUpWithNotConfigured() {
        var indicator = new FinnhubHealthIndicator("");
        var builder = new Health.Builder();

        indicator.doHealthCheck(builder);
        var health = builder.build();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("apiKeyConfigured", false);
    }

    @Test
    void doHealthCheck_withNullApiKey_reportsUpWithNotConfigured() {
        var indicator = new FinnhubHealthIndicator(null);
        var builder = new Health.Builder();

        indicator.doHealthCheck(builder);
        var health = builder.build();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("apiKeyConfigured", false);
    }
}
