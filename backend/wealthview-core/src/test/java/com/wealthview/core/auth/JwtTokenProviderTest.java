package com.wealthview.core.auth;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void generateRefreshToken_setsRandomJti_extractableViaExtractJti() {
        var token = tokenProvider.generateRefreshToken(userId, 0);

        var jti = tokenProvider.extractJti(token);
        assertThat(jti).isNotNull();
    }

    @Test
    void generateRefreshToken_distinctCalls_produceDistinctJtis() {
        var t1 = tokenProvider.generateRefreshToken(userId, 0);
        var t2 = tokenProvider.generateRefreshToken(userId, 0);

        assertThat(tokenProvider.extractJti(t1)).isNotEqualTo(tokenProvider.extractJti(t2));
    }

    @Test
    void generateRefreshToken_withExplicitJti_embedsThatJti() {
        var explicit = UUID.randomUUID();
        var token = tokenProvider.generateRefreshToken(userId, 0, explicit);

        assertThat(tokenProvider.extractJti(token)).isEqualTo(explicit);
    }

    @Test
    void generateAccessToken_withSessionId_embedsSidClaim() {
        var sid = UUID.randomUUID();
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin",
                "test@example.com", 0, sid);

        assertThat(tokenProvider.extractSessionId(token)).isEqualTo(sid);
    }

    @Test
    void extractSessionId_tokenWithoutSid_returnsNull() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com");

        assertThat(tokenProvider.extractSessionId(token)).isNull();
    }

    @Test
    void extractJti_accessTokenWithoutJti_returnsNull() {
        var token = tokenProvider.generateAccessToken(userId, tenantId, "admin", "test@example.com");

        assertThat(tokenProvider.extractJti(token)).isNull();
    }

    @Test
    void validateToken_tokenExpiredBeyondClockSkew_returnsFalse() {
        var longStaleProvider = new JwtTokenProvider(
                "test-secret-key-that-is-at-least-32-characters-long",
                -120_000, 86400000);
        var staleToken = longStaleProvider.generateAccessToken(userId, tenantId, "admin", "x@y.z");

        assertThat(longStaleProvider.validateToken(staleToken)).isFalse();
    }

    // --- MFA challenge token --------------------------------------------------

    @Test
    void generateMfaChallenge_returnsNonEmptyTokenCarryingJtiAndUser() {
        var jti = UUID.randomUUID();

        var token = tokenProvider.generateMfaChallenge(userId, "cookie", jti, 60_000L);

        assertThat(token).isNotBlank();
        assertThat(tokenProvider.extractJti(token)).isEqualTo(jti);
        assertThat(tokenProvider.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    void generateMfaChallenge_tokenTypeIsMfaChallenge() {
        var token = tokenProvider.generateMfaChallenge(userId, "cookie", UUID.randomUUID(), 60_000L);

        assertThat(tokenProvider.extractTokenType(token)).isEqualTo("mfa_challenge");
    }

    @Test
    void validateMfaChallenge_withMfaChallengeToken_returnsTrue() {
        var token = tokenProvider.generateMfaChallenge(userId, "cookie", UUID.randomUUID(), 60_000L);

        assertThat(tokenProvider.validateMfaChallenge(token)).isTrue();
    }

    @Test
    void validateMfaChallenge_withAccessToken_returnsFalse() {
        // An access token must NOT pass MFA-challenge validation, otherwise a
        // caller could skip the challenge step by replaying their access token.
        var accessToken = tokenProvider.generateAccessToken(userId, tenantId, "admin", "x@y.z");

        assertThat(tokenProvider.validateMfaChallenge(accessToken)).isFalse();
    }

    @Test
    void validateMfaChallenge_withRefreshToken_returnsFalse() {
        var refreshToken = tokenProvider.generateRefreshToken(userId, 0);

        assertThat(tokenProvider.validateMfaChallenge(refreshToken)).isFalse();
    }

    @Test
    void validateMfaChallenge_withGarbageToken_returnsFalse() {
        assertThat(tokenProvider.validateMfaChallenge("not-a-token")).isFalse();
    }

    @Test
    void validateMfaChallenge_withExpiredChallengeToken_returnsFalse() {
        var staleProvider = new JwtTokenProvider(
                "test-secret-key-that-is-at-least-32-characters-long", 3600000, 86400000);
        var expired = staleProvider.generateMfaChallenge(userId, "cookie", UUID.randomUUID(), -120_000L);

        assertThat(staleProvider.validateMfaChallenge(expired)).isFalse();
    }

    @Test
    void validateAccessToken_withMfaChallengeToken_returnsTrue() {
        // validateAccessToken only rejects refresh tokens; an mfa_challenge
        // token is not a refresh token so it passes the type check. This pins
        // the exact predicate (reject only "refresh"), not "accept only access".
        var token = tokenProvider.generateMfaChallenge(userId, "cookie", UUID.randomUUID(), 60_000L);

        assertThat(tokenProvider.validateAccessToken(token)).isTrue();
    }

    @Test
    void validateAccessToken_withGarbageToken_returnsFalse() {
        assertThat(tokenProvider.validateAccessToken("garbage.token")).isFalse();
    }
}
