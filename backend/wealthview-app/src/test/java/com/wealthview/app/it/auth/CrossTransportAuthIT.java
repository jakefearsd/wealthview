package com.wealthview.app.it.auth;

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
 * The cookie path and the Bearer path issue and consume the same JWT —
 * neither transport carries a separate token format. These tests pin the
 * cross-transport invariants:
 *
 * <ol>
 *   <li>A cookie-issued token is acceptable as a Bearer header.</li>
 *   <li>A token-endpoint-issued token is acceptable as an {@code access_token}
 *       cookie.</li>
 *   <li>Logout in either transport invalidates the JWT for both.</li>
 *   <li>Refresh accepts a cookie-issued refresh token in the body.</li>
 * </ol>
 */
class CrossTransportAuthIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    @Test
    void tokenIssuedViaCookieEndpoint_worksAsBearer() {
        var session = authHelper.adminSession();

        var headers = new HttpHeaders();
        headers.setBearerAuth(session.accessToken());

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("email")).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void tokenIssuedViaTokenEndpoint_worksAsCookie() {
        var loginResponse = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        var bearerToken = (String) loginResponse.getBody().get("access_token");

        var headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "access_token=" + bearerToken);

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("email")).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void logoutViaCookiePath_revokesBearerToken() {
        var session = authHelper.adminSession();

        // Confirm the Bearer header authenticates pre-logout.
        var preLogout = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(session.accessToken())), MAP_TYPE);
        assertThat(preLogout.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Logout via the cookie path (uses CSRF + cookie auth via authHelper).
        var logout = restTemplate.exchange("/api/v1/auth/logout",
                HttpMethod.POST, authHelper.authEntity(null, session.accessToken()), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var postLogout = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(session.accessToken())), MAP_TYPE);
        assertThat(postLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutViaTokenPath_revokesCookieToken() {
        var loginResponse = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        var token = (String) loginResponse.getBody().get("access_token");

        // Confirm cookie-style use works pre-logout.
        var preHeaders = new HttpHeaders();
        preHeaders.add(HttpHeaders.COOKIE, "access_token=" + token);
        var preLogout = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(preHeaders), MAP_TYPE);
        assertThat(preLogout.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Logout via the token (Bearer) path.
        var logout = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(bearerHeaders(token)), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var postHeaders = new HttpHeaders();
        postHeaders.add(HttpHeaders.COOKIE, "access_token=" + token);
        var postLogout = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(postHeaders), MAP_TYPE);
        assertThat(postLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshViaTokenPath_acceptsCookieRefreshToken() {
        // adminSession() came from the cookie path; its refreshToken is a
        // standard refresh JWT. Hand it to the mobile refresh endpoint and
        // expect a fresh pair.
        var session = authHelper.adminSession();
        assertThat(session.refreshToken()).isNotBlank();

        var refresh = restTemplate.exchange("/api/v1/auth/token/refresh",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("refresh_token", session.refreshToken()), jsonHeaders()),
                MAP_TYPE);

        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh.getBody()).containsKeys("access_token", "refresh_token");
        assertThat((String) refresh.getBody().get("access_token")).isNotEqualTo(session.accessToken());
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
