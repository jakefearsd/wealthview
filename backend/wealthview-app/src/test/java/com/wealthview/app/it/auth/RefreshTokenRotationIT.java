package com.wealthview.app.it.auth;

import java.time.OffsetDateTime;
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
import com.wealthview.app.it.AuthHelper;
import com.wealthview.app.it.testutil.HttpFixtures;
import com.wealthview.persistence.repository.RefreshTokenRepository;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of single-use refresh-token rotation: a refresh token
 * may be redeemed exactly once. A second redemption of the same JTI is
 * treated as evidence of compromise — the engine increments the user's
 * token_generation, which invalidates every outstanding token (including the
 * legitimate replacement) and forces re-login everywhere.
 *
 * <p>Each test runs against both the cookie path
 * ({@code /api/v1/auth/refresh}) and the bearer path
 * ({@code /api/v1/auth/token/refresh}) where applicable.
 */
class RefreshTokenRotationIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void tokenLogin_persistsRefreshTokenRow() {
        long before = countRefreshTokens();

        tokenLogin();

        assertThat(countRefreshTokens()).isEqualTo(before + 1);
    }

    @Test
    void tokenRefresh_singleUse_secondCallWithOldTokenFails() {
        var first = tokenLogin();
        var firstRefresh = first.refreshToken();

        var firstRefreshResp = tokenRefresh(firstRefresh);
        assertThat(firstRefreshResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var replay = tokenRefresh(firstRefresh);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenRefresh_reuseDetection_revokesAllUserTokens() {
        var first = tokenLogin();
        var originalRefresh = first.refreshToken();

        var legit = tokenRefresh(originalRefresh);
        assertThat(legit.getStatusCode()).isEqualTo(HttpStatus.OK);
        var replacementRefresh = (String) legit.getBody().get("refresh_token");
        var replacementAccess = (String) legit.getBody().get("access_token");

        // Sanity check: after legit refresh, token_generation has bumped.
        Integer genAfterLegit = jdbc.queryForObject(
                "SELECT token_generation FROM users WHERE id = ?",
                Integer.class, authHelper.adminUserId());
        assertThat(genAfterLegit).isGreaterThanOrEqualTo(1);

        // REUSE: hand back the original (already-consumed) refresh token.
        var reuseResp = tokenRefresh(originalRefresh);
        assertThat(reuseResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The legitimate replacement token must now ALSO be invalid because
        // reuse detection bumped token_generation across the whole user.
        var followUp = tokenRefresh(replacementRefresh);
        assertThat(followUp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The replacement access token, too.
        var meWithReplacement = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(HttpFixtures.bearerHeaders(replacementAccess)), MAP_TYPE);
        assertThat(meWithReplacement.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenLogout_revokesAllOutstandingRefreshTokens() {
        var login = tokenLogin();
        var accessToken = login.accessToken();

        var logout = restTemplate.exchange("/api/v1/auth/token/logout",
                HttpMethod.POST, new HttpEntity<>(HttpFixtures.bearerHeaders(accessToken)), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        long unrevoked = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Long.class, authHelper.adminUserId());
        assertThat(unrevoked).isEqualTo(0L);
    }

    @Test
    void tokenRefresh_byUnknownJti_returns401() {
        // The refresh-token row has a JTI that drives the database lookup. If
        // somebody hands us a JWT whose JTI was never persisted (e.g. a JWT
        // forged with a stolen signing key, or an in-flight token after the
        // row was wiped), the server must reject it.
        var login = tokenLogin();
        var freshRt = login.refreshToken();
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", authHelper.adminUserId());

        var resp = tokenRefresh(freshRt);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenRefresh_dbExpiredJti_returns401() {
        var login = tokenLogin();
        var rt = login.refreshToken();

        // Backdate the row's expires_at so the DB-side check fails even though
        // the JWT itself is still within its 24h expiry.
        jdbc.update("UPDATE refresh_tokens SET expires_at = ? WHERE user_id = ?",
                OffsetDateTime.now().minusHours(1), authHelper.adminUserId());

        var resp = tokenRefresh(rt);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void cookieRefresh_singleUse_secondCallWithOldCookieFails() {
        // Cookie path uses /api/v1/auth/{login,refresh}. Same single-use rule.
        var loginResp = api.postAnonForEntity("/api/v1/auth/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var refreshCookie = extractCookie(loginResp.getHeaders(), "refresh_token");

        var firstRefresh = cookieRefresh(refreshCookie);
        assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);

        var replay = cookieRefresh(refreshCookie);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private long countRefreshTokens() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?",
                Long.class, authHelper.adminUserId());
    }

    private AuthHelper.TokenPair tokenLogin() {
        return authHelper.mobileLogin(restTemplate, ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> tokenRefresh(String refreshToken) {
        return api.postAnonForEntity("/api/v1/auth/token/refresh",
                Map.of("refresh_token", refreshToken));
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> cookieRefresh(String refreshTokenCookie) {
        var headers = HttpFixtures.jsonHeaders();
        headers.add(HttpHeaders.COOKIE, "refresh_token=" + refreshTokenCookie);
        return restTemplate.exchange("/api/v1/auth/refresh",
                HttpMethod.POST, new HttpEntity<>(headers), MAP_TYPE);
    }

    private String extractCookie(HttpHeaders headers, String name) {
        var setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        for (var sc : setCookies) {
            for (var c : java.net.HttpCookie.parse(sc)) {
                if (name.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}
