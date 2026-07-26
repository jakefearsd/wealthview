package com.wealthview.app.it.auth;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.testutil.HttpFixtures;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

class AuthTokenIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    @Test
    void tokenLogin_withValidCredentials_returnsTokensInBodyNoCookies() {
        var body = Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD);

        var response = api.postAnonForEntity("/api/v1/auth/token/login", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys(
                "access_token", "refresh_token", "user_id", "tenant_id", "email", "role");
        assertThat(response.getBody().get("email")).isEqualTo(ADMIN_EMAIL);
        assertThat(response.getBody().get("access_token")).isInstanceOf(String.class);
        assertThat((String) response.getBody().get("access_token")).isNotBlank();
        // Auth tokens MUST NOT travel as cookies on the mobile transport.
        // Spring may still emit an XSRF-TOKEN cookie that mobile clients ignore;
        // the security invariant is "no access_token / refresh_token in cookies".
        assertNoAuthTokenCookies(response.getHeaders());
    }

    @Test
    void tokenLogin_withInvalidCredentials_returns401() {
        var body = Map.of("email", ADMIN_EMAIL, "password", "wrongpassword");

        var response = api.postAnonForEntity("/api/v1/auth/token/login", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "UNAUTHORIZED");
    }

    @Test
    void tokenRegister_withValidInviteCode_returns201WithTokens() {
        var inviteCode = authHelper.createInviteCode();
        var body = Map.of(
                "email", "mobile-newuser@test.com",
                "password", "mytestpass",
                "invite_code", inviteCode);

        var response = api.postAnonForEntity("/api/v1/auth/token/register", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKeys(
                "access_token", "refresh_token", "user_id", "tenant_id");
        assertThat(response.getBody().get("email")).isEqualTo("mobile-newuser@test.com");
        assertThat(response.getBody().get("role")).isEqualTo("member");
        assertNoAuthTokenCookies(response.getHeaders());
    }

    @Test
    void tokenRefresh_withValidRefreshToken_returnsNewPair() {
        var loginResponse = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var originalAccess = (String) loginResponse.getBody().get("access_token");
        var originalRefresh = (String) loginResponse.getBody().get("refresh_token");

        var refreshResponse = api.postAnonForEntity("/api/v1/auth/token/refresh",
                Map.of("refresh_token", originalRefresh));

        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshResponse.getBody()).containsKeys("access_token", "refresh_token");
        assertThat((String) refreshResponse.getBody().get("access_token")).isNotEqualTo(originalAccess);
        assertThat((String) refreshResponse.getBody().get("refresh_token")).isNotEqualTo(originalRefresh);
        assertNoAuthTokenCookies(refreshResponse.getHeaders());
    }

    @Test
    void tokenLogout_invalidatesAllTokensForUser() {
        var inviteCode = authHelper.createInviteCode();
        api.postAnonForEntity("/api/v1/auth/token/register", Map.of(
                        "email", "logout-victim@test.com",
                        "password", "mytestpass",
                        "invite_code", inviteCode));

        var loginResponse = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", "logout-victim@test.com", "password", "mytestpass"));
        var accessToken = (String) loginResponse.getBody().get("access_token");

        var logoutResponse = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(HttpFixtures.bearerHeaders(accessToken)), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var followUp = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(HttpFixtures.bearerHeaders(accessToken)), MAP_TYPE);
        assertThat(followUp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bearerHeader_isAcceptedByProtectedEndpoint() {
        var loginResponse = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var accessToken = (String) loginResponse.getBody().get("access_token");

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(HttpFixtures.bearerHeaders(accessToken)), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("email")).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void bearerHeader_skipsCsrf_onMutatingEndpoint() {
        var loginResponse = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var accessToken = (String) loginResponse.getBody().get("access_token");

        // POST without X-XSRF-TOKEN header — would 403 on the cookie path.
        var headers = HttpFixtures.bearerJsonHeaders(accessToken);
        var body = Map.of("name", "Mobile-created brokerage", "type", "brokerage");

        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("name")).isEqualTo("Mobile-created brokerage");
    }

    @Test
    void cookieAuth_stillRequiresCsrf_onMutatingEndpoint() {
        var session = authHelper.adminSession();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Cookie auth WITHOUT the X-XSRF-TOKEN header — A3 must not regress this.
        headers.add(HttpHeaders.COOKIE, "access_token=" + session.accessToken());
        var body = Map.of("name", "Should-fail brokerage", "type", "brokerage");

        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void assertNoAuthTokenCookies(HttpHeaders headers) {
        var setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return;
        }
        assertThat(setCookies).noneMatch(c -> c.startsWith("access_token="));
        assertThat(setCookies).noneMatch(c -> c.startsWith("refresh_token="));
    }
}
