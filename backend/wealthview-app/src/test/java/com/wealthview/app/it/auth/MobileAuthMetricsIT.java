package com.wealthview.app.it.auth;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mobile-auth shipping note added a {@code transport={cookie|bearer}}
 * tag to the {@code wealthview.auth.*} counters so dashboards can break down
 * usage by transport. This IT pins the tag's presence by querying the
 * MeterRegistry directly — substring-grep on the prometheus output is
 * brittle, especially with the SAMPLE counter ordering across runs.
 */
class MobileAuthMetricsIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void tokenLogin_incrementsMeterWithTransportBearer() {
        var before = readCounter("wealthview.auth.login",
                "result", "success", "reason", "ok", "transport", "bearer");

        mobileLogin();

        var after = readCounter("wealthview.auth.login",
                "result", "success", "reason", "ok", "transport", "bearer");
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void cookieLogin_incrementsMeterWithTransportCookie() {
        // The bootstrap login in AbstractApiIntegrationTest#setUp already
        // emits one cookie-tagged success counter. Take a baseline post-
        // bootstrap, log in once more via the cookie endpoint, and assert
        // the counter advanced.
        var before = readCounter("wealthview.auth.login",
                "result", "success", "reason", "ok", "transport", "cookie");

        var response = restTemplate.exchange("/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var after = readCounter("wealthview.auth.login",
                "result", "success", "reason", "ok", "transport", "cookie");
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void tokenRefresh_incrementsMeterWithTransportBearer() {
        var loginResponse = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        var refresh = (String) loginResponse.getBody().get("refresh_token");

        var before = readCounter("wealthview.auth.refresh",
                "result", "success", "reason", "ok", "transport", "bearer");

        var refreshResponse = restTemplate.exchange("/api/v1/auth/token/refresh",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("refresh_token", refresh), jsonHeaders()),
                MAP_TYPE);
        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        var after = readCounter("wealthview.auth.refresh",
                "result", "success", "reason", "ok", "transport", "bearer");
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void tokenLogout_incrementsMeterWithTransportBearer() {
        var token = mobileLogin();

        var before = readCounter("wealthview.auth.logout", "transport", "bearer");

        var logout = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(bearerHeaders(token)), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var after = readCounter("wealthview.auth.logout", "transport", "bearer");
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void cookieLogout_incrementsMeterWithTransportCookie() {
        var session = authHelper.adminSession();

        var before = readCounter("wealthview.auth.logout", "transport", "cookie");

        var logout = restTemplate.exchange("/api/v1/auth/logout",
                HttpMethod.POST, authHelper.authEntity(null, session.accessToken()), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var after = readCounter("wealthview.auth.logout", "transport", "cookie");
        assertThat(after).isGreaterThan(before);
    }

    private double readCounter(String name, String... tags) {
        var search = meterRegistry.find(name);
        for (int i = 0; i < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        var counter = search.counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String mobileLogin() {
        var response = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("access_token");
    }

    private HttpHeaders jsonHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders bearerHeaders(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
