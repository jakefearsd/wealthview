package com.wealthview.core.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider tokenProvider;
    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(
                "test-secret-key-that-is-at-least-32-characters-long",
                3600000,
                86400000
        );
    }

    @Test
    void generateAccessToken_validInputs_returnsNonEmptyToken() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com");

        assertThat(token).isNotBlank();
    }

    @Test
    void extractUserId_validToken_returnsUserId() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com");

        assertThat(tokenProvider.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    void extractTenantId_validToken_returnsTenantId() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com");

        assertThat(tokenProvider.extractTenantId(token)).isEqualTo(tenantId);
    }

    @Test
    void extractRole_validToken_returnsRole() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "member", "test@example.com");

        assertThat(tokenProvider.extractRole(token)).isEqualTo("member");
    }

    @Test
    void extractEmail_validToken_returnsEmail() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "user@test.com");

        assertThat(tokenProvider.extractEmail(token)).isEqualTo("user@test.com");
    }

    @Test
    void isTokenExpired_freshToken_returnsFalse() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com");

        assertThat(tokenProvider.isTokenExpired(token)).isFalse();
    }

    @Test
    void validateToken_validToken_returnsTrue() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com");

        assertThat(tokenProvider.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_invalidToken_returnsFalse() {
        assertThat(tokenProvider.validateToken("invalid.token.here")).isFalse();
    }

    @Test
    void generateRefreshToken_validInput_returnsToken() {
        var token = tokenProvider.generateRefreshToken(userId);

        assertThat(token).isNotBlank();
        assertThat(tokenProvider.extractUserId(token)).isEqualTo(userId);
    }
}
