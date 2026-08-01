package com.wealthview.app.it.actuator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusEndpointIT extends AbstractApiIntegrationTest {

    private static final String SUPER_ADMIN_EMAIL = "it-super-admin@wealthview.test";
    private static final String SUPER_ADMIN_PASSWORD = "testpass123";

    @Autowired
    private MeterRegistry meterRegistry;

    private String superAdminToken;

    @BeforeEach
    void setUpSuperAdmin() {
        authHelper.createSuperAdminDirectly(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD);
        superAdminToken = authHelper.loginAs(restTemplate, SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD);
    }

    @Test
    void meterRegistry_isPrometheusType() {
        assertThat(meterRegistry.getClass().getName()).contains("Prometheus");
    }

    @Test
    void prometheusEndpoint_noAuth_returns401() {
        var response = api.getAnonForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void prometheusEndpoint_nonSuperAdmin_returns403() {
        var response = api.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // 403 must surface through the standard error envelope. Without a
        // custom AccessDeniedHandler in SecurityConfig, Spring Security
        // returns an empty body here, which clients can't parse.
        assertThat(response.getBody())
                .as("403 from the security filter chain should serialize the standard error envelope")
                .contains("\"error\":\"FORBIDDEN\"")
                .contains("\"status\":403");
    }

    @Test
    void prometheusEndpoint_superAdmin_returns200WithMetrics() {
        var response = api.getForEntityAs(superAdminToken, "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("jvm_memory_used_bytes");
    }

    @Test
    void prometheusEndpoint_containsApplicationTag() {
        var response = api.getForEntityAs(superAdminToken, "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("application=\"wealthview\"");
    }

    @Test
    void prometheusEndpoint_containsHikariMetrics() {
        var response = api.getForEntityAs(superAdminToken, "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("hikaricp_connections");
    }

    @Test
    void prometheusEndpoint_afterAMonteCarloOptimization_stillRendersAndExposesItsBuckets() {
        // Regression guard. MonteCarloSpendingOptimizer#optimize used to carry BOTH
        // @Timed(histogram = true) and @Observed under the same meter name. Both register a timer,
        // so one Prometheus family ended up holding Histogram data points and Summary data points;
        // the text writer casts every point to the first one's type and threw ClassCastException,
        // which failed the ENTIRE scrape — every metric, not just this one. Nothing caught it
        // because no integration test ran an optimization before scraping.
        var scenarioId = (String) data.createScenario("Prometheus regression").get("id");
        var optimize = api.postForEntity("/api/v1/projections/" + scenarioId + "/optimize",
                java.util.Map.of("name", "Guardrail", "trial_count", 100, "confidence_level", 0.90));
        assertThat(optimize.getStatusCode()).isEqualTo(HttpStatus.OK);

        var response = api.getForEntityAs(superAdminToken, "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode())
                .as("a scrape after an optimization must not 500")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("the Grafana p95 panel queries wealthview_mc_optimize_seconds_bucket, so the "
                        + "percentile histogram must survive the move from @Timed to a MeterFilter")
                .contains("wealthview_mc_optimize_seconds_bucket");
    }

    @Test
    void healthEndpoint_noAuth_returns200() {
        var response = api.getAnonForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }
}
