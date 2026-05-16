package com.wealthview.core.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public final class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private static final long CLOCK_SKEW_SECONDS = 60;

    private final SecretKey key;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    private final String issuer;
    private final String audience;

    @Autowired
    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiration}") long accessTokenExpiration,
            @Value("${app.jwt.refresh-token-expiration}") long refreshTokenExpiration,
            @Value("${app.jwt.issuer:wealthview-api}") String issuer,
            @Value("${app.jwt.audience:wealthview-web}") String audience) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
        this.issuer = issuer;
        this.audience = audience;
    }

    public JwtTokenProvider(String secret, long accessTokenExpiration, long refreshTokenExpiration) {
        this(secret, accessTokenExpiration, refreshTokenExpiration, "wealthview-api", "wealthview-web");
    }

    public String generateAccessToken(UUID userId, UUID tenantId, String role, String email) {
        return generateAccessToken(userId, tenantId, role, email, 0, null);
    }

    public String generateAccessToken(UUID userId, UUID tenantId, String role, String email, int generation) {
        return generateAccessToken(userId, tenantId, role, email, generation, null);
    }

    public String generateAccessToken(UUID userId, UUID tenantId, String role, String email,
                                      int generation, UUID sessionId) {
        var now = new Date();
        var expiry = new Date(now.getTime() + accessTokenExpiration);

        var builder = Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(userId.toString())
                .claim("type", "access")
                .claim("tenant_id", tenantId.toString())
                .claim("role", role)
                .claim("email", email)
                .claim("generation", generation)
                .issuedAt(now)
                .expiration(expiry);
        if (sessionId != null) {
            builder.claim("sid", sessionId.toString());
        }
        return builder.signWith(key).compact();
    }

    public String generateMfaChallenge(UUID userId, String transport, UUID jti, long ttlMillis) {
        var now = new Date();
        var expiry = new Date(now.getTime() + ttlMillis);
        return Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(userId.toString())
                .id(jti.toString())
                .claim("type", "mfa_challenge")
                .claim("transport", transport)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public boolean validateMfaChallenge(String token) {
        return validateToken(token) && "mfa_challenge".equals(extractTokenType(token));
    }

    public String generateRefreshToken(UUID userId, int generation) {
        return generateRefreshToken(userId, generation, UUID.randomUUID());
    }

    public String generateRefreshToken(UUID userId, int generation, UUID jti) {
        var now = new Date();
        var expiry = new Date(now.getTime() + refreshTokenExpiration);

        return Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(userId.toString())
                .id(jti.toString())
                .claim("type", "refresh")
                .claim("generation", generation)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public long getAccessTokenExpirationMs() {
        return accessTokenExpiration;
    }

    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpiration;
    }

    public int extractGeneration(String token) {
        var gen = getClaims(token).get("generation", Integer.class);
        return gen != null ? gen : 0;
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(getClaims(token).getSubject());
    }

    public UUID extractTenantId(String token) {
        return UUID.fromString(getClaims(token).get("tenant_id", String.class));
    }

    public String extractRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    public String extractEmail(String token) {
        return getClaims(token).get("email", String.class);
    }

    public boolean isTokenExpired(String token) {
        return getClaims(token).getExpiration().toInstant().isBefore(Instant.now());
    }

    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired");
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            // Don't log the stack trace here — these are routine "user sent
            // a bad/expired/garbage cookie" cases, but include the cause
            // class so unusual JWT library failures (e.g. SignatureException
            // vs MalformedJwtException) are still distinguishable.
            log.warn("JWT token invalid: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return false;
        }
    }

    public boolean validateAccessToken(String token) {
        if (!validateToken(token)) {
            return false;
        }
        var type = extractTokenType(token);
        return !"refresh".equals(type);
    }

    public boolean validateRefreshToken(String token) {
        return validateToken(token) && "refresh".equals(extractTokenType(token));
    }

    public String extractTokenType(String token) {
        return getClaims(token).get("type", String.class);
    }

    public UUID extractJti(String token) {
        var raw = getClaims(token).getId();
        return raw == null ? null : UUID.fromString(raw);
    }

    public UUID extractSessionId(String token) {
        var raw = getClaims(token).get("sid", String.class);
        return raw == null ? null : UUID.fromString(raw);
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
