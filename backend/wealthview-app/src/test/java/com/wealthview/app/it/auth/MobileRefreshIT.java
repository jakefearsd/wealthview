package com.wealthview.app.it.auth;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
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
        var response = restTemplate.exchange("/api/v1/auth/token/refresh",
                HttpMethod.POST, new HttpEntity<>(Map.of(), jsonHeaders()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refresh_withNullField_returns400() {
        // {"refresh_token": null} — Bean Validation @NotBlank should reject.
        var body = new HashMap<String, Object>();
        body.put("refresh_token", null);

        var response = restTemplate.exchange("/api/v1/auth/token/refresh",
                HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refresh_withMalformedJson_returns400() {
        var response = restTemplate.exchange("/api/v1/auth/token/refresh",
                HttpMethod.POST, new HttpEntity<>("{not json", jsonHeaders()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refresh_withAccessTokenInBody_returns401() {
        var loginResponse = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        var accessToken = (String) loginResponse.getBody().get("access_token");

        // Hand the access token where a refresh token is expected. The
        // service-level validateRefreshToken must reject type=access tokens.
        var response = restTemplate.exchange("/api/v1/auth/token/refresh",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("refresh_token", accessToken), jsonHeaders()),
                MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_withInvalidJwtString_returns401() {
        var response = restTemplate.exchange("/api/v1/auth/token/refresh",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("refresh_token", "not-a-jwt"), jsonHeaders()),
                MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_consumedTwice_secondCallFails() {
        var loginResponse = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        var originalRefresh = (String) loginResponse.getBody().get("refresh_token");

        var firstRefresh = restTemplate.exchange("/api/v1/auth/token/refresh",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("refresh_token", originalRefresh), jsonHeaders()),
                MAP_TYPE);
        assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second call with the same (now-stale-generation) refresh token must fail.
        var secondRefresh = restTemplate.exchange("/api/v1/auth/token/refresh",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("refresh_token", originalRefresh), jsonHeaders()),
                MAP_TYPE);
        assertThat(secondRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_afterLogout_returns401() {
        var loginResponse = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        var accessToken = (String) loginResponse.getBody().get("access_token");
        var refreshToken = (String) loginResponse.getBody().get("refresh_token");

        var logout = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(bearerHeaders(accessToken)), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var refresh = restTemplate.exchange("/api/v1/auth/token/refresh",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("refresh_token", refreshToken), jsonHeaders()),
                MAP_TYPE);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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
