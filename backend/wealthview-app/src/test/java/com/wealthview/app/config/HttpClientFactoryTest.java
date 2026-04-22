package com.wealthview.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpClientFactoryTest {

    @Test
    void withTimeouts_hangingServer_triggersReadTimeoutWithinBudget() throws Exception {
        // Stand up a local TCP listener that accepts the connection but never
        // writes a response. A RestClient backed by our timeout factory must
        // give up quickly rather than pin the caller thread indefinitely.
        try (var server = new ServerSocket(0)) {
            var port = server.getLocalPort();
            var readTimeout = Duration.ofMillis(500);

            var client = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .requestFactory(HttpClientFactory.withTimeouts(Duration.ofSeconds(5), readTimeout))
                    .build();

            var started = System.nanoTime();
            assertThatThrownBy(() -> client.get().uri("/").retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class);
            var elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            // Allow slack for JVM warm-up / socket accept overhead, but insist
            // the caller gets control back within a small multiple of the budget.
            assertThat(elapsedMs).isLessThan(5_000);
        }
    }

    @Test
    void withTimeouts_unroutableHost_triggersConnectTimeoutWithinBudget() {
        // 10.255.255.1 is a TEST-NET address that should not be reachable — the
        // OS should stall the TCP handshake, so a short connect timeout must
        // bail out quickly.
        var connectTimeout = Duration.ofMillis(500);
        var client = RestClient.builder()
                .baseUrl("http://10.255.255.1")
                .requestFactory(HttpClientFactory.withTimeouts(connectTimeout, Duration.ofSeconds(15)))
                .build();

        var started = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
        var elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMs).isLessThan(5_000);
    }
}
