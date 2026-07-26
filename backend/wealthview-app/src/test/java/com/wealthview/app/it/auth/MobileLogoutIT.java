package com.wealthview.app.it.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.testutil.HttpFixtures;

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
        var token = authHelper.mobileLogin(restTemplate, ADMIN_EMAIL, ADMIN_PASSWORD).accessToken();

        var firstLogout = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(HttpFixtures.bearerHeaders(token)), Void.class);
        assertThat(firstLogout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The first logout incremented token_generation, so the same Bearer
        // is now stale — the second call hits the auth filter as
        // unauthenticated and the entry-point returns 401, never reaching
        // the controller.
        var secondLogout = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(HttpFixtures.bearerHeaders(token)), MAP_TYPE);
        assertThat(secondLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
