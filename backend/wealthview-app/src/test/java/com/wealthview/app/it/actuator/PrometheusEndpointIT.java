package com.wealthview.app.it.actuator;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusEndpointIT extends AbstractApiIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void meterRegistry_isPrometheusType() {
        assertThat(meterRegistry.getClass().getName()).contains("Prometheus");
    }

    @Test
    void prometheusEndpoint_noAuth_returns200WithMetrics() {
        var response = restTemplate.exchange("/actuator/prometheus",
                HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("jvm_memory_used_bytes");
    }

    @Test
    void prometheusEndpoint_containsApplicationTag() {
        var response = restTemplate.exchange("/actuator/prometheus",
                HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("application=\"wealthview\"");
    }

    @Test
    void prometheusEndpoint_containsHikariMetrics() {
        var response = restTemplate.exchange("/actuator/prometheus",
                HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("hikaricp_connections");
    }

    @Test
    void healthEndpoint_noAuth_returns200() {
        var response = restTemplate.exchange("/actuator/health",
                HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }
}
