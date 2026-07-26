package com.wealthview.app.it.auth;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.testutil.HttpFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative-path coverage for {@code POST /api/v1/auth/token/refresh}.
 * The happy path is covered in {@link AuthTokenIT}; here we exercise
 * malformed bodies, wrong token types, replay, and post-logout behavior.
 */
class MobileRefreshIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    @Test
    void refresh_withMissingField_returns400() {
        var response = api.postAnonForEntity("/api/v1/auth/token/refresh", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refresh_withNullField_returns400() {
        // {"refresh_token": null} — Bean Validation @NotBlank should reject.
        var body = new HashMap<String, Object>();
        body.put("refresh_token", null);

        var response = api.postAnonForEntity("/api/v1/auth/token/refresh", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refresh_withMalformedJson_returns400() {
        var response = api.postAnonForEntity("/api/v1/auth/token/refresh", "{not json");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refresh_withAccessTokenInBody_returns401() {
        var loginResponse = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var accessToken = (String) loginResponse.getBody().get("access_token");

        // Hand the access token where a refresh token is expected. The
        // service-level validateRefreshToken must reject type=access tokens.
        var response = api.postAnonForEntity("/api/v1/auth/token/refresh",
                Map.of("refresh_token", accessToken));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_withInvalidJwtString_returns401() {
        var response = api.postAnonForEntity("/api/v1/auth/token/refresh",
                Map.of("refresh_token", "not-a-jwt"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_consumedTwice_secondCallFails() {
        var loginResponse = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var originalRefresh = (String) loginResponse.getBody().get("refresh_token");

        var firstRefresh = api.postAnonForEntity("/api/v1/auth/token/refresh",
                Map.of("refresh_token", originalRefresh));
        assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second call with the same (now-stale-generation) refresh token must fail.
        var secondRefresh = api.postAnonForEntity("/api/v1/auth/token/refresh",
                Map.of("refresh_token", originalRefresh));
        assertThat(secondRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_afterLogout_returns401() {
        var loginResponse = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var accessToken = (String) loginResponse.getBody().get("access_token");
        var refreshToken = (String) loginResponse.getBody().get("refresh_token");

        var logout = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(HttpFixtures.bearerHeaders(accessToken)), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var refresh = api.postAnonForEntity("/api/v1/auth/token/refresh",
                Map.of("refresh_token", refreshToken));
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
