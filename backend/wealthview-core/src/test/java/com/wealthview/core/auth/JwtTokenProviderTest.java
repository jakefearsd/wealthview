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
        var token = tokenProvider.generateRefreshToken(userId, 0);

        assertThat(token).isNotBlank();
        assertThat(tokenProvider.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    void validateAccessToken_withAccessToken_returnsTrue() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com");

        assertThat(tokenProvider.validateAccessToken(token)).isTrue();
    }

    @Test
    void validateAccessToken_withRefreshToken_returnsFalse() {
        var token = tokenProvider.generateRefreshToken(userId, 0);

        assertThat(tokenProvider.validateAccessToken(token)).isFalse();
    }

    @Test
    void validateRefreshToken_withRefreshToken_returnsTrue() {
        var token = tokenProvider.generateRefreshToken(userId, 0);

        assertThat(tokenProvider.validateRefreshToken(token)).isTrue();
    }

    @Test
    void validateRefreshToken_withAccessToken_returnsFalse() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com");

        assertThat(tokenProvider.validateRefreshToken(token)).isFalse();
    }

    @Test
    void extractTokenType_accessToken_returnsAccess() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com");

        assertThat(tokenProvider.extractTokenType(token)).isEqualTo("access");
    }

    @Test
    void extractTokenType_refreshToken_returnsRefresh() {
        var token = tokenProvider.generateRefreshToken(userId, 0);

        assertThat(tokenProvider.extractTokenType(token)).isEqualTo("refresh");
    }

    @Test
    void extractGeneration_refreshToken_returnsGeneration() {
        var token = tokenProvider.generateRefreshToken(userId, 5);

        assertThat(tokenProvider.extractGeneration(token)).isEqualTo(5);
    }

    @Test
    void extractGeneration_accessTokenWithoutExplicitGeneration_returnsZero() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com");

        assertThat(tokenProvider.extractGeneration(token)).isEqualTo(0);
    }

    @Test
    void generateAccessToken_withGeneration_embedsGenerationClaim() {
        // Access tokens must carry a generation claim so the auth filter can
        // reject tokens issued before a password reset or explicit logout.
        // Without this, a leaked access token is usable until it expires (15m).
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com", 7);

        assertThat(tokenProvider.extractGeneration(token)).isEqualTo(7);
    }

    @Test
    void generateAccessToken_withGeneration_preservesOtherClaims() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com", 3);

        assertThat(tokenProvider.extractUserId(token)).isEqualTo(userId);
        assertThat(tokenProvider.extractTenantId(token)).isEqualTo(tenantId);
        assertThat(tokenProvider.extractRole(token)).isEqualTo("admin");
        assertThat(tokenProvider.extractEmail(token)).isEqualTo("test@example.com");
        assertThat(tokenProvider.extractTokenType(token)).isEqualTo("access");
    }

    @Test
    void validateToken_tokenIssuedByDifferentIssuer_returnsFalse() {
        var otherProvider = new JwtTokenProvider(
                "test-secret-key-that-is-at-least-32-characters-long",
                3600000, 86400000, "other-issuer", "wealthview-web");
        var foreignToken = otherProvider.generateAccessToken(userId, tenantId, "admin", "x@y.z");

        assertThat(tokenProvider.validateToken(foreignToken)).isFalse();
    }

    @Test
    void validateToken_tokenForDifferentAudience_returnsFalse() {
        var otherProvider = new JwtTokenProvider(
                "test-secret-key-that-is-at-least-32-characters-long",
                3600000, 86400000, "wealthview-api", "some-other-service");
        var foreignToken = otherProvider.generateAccessToken(userId, tenantId, "admin", "x@y.z");

        assertThat(tokenProvider.validateToken(foreignToken)).isFalse();
    }

    @Test
    void validateToken_tokenExpiredWithinClockSkew_returnsTrue() {
        // Token with expiry 30 seconds in the past should still validate because
        // clock skew tolerance is 60 seconds.
        var shortLivedProvider = new JwtTokenProvider(
                "test-secret-key-that-is-at-least-32-characters-long",
                -30_000, 86400000);
        var alreadyExpired = shortLivedProvider.generateAccessToken(userId, tenantId, "admin", "x@y.z");

        assertThat(shortLivedProvider.validateToken(alreadyExpired)).isTrue();
    }

    @Test
    void validateToken_tokenExpiredBeyondClockSkew_returnsFalse() {
        var longStaleProvider = new JwtTokenProvider(
                "test-secret-key-that-is-at-least-32-characters-long",
                -120_000, 86400000);
        var staleToken = longStaleProvider.generateAccessToken(userId, tenantId, "admin", "x@y.z");

        assertThat(longStaleProvider.validateToken(staleToken)).isFalse();
    }
}
