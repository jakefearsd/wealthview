package com.wealthview.app.it.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code GET /api/v1/auth/sessions} and the two DELETE flavors:
 * single-session revoke and "all other" revoke.
 */
class SessionManagementIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listSessions_afterMultipleLogins_returnsAllActive() {
        var s1 = tokenLogin();
        var s2 = tokenLogin();
        var s3 = tokenLogin();

        var resp = listSessions((String) s3.get("access_token"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void listSessions_marksCurrentSession() {
        var s1 = tokenLogin();
        var s2 = tokenLogin();
        var s2Access = (String) s2.get("access_token");

        var resp = listSessions(s2Access);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var rows = resp.getBody();
        long currentCount = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("current"))).count();
        assertThat(currentCount).isEqualTo(1);
    }

    @Test
    void revokeSession_invalidatesItsToken_otherSessionsContinue() {
        var aLogin = tokenLogin();
        var bLogin = tokenLogin();
        var aAccess = (String) aLogin.get("access_token");
        var bAccess = (String) bLogin.get("access_token");

        var sessions = listSessions(aAccess).getBody();
        // Revoke the OTHER session (the one we're not using to make the call).
        var idToRevoke = otherSessionId(sessions);
        var del = restTemplate.exchange("/api/v1/auth/sessions/" + idToRevoke,
                HttpMethod.DELETE, new HttpEntity<>(bearerHeaders(aAccess)), Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The B token (which corresponds to the just-revoked session) is now invalid.
        var checkB = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(bAccess)), MAP_TYPE);
        assertThat(checkB.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The A token still works.
        var checkA = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(aAccess)), MAP_TYPE);
        assertThat(checkA.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void revokeOwnCurrentSession_logsOutCaller() {
        var login = tokenLogin();
        var access = (String) login.get("access_token");

        var sessions = listSessions(access).getBody();
        var currentId = currentSessionId(sessions);

        var del = restTemplate.exchange("/api/v1/auth/sessions/" + currentId,
                HttpMethod.DELETE, new HttpEntity<>(bearerHeaders(access)), Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var followUp = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(access)), MAP_TYPE);
        assertThat(followUp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void revokeAllOtherSessions_keepsCurrent() {
        tokenLogin();
        tokenLogin();
        tokenLogin();
        var current = tokenLogin();
        var currentAccess = (String) current.get("access_token");

        var del = restTemplate.exchange("/api/v1/auth/sessions",
                HttpMethod.DELETE, new HttpEntity<>(bearerHeaders(currentAccess)), Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var sessions = listSessions(currentAccess).getBody();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).get("current")).isEqualTo(true);
    }

    @Test
    void cannotRevokeAnotherUsersSession_returns404() {
        // Tenant A (admin) tries to revoke a session id belonging to Tenant B.
        authHelper.bootstrapSecondTenant(restTemplate);
        var aLogin = tokenLogin();
        var aAccess = (String) aLogin.get("access_token");
        // A session belonging to Tenant 2's admin (created during bootstrap).
        var foreignSessionId = jdbc.queryForObject(
                "SELECT id FROM user_sessions WHERE user_id = (SELECT id FROM users WHERE email = ?) LIMIT 1",
                UUID.class, "it-admin2@wealthview.test");

        var resp = restTemplate.exchange("/api/v1/auth/sessions/" + foreignSessionId,
                HttpMethod.DELETE, new HttpEntity<>(bearerHeaders(aAccess)), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void loginWithDeviceLabel_storesIt() {
        var body = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "device_label", "iPhone 15");
        var resp = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var sessions = listSessions((String) resp.getBody().get("access_token")).getBody();
        assertThat(sessions).anyMatch(s -> "iPhone 15".equals(s.get("device_label")));
    }

    @Test
    void lastUsedAt_throttled_doesntUpdateOnEveryRequest() {
        var login = tokenLogin();
        var access = (String) login.get("access_token");

        // First call to /me primes last_used_at; second call within throttle
        // window must not update it again.
        restTemplate.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(access)), MAP_TYPE);
        var afterFirst = readMostRecentLastUsedAt();

        restTemplate.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(access)), MAP_TYPE);
        var afterSecond = readMostRecentLastUsedAt();

        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    private OffsetDateTime readMostRecentLastUsedAt() {
        return jdbc.queryForObject(
                "SELECT last_used_at FROM user_sessions WHERE user_id = ? ORDER BY created_at DESC LIMIT 1",
                OffsetDateTime.class, authHelper.adminUserId());
    }

    private UUID otherSessionId(List<Map<String, Object>> sessions) {
        for (var row : sessions) {
            if (!Boolean.TRUE.equals(row.get("current"))) {
                return UUID.fromString((String) row.get("id"));
            }
        }
        throw new IllegalStateException("No non-current session found");
    }

    private UUID currentSessionId(List<Map<String, Object>> sessions) {
        for (var row : sessions) {
            if (Boolean.TRUE.equals(row.get("current"))) {
                return UUID.fromString((String) row.get("id"));
            }
        }
        throw new IllegalStateException("No current session found");
    }

    private Map<String, Object> tokenLogin() {
        var resp = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                        jsonHeaders()),
                MAP_TYPE);
        return resp.getBody();
    }

    private org.springframework.http.ResponseEntity<List<Map<String, Object>>> listSessions(String accessToken) {
        return restTemplate.exchange("/api/v1/auth/sessions",
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(accessToken)), LIST_TYPE);
    }

    private HttpHeaders jsonHeaders() {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders bearerHeaders(String accessToken) {
        var h = new HttpHeaders();
        h.setBearerAuth(accessToken);
        return h;
    }
}
