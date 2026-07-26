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
 * Edge cases for {@code POST /api/v1/auth/token/logout}.
 *
 * <p>The endpoint requires authentication (per SecurityConfig — only the
 * three unauthenticated token endpoints are login/register/refresh). These
 * tests document the unauthenticated-call rejection, the cookie-auth
 * fallthrough, and the double-logout idempotency boundary.
 */
class MobileLogoutIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    @Test
    void tokenLogout_withoutBearerHeader_returns401() {
        var response = api.postAnonForEntity("/api/v1/auth/token/logout");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The token-logout endpoint is mapped to a controller method that takes
     * the {@code @AuthenticationPrincipal} — Spring populates that from
     * either transport. The /api/v1/auth/token/** path is on the CSRF
     * ignore list, so a cookie-authenticated client can hit logout there
     * too. This test pins that current behavior so a future tightening
     * (Bearer-only logout) is a deliberate, visible change.
     */
    @Test
    void tokenLogout_withCookieAuth_works() {
        var session = authHelper.adminSession();

        var headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "access_token=" + session.accessToken());

        var response = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void tokenLogout_calledTwice_secondCallFails() {
        var loginResponse = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        var token = (String) loginResponse.getBody().get("access_token");

        var firstLogout = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(bearerHeaders(token)), Void.class);
        assertThat(firstLogout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The first logout incremented token_generation, so the same Bearer
        // is now stale — the second call hits the auth filter as
        // unauthenticated and the entry-point returns 401, never reaching
        // the controller.
        var secondLogout = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(bearerHeaders(token)), MAP_TYPE);
        assertThat(secondLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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
