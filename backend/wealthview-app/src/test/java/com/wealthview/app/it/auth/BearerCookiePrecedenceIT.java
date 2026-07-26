package com.wealthview.app.it.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pin the Bearer-vs-cookie precedence rules in {@link
 * com.wealthview.api.security.JwtAuthenticationFilter}.
 *
 * <p>The contract is "Bearer first, never fall back to cookie": once the
 * client has chosen to send an {@code Authorization: Bearer ...} header, the
 * filter trusts that and ignores the cookie. The cookie path is only used
 * when no Bearer header is present. This prevents an attacker who can plant
 * an {@code access_token} cookie in a victim's browser from piggy-backing on
 * an unrelated valid Bearer header from another origin or extension.
 */
class BearerCookiePrecedenceIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";
    private static final String MEMBER_EMAIL = "precedence-member@test.com";
    private static final String MEMBER_PASSWORD = "memberpass1";

    @Test
    void bothBearerAndCookie_bearerTokenWins() {
        // The cookie identifies the admin; the Bearer header identifies a
        // different user (a member in the same tenant). The me endpoint must
        // report the Bearer's identity.
        authHelper.createUserDirectly(MEMBER_EMAIL, MEMBER_PASSWORD, "member");
        var memberBearer = mobileLoginAs(MEMBER_EMAIL, MEMBER_PASSWORD);
        var adminCookie = authHelper.adminSession().accessToken();

        var headers = new HttpHeaders();
        headers.setBearerAuth(memberBearer);
        headers.add(HttpHeaders.COOKIE, "access_token=" + adminCookie);

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("email")).isEqualTo(MEMBER_EMAIL);
        assertThat(response.getBody().get("role")).isEqualTo("member");
    }

    /**
     * Critical security invariant. With a valid cookie present, an attacker
     * who can inject an arbitrary Authorization header must NOT be able to
     * trick the server into authenticating as the cookie user just by
     * supplying a garbage Bearer token. Bearer-first means the filter
     * commits to validating the Bearer token alone; if it fails, the request
     * is unauthenticated.
     */
    @Test
    void validCookieAndInvalidBearer_authenticationFails() {
        var adminCookie = authHelper.adminSession().accessToken();

        var headers = new HttpHeaders();
        headers.setBearerAuth("not.a.valid.jwt");
        headers.add(HttpHeaders.COOKIE, "access_token=" + adminCookie);

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noBearerOrCookie_returns401_onProtectedEndpoint() {
        var response = api.getAnonForEntity("/api/v1/auth/me");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String mobileLoginAs(String email, String password) {
        return authHelper.mobileLogin(restTemplate, email, password).accessToken();
    }
}
