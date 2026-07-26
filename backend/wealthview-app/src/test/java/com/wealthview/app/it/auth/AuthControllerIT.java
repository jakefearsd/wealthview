package com.wealthview.app.it.auth;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerIT extends AbstractApiIntegrationTest {

    @Test
    void register_withValidInviteCode_returns201WithTokens() {
        var inviteCode = authHelper.createInviteCode();
        var body = Map.of(
                "email", "newuser@test.com",
                "password", "mytestpass",
                "invite_code", inviteCode
        );

        var response = api.postAnonForEntity("/api/v1/auth/register", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKeys("user_id", "tenant_id");
        assertThat(response.getBody()).doesNotContainKeys("access_token", "refresh_token");
        assertThat(response.getBody().get("email")).isEqualTo("newuser@test.com");
        assertThat(response.getBody().get("role")).isEqualTo("member");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anyMatch(c -> c.startsWith("access_token=") && c.contains("HttpOnly"));
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anyMatch(c -> c.startsWith("refresh_token=") && c.contains("HttpOnly"));
    }

    @Test
    void register_withExpiredInviteCode_returns400() {
        var expiredCode = authHelper.createExpiredInviteCode();
        var body = Map.of(
                "email", "expired@test.com",
                "password", "mytestpass",
                "invite_code", expiredCode
        );

        var response = api.postAnonForEntity("/api/v1/auth/register", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_withDuplicateEmail_returns409() {
        var inviteCode = authHelper.createInviteCode();
        var body = Map.of(
                "email", "newuser2@test.com",
                "password", "mytestpass",
                "invite_code", inviteCode
        );
        api.postAnonForEntity("/api/v1/auth/register", body);

        var inviteCode2 = authHelper.createInviteCode();
        var body2 = Map.of(
                "email", "newuser2@test.com",
                "password", "mytestpass",
                "invite_code", inviteCode2
        );

        var response = api.postAnonForEntity("/api/v1/auth/register", body2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void login_withCorrectCredentials_returnsTokens() {
        var body = Map.of(
                "email", "it-admin@wealthview.test",
                "password", "testpass123"
        );

        var response = api.postAnonForEntity("/api/v1/auth/login", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContainKeys("access_token", "refresh_token");
        assertThat(response.getBody().get("email")).isEqualTo("it-admin@wealthview.test");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anyMatch(c -> c.startsWith("access_token=") && c.contains("HttpOnly")
                        && c.contains("SameSite=Strict"));
    }

    @Test
    void login_withWrongPassword_returns401() {
        var body = Map.of(
                "email", "it-admin@wealthview.test",
                "password", "wrongpassword"
        );

        var response = api.postAnonForEntity("/api/v1/auth/login", body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createInviteCode_asAdmin_returns201() {
        var response = api.postForEntity("/api/v1/tenant/invite-codes", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("code");
    }

    @Test
    void listInviteCodes_asAdmin_returnsAll() {
        authHelper.createInviteCode();

        var response = api.getListForEntity("/api/v1/tenant/invite-codes");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }
}
