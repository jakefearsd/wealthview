package com.wealthview.app.it.actuator;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the opt-in anonymous metrics scrape (`app.observability.anonymous-metrics`).
 *
 * <p>The bundled Prometheus/Grafana stack collected nothing for its entire existence: its scrape
 * config sent HTTP Basic credentials, but the app's filter chain only ever accepted a JWT — there
 * is no {@code UserDetailsService} and Basic was never enabled — so {@code /actuator/prometheus}
 * returned 401 on every single scrape. Nothing failed loudly; monitoring was simply blind.
 *
 * <p>The fix opens the two metrics endpoints explicitly rather than bolting a second
 * authentication mechanism onto the app. That makes the flag a security-relevant switch, so it is
 * tested from both sides: {@link PrometheusEndpointIT} asserts the default-off posture
 * (anonymous 401, non-super-admin 403, super-admin 200), and this class asserts that turning it on
 * actually lets an unauthenticated scraper through.
 *
 * <p>Deliberately also asserts that flipping the flag does NOT open the rest of {@code /actuator}
 * — only the two metrics paths. A blanket {@code /actuator/**} permitAll would expose
 * {@code /actuator/health} details and any endpoint later added to the exposure list.
 */
@TestPropertySource(properties = "app.observability.anonymous-metrics=true")
class AnonymousMetricsScrapeIT extends AbstractApiIntegrationTest {

    @Test
    void prometheusEndpoint_whenAnonymousMetricsEnabled_isScrapableWithoutCredentials() {
        var response = api.getAnonForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode())
                .as("Prometheus has no credential it can present; with the flag on the scrape "
                        + "must succeed unauthenticated or the stack collects nothing")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    void metricsEndpoint_whenAnonymousMetricsEnabled_isReachableWithoutCredentials() {
        var response = api.getAnonForEntity("/actuator/metrics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void httpServerRequestsHistogram_isExposed() {
        // The shipped Grafana latency panel and the WealthViewHttpP99Latency alert both run
        // histogram_quantile over this series. The buckets used to be enabled only under the
        // loadtest profile, so in prod the panel rendered empty and the alert could never fire.
        api.getAnonForEntity("/actuator/health", String.class);

        var response = api.getAnonForEntity("/actuator/prometheus", String.class);

        assertThat(response.getBody())
                .as("latency buckets must be published on every profile, not just loadtest")
                .contains("http_server_requests_seconds_bucket");
    }

    @Test
    void otherActuatorEndpoints_whenAnonymousMetricsEnabled_remainProtected() {
        var response = api.getAnonForEntity("/actuator/beans", String.class);

        assertThat(response.getStatusCode())
                .as("the flag opens the two metrics paths only — it must not unlock /actuator/**")
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }
}
