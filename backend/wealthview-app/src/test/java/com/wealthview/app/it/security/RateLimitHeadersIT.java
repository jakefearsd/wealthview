package com.wealthview.app.it.security;

import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standard rate-limit response headers (X-RateLimit-Limit / -Remaining /
 * -Reset) must travel on every response — successful 2xx, 4xx, and the 429
 * itself. Mobile clients use these to back off proactively.
 *
 * <p>The base IT profile disables rate limiting for test stability; this
 * class flips it back on via @TestPropertySource so the filter runs.
 */
@TestPropertySource(properties = "app.rate-limit.enabled=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitHeadersIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    @Test
    @Order(1)
    void headers_presentOnSuccessfulLogin() {
        var resp = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst("X-RateLimit-Limit")).isNotNull();
        assertThat(resp.getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
        assertThat(resp.getHeaders().getFirst("X-RateLimit-Reset")).isNotNull();
    }

    @Test
    @Order(2)
    void remaining_decrementsAcrossRequests() {
        var first = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        var firstRemaining = Integer.parseInt(first.getHeaders().getFirst("X-RateLimit-Remaining"));

        var second = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        var secondRemaining = Integer.parseInt(second.getHeaders().getFirst("X-RateLimit-Remaining"));

        assertThat(secondRemaining).isLessThan(firstRemaining);
    }

    @Test
    @Order(3)
    void resetHeader_isFutureUnixTimestamp() {
        var resp = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);

        long reset = Long.parseLong(resp.getHeaders().getFirst("X-RateLimit-Reset"));
        long now = System.currentTimeMillis() / 1000L;
        assertThat(reset).isBetween(now, now + 60L);
    }

    @Test
    @Order(99)
    void headers_presentOn429Response() {
        // Auth endpoints get 60 requests per IP per minute. Burn through the budget.
        org.springframework.http.ResponseEntity<Map<String, Object>> last = null;
        for (int i = 0; i < 65; i++) {
            last = restTemplate.exchange("/api/v1/auth/token/login",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("email", "nobody-" + i + "@x.test", "password", "pw"),
                            jsonHeaders()),
                    MAP_TYPE);
            if (last.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                break;
            }
        }
        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(last.getHeaders().getFirst("X-RateLimit-Limit")).isNotNull();
        assertThat(last.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(last.getHeaders().getFirst("X-RateLimit-Reset")).isNotNull();
    }

    private HttpHeaders jsonHeaders() {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
