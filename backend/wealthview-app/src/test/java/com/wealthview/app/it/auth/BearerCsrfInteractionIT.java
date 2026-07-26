package com.wealthview.app.it.auth;

import java.util.Map;

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
 * SecurityConfig configures the CSRF filter to ignore requests that carry an
 * {@code Authorization: Bearer} header — the rationale being that an attacker
 * page in a victim's browser can't set that header, so CSRF protection is
 * unnecessary for the Bearer transport. These tests pin that ignore-rule
 * across mutating, GET, and the four token endpoints.
 */
class BearerCsrfInteractionIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    @Test
    void bearerWithInvalidCsrfCookie_succeeds() {
        var token = mobileLogin();

        var headers = HttpFixtures.bearerJsonHeaders(token);
        // Deliberately wrong XSRF-TOKEN cookie. CSRF is skipped because of
        // the Bearer header, so this must NOT cause a 403.
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=this-is-wrong");
        var body = Map.of("name", "csrf-skipped brokerage", "type", "brokerage");

        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void bearerWithoutAnyCookies_succeeds() {
        var token = mobileLogin();

        var headers = HttpFixtures.bearerJsonHeaders(token);
        var body = Map.of("name", "no-cookies brokerage", "type", "brokerage");

        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void bearerOnGetRequest_succeedsWithoutCsrf() {
        var token = mobileLogin();

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(HttpFixtures.bearerHeaders(token)), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tokenLoginEndpoint_acceptsCsrflessRequest() {
        // No cookies, no XSRF — relying on the /api/v1/auth/token/** ignore
        // rule in SecurityConfig. A plain anonymous POST carries neither, so
        // this is request-shape identical to the raw jsonHeaders()-only entity
        // it replaces.
        var body = Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD);

        var response = api.postAnonForEntity("/api/v1/auth/token/login", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tokenRegisterEndpoint_acceptsCsrflessRequest() {
        var inviteCode = authHelper.createInviteCode();
        var body = Map.of(
                "email", "csrfless-register@test.com",
                "password", "regpass1234",
                "invite_code", inviteCode);

        var response = api.postAnonForEntity("/api/v1/auth/token/register", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void tokenRefreshEndpoint_acceptsCsrflessRequest() {
        var loginResponse = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var refresh = (String) loginResponse.getBody().get("refresh_token");

        var refreshResponse = api.postAnonForEntity("/api/v1/auth/token/refresh",
                Map.of("refresh_token", refresh));

        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tokenLogoutEndpoint_acceptsCsrflessRequest() {
        var token = mobileLogin();

        var response = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(HttpFixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private String mobileLogin() {
        return authHelper.mobileLogin(restTemplate, ADMIN_EMAIL, ADMIN_PASSWORD).accessToken();
    }
}
