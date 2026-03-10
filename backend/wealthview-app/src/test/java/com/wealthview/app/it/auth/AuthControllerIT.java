package com.wealthview.app.it.auth;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.Map;

import static com.wealthview.app.it.testutil.TestDataHelper.LIST_MAP_TYPE;
import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerIT extends AbstractApiIntegrationTest {

    @Test
    void register_withValidInviteCode_returns201WithTokens() {
        var inviteCode = authHelper.createInviteCode();
        var body = Map.of(
                "email", "newuser@test.com",
                "password", "password123",
                "invite_code", inviteCode
        );

        var response = restTemplate.exchange("/api/v1/auth/register",
                HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKeys("access_token", "refresh_token", "user_id", "tenant_id");
        assertThat(response.getBody().get("email")).isEqualTo("newuser@test.com");
        assertThat(response.getBody().get("role")).isEqualTo("member");
    }

    @Test
    void register_withExpiredInviteCode_returns400() {
        var expiredCode = authHelper.createExpiredInviteCode();
        var body = Map.of(
                "email", "expired@test.com",
                "password", "password123",
                "invite_code", expiredCode
        );

        var response = restTemplate.exchange("/api/v1/auth/register",
                HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_withDuplicateEmail_returns409() {
        var inviteCode = authHelper.createInviteCode();
        var body = Map.of(
                "email", "newuser2@test.com",
                "password", "password123",
                "invite_code", inviteCode
        );
        restTemplate.exchange("/api/v1/auth/register",
                HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), MAP_TYPE);

        var inviteCode2 = authHelper.createInviteCode();
        var body2 = Map.of(
                "email", "newuser2@test.com",
                "password", "password456",
                "invite_code", inviteCode2
        );

        var response = restTemplate.exchange("/api/v1/auth/register",
                HttpMethod.POST, new HttpEntity<>(body2, jsonHeaders()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void login_withCorrectCredentials_returnsTokens() {
        var body = Map.of(
                "email", "it-admin@wealthview.test",
                "password", "testpass123"
        );

        var response = restTemplate.exchange("/api/v1/auth/login",
                HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("access_token", "refresh_token");
        assertThat(response.getBody().get("email")).isEqualTo("it-admin@wealthview.test");
    }

    @Test
    void login_withWrongPassword_returns401() {
        var body = Map.of(
                "email", "it-admin@wealthview.test",
                "password", "wrongpassword"
        );

        var response = restTemplate.exchange("/api/v1/auth/login",
                HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createInviteCode_asAdmin_returns201() {
        var response = restTemplate.exchange("/api/v1/tenant/invite-codes",
                HttpMethod.POST, authHelper.authEntity(null, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("code");
    }

    @Test
    void listInviteCodes_asAdmin_returnsAll() {
        authHelper.createInviteCode();

        var response = restTemplate.exchange("/api/v1/tenant/invite-codes",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    private HttpHeaders jsonHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
