package com.wealthview.app.it.auth;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.testutil.HttpFixtures;
import com.wealthview.core.auth.JwtTokenProvider;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge-case validation for the Bearer-token transport on protected endpoints.
 *
 * <p>The mobile path runs the same {@code JwtAuthenticationFilter} as the
 * cookie path, but accepts {@code Authorization: Bearer <jwt>} in addition to
 * (and in preference to) the {@code access_token} cookie. These tests cover
 * malformed headers, mismatched schemes, wrong token types, foreign secrets,
 * and revocation paths that should each reject the request with 401.
 */
class BearerTokenValidationIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void bearerHeader_withMalformedJwt_returns401() {
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt");

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bearerHeader_withEmptyToken_returns401() {
        // "Bearer " with no token after the space — extractBearerToken returns
        // null because the trimmed remainder is empty, the cookie path is also
        // absent, so the request falls through to the auth entry point.
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer ");

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bearerHeader_withWhitespaceOnlyToken_returns401() {
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer       ");

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bearerHeader_withRandomBase64_returns401() {
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer ZGVhZGJlZWY=");

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bearerHeader_withWrongSchemeToken_returns401() {
        var validToken = mobileLogin();
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Token " + validToken);

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Documented behavior: {@code extractBearerToken} performs a case-sensitive
     * {@code header.startsWith("Bearer ")} check, so {@code "bearer "} is not
     * recognised as the Bearer scheme. With no cookie present, the request is
     * unauthenticated. This test pins that behavior so any future tweak to the
     * parsing rule shows up here as a deliberate change.
     */
    @Test
    void bearerHeader_withWrongCaseScheme_returns401() {
        var validToken = mobileLogin();
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "bearer " + validToken);

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bearerHeader_withTokenSignedByDifferentSecret_returns401() {
        var foreign = new JwtTokenProvider(
                "DIFFERENT_HS256_SIGNING_KEY_FIXED_VALUE_FOR_TEST_DETERMINISM_ONLY",
                3600000L,
                86400000L);
        var forgedToken = foreign.generateAccessToken(
                UUID.randomUUID(), authHelper.tenantId(), "admin", ADMIN_EMAIL, 0);

        var headers = new HttpHeaders();
        headers.setBearerAuth(forgedToken);

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bearerHeader_withRefreshTokenInAuthHeader_returns401() {
        var loginResponse = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var refreshToken = (String) loginResponse.getBody().get("refresh_token");

        var headers = new HttpHeaders();
        headers.setBearerAuth(refreshToken);

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bearerHeader_withExpiredAccessToken_returns401() {
        // Build a JwtTokenProvider with a *negative* access expiration so the
        // token's `exp` lies in the past. Use the same secret/issuer/audience
        // as the running app so signature + headers all validate; only the
        // `exp` claim drives rejection. Five minutes back to clear the 60s
        // clock-skew tolerance baked into JwtTokenProvider.
        var sameSecret = new JwtTokenProvider(
                "INTEGRATION_TEST_HS256_SIGNING_KEY_FIXED_VALUE_FOR_TEST_DETERMINISM_ONLY",
                -300000L,
                86400000L);
        var expired = sameSecret.generateAccessToken(
                authHelper.adminUserId(), authHelper.tenantId(), "admin", ADMIN_EMAIL, 0);

        var headers = new HttpHeaders();
        headers.setBearerAuth(expired);

        var response = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bearerHeader_withTokenForDeletedUser_returns401() {
        // Bootstrap a member user inside the admin's tenant, login as that
        // user via the token endpoint, then delete the row directly. The
        // session-state validator must reject the now-orphan token on the
        // next request via its "user not found" branch.
        //
        // We delete with JdbcTemplate (and pre-clear FK references in
        // login_activity / audit_log) instead of going through the REST
        // DELETE endpoint because the API-level cascade isn't wired for
        // these audit tables — that's a separate concern this test isn't
        // about. We're exercising the SessionStateValidator path, not the
        // delete-user controller.
        var memberId = authHelper.createUserDirectly("delete-victim@test.com",
                ADMIN_PASSWORD, "member");
        var memberToken = mobileLoginAs("delete-victim@test.com", ADMIN_PASSWORD);

        // Sanity: token works before deletion.
        var preCheck = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(HttpFixtures.bearerHeaders(memberToken)), MAP_TYPE);
        assertThat(preCheck.getStatusCode()).isEqualTo(HttpStatus.OK);

        jdbcTemplate.update("DELETE FROM audit_log WHERE user_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM login_activity WHERE user_email = ?", "delete-victim@test.com");
        var deleted = jdbcTemplate.update("DELETE FROM users WHERE id = ?", memberId);
        assertThat(deleted).isEqualTo(1);

        var postCheck = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(HttpFixtures.bearerHeaders(memberToken)), MAP_TYPE);
        assertThat(postCheck.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bearerHeader_withTokenForDisabledTenant_returns401() {
        // Login on the (still-active) admin tenant, then have the super_admin
        // disable that tenant. The cached JWT should stop working immediately
        // because the SessionStateValidator consults the tenant's active flag.
        var memberId = authHelper.createUserDirectly("tenant-disable-victim@test.com",
                ADMIN_PASSWORD, "member");
        var memberToken = mobileLoginAs("tenant-disable-victim@test.com", ADMIN_PASSWORD);

        // Bootstrap a super_admin and disable the admin tenant via the
        // SuperAdmin endpoint. The super_admin lives inside the same tenant
        // bootstrap to avoid the multi-tenant setup overhead, but their
        // disable call applies to that tenant, which is the point of the test.
        authHelper.createSuperAdminDirectly("super-disable@test.com", ADMIN_PASSWORD);
        var superToken = authHelper.loginAs(restTemplate, "super-disable@test.com", ADMIN_PASSWORD);

        var setActive = api.putForEntityAs(superToken,
                "/api/v1/admin/tenants/" + authHelper.tenantId() + "/active",
                Map.of("active", false), Void.class);
        assertThat(setActive.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var followUp = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(HttpFixtures.bearerHeaders(memberToken)), MAP_TYPE);
        assertThat(followUp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Touch memberId to keep the variable used; it documents the user
        // identity carried by the token under test.
        assertThat(memberId).isNotNull();
    }

    @Test
    void bearerHeader_withStaleGenerationAfterReLogin_returns401() {
        // Token generation moves on logout, but also on every successful
        // refresh — so we exercise the stale-generation rejection path
        // explicitly: login, refresh (which bumps the generation), then try
        // the original access token.
        var loginResponse = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var originalAccess = (String) loginResponse.getBody().get("access_token");
        var originalRefresh = (String) loginResponse.getBody().get("refresh_token");

        var refresh = api.postAnonForEntity("/api/v1/auth/token/refresh",
                Map.of("refresh_token", originalRefresh));
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);

        var followUp = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(HttpFixtures.bearerHeaders(originalAccess)), MAP_TYPE);
        assertThat(followUp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String mobileLogin() {
        return mobileLoginAs(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private String mobileLoginAs(String email, String password) {
        return authHelper.mobileLogin(restTemplate, email, password).accessToken();
    }
}
