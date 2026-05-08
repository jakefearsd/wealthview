package com.wealthview.core.auth.dto;

import com.wealthview.core.tenant.dto.PasswordResetRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Records auto-generate a toString that prints every component, which would
 * dump plaintext passwords / tokens if any code path ever logged the
 * object. Each of these DTOs overrides toString to redact sensitive fields;
 * if those overrides are removed, this test fails before a regression can
 * ship.
 */
class AuthDtoRedactionTest {

    private static final String SECRET_PASSWORD = "hunter2-must-not-leak";
    private static final String SECRET_TOKEN = "eyJ.fake.jwt.access";

    @Test
    void loginRequest_toString_redactsPassword() {
        var request = new LoginRequest("user@example.com", SECRET_PASSWORD);

        assertThat(request.toString())
                .doesNotContain(SECRET_PASSWORD)
                .contains("password=***")
                .contains("user@example.com");
    }

    @Test
    void registerRequest_toString_redactsPasswordAndInviteCode() {
        var request = new RegisterRequest("user@example.com", SECRET_PASSWORD, "INVITE-XYZ");

        assertThat(request.toString())
                .doesNotContain(SECRET_PASSWORD)
                .doesNotContain("INVITE-XYZ")
                .contains("password=***")
                .contains("inviteCode=***");
    }

    @Test
    void passwordResetRequest_toString_redactsPassword() {
        var request = new PasswordResetRequest(SECRET_PASSWORD);

        assertThat(request.toString())
                .doesNotContain(SECRET_PASSWORD)
                .contains("newPassword=***");
    }

    @Test
    void authResult_toString_redactsBothTokens() {
        var refresh = "eyJ.fake.jwt.refresh";
        var result = new AuthResult(SECRET_TOKEN, refresh,
                UUID.randomUUID(), UUID.randomUUID(), "user@example.com", "member");

        assertThat(result.toString())
                .doesNotContain(SECRET_TOKEN)
                .doesNotContain(refresh)
                .contains("accessToken=***")
                .contains("refreshToken=***")
                .contains("user@example.com");
    }
}
