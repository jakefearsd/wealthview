package com.wealthview.core.auth.dto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MobileAuthResponseTest {

    @Test
    void toString_redactsAccessAndRefreshTokens() {
        var response = new MobileAuthResponse(
                "real-access-token-secret",
                "real-refresh-token-secret",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user@example.com",
                "member");

        var rendered = response.toString();

        assertThat(rendered).doesNotContain("real-access-token-secret");
        assertThat(rendered).doesNotContain("real-refresh-token-secret");
        assertThat(rendered).contains("accessToken=***");
        assertThat(rendered).contains("refreshToken=***");
        assertThat(rendered).contains("user@example.com");
        assertThat(rendered).contains("member");
    }

    @Test
    void from_authResult_copiesAllFieldsExceptIdentity() {
        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var result = new AuthResult("access-jwt", "refresh-jwt",
                userId, tenantId, "user@example.com", "admin");

        var response = MobileAuthResponse.from(result);

        assertThat(response.accessToken()).isEqualTo("access-jwt");
        assertThat(response.refreshToken()).isEqualTo("refresh-jwt");
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.role()).isEqualTo("admin");
    }
}
