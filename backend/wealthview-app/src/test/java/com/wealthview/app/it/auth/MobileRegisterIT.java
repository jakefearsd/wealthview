package com.wealthview.app.it.auth;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative-path coverage for {@code POST /api/v1/auth/token/register}.
 * Registration is a thin wrapper over {@code AuthService.register}, but the
 * mobile path returns the same envelope shape with the response code mapping
 * documented here.
 *
 * <p>The cookie-path register tests in {@link AuthControllerIT} cover the
 * service layer; these tests verify the mobile transport routes the same
 * exceptions to the same HTTP statuses.
 */
class MobileRegisterIT extends AbstractApiIntegrationTest {

    @Test
    void tokenRegister_withInvalidInviteCode_returns400() {
        var body = Map.of(
                "email", "invalid-invite@test.com",
                "password", "validpass1",
                "invite_code", "NOT-A-REAL-CODE");

        var response = api.postAnonForEntity("/api/v1/auth/token/register", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tokenRegister_withExpiredInviteCode_returns400() {
        var expiredCode = authHelper.createExpiredInviteCode();
        var body = Map.of(
                "email", "expired-invite@test.com",
                "password", "validpass1",
                "invite_code", expiredCode);

        var response = api.postAnonForEntity("/api/v1/auth/token/register", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tokenRegister_withUsedInviteCode_returns400() {
        var inviteCode = authHelper.createInviteCode();
        var firstBody = Map.of(
                "email", "first-user@test.com",
                "password", "validpass1",
                "invite_code", inviteCode);
        var first = api.postAnonForEntity("/api/v1/auth/token/register", firstBody);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Same invite, different email — must reject.
        var secondBody = Map.of(
                "email", "second-user@test.com",
                "password", "validpass1",
                "invite_code", inviteCode);
        var second = api.postAnonForEntity("/api/v1/auth/token/register", secondBody);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tokenRegister_withDuplicateEmail_returns409() {
        var firstInvite = authHelper.createInviteCode();
        var firstBody = Map.of(
                "email", "dup@test.com",
                "password", "validpass1",
                "invite_code", firstInvite);
        var first = api.postAnonForEntity("/api/v1/auth/token/register", firstBody);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var secondInvite = authHelper.createInviteCode();
        var secondBody = Map.of(
                "email", "dup@test.com",
                "password", "validpass1",
                "invite_code", secondInvite);
        var second = api.postAnonForEntity("/api/v1/auth/token/register", secondBody);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void tokenRegister_withWeakPassword_returns400() {
        var inviteCode = authHelper.createInviteCode();
        // "password" is in the common-password list — AuthService rejects it
        // with IllegalArgumentException -> 400.
        var body = Map.of(
                "email", "weak-pass@test.com",
                "password", "password",
                "invite_code", inviteCode);

        var response = api.postAnonForEntity("/api/v1/auth/token/register", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tokenRegister_withMissingEmail_returns400() {
        var inviteCode = authHelper.createInviteCode();
        var body = Map.of(
                "password", "validpass1",
                "invite_code", inviteCode);

        var response = api.postAnonForEntity("/api/v1/auth/token/register", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tokenRegister_withTooShortPassword_returns400() {
        var inviteCode = authHelper.createInviteCode();
        // RegisterRequest @Size(min = 8). Anything shorter is a validation
        // failure short of the service layer.
        var body = Map.of(
                "email", "short-pass@test.com",
                "password", "abc",
                "invite_code", inviteCode);

        var response = api.postAnonForEntity("/api/v1/auth/token/register", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
