package com.wealthview.core.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.wealthview.core.auth.dto.AuthResult;
import com.wealthview.core.common.Entities;
import com.wealthview.persistence.entity.RefreshTokenEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.entity.UserSessionEntity;
import com.wealthview.persistence.repository.RefreshTokenRepository;
import com.wealthview.persistence.repository.UserRepository;
import com.wealthview.persistence.repository.UserSessionRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Token lifecycle collaborator for {@link AuthService}: mints access/refresh
 * pairs (creating or reusing a {@code user_sessions} row), and runs the refresh
 * rotation flow with reuse-detection, generation, revocation and expiry checks.
 *
 * <p>Extracted from {@code AuthService} as a behavior-preserving decomposition.
 * Every security check (JTI reuse detection, stale-generation rejection,
 * revoked/expired token rejection, optimistic-lock race handling) moved here
 * unchanged. {@code AuthService} retains the {@code @Transactional} boundaries;
 * this helper runs inside the orchestrator's transaction. The reuse-detection
 * generation bump still runs in a {@code REQUIRES_NEW} transaction so it commits
 * even when the surrounding method throws.
 */
@Service
class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private static final UUID NIL_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserSessionRepository userSessionRepository;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTx;

    TokenService(UserRepository userRepository,
                 JwtTokenProvider jwtTokenProvider,
                 RefreshTokenRepository refreshTokenRepository,
                 UserSessionRepository userSessionRepository,
                 MeterRegistry meterRegistry,
                 PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userSessionRepository = userSessionRepository;
        this.meterRegistry = meterRegistry;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Mint a fresh access/refresh pair, persist the refresh JTI, and ensure a
     * user_sessions row exists for this device. {@code existingSessionId} is
     * non-null on the refresh path: refreshing rotates tokens within an
     * existing session, never creates a new one.
     */
    AuthResult issueTokens(UserEntity user, AuthRequestContext context, UUID existingSessionId) {
        var role = user.isSuperAdmin() ? "super_admin" : user.getRole();

        UUID sessionId = existingSessionId;
        if (sessionId == null) {
            var session = userSessionRepository.save(new UserSessionEntity(
                    user.getTenantId(), user.getId(), context.deviceLabel(),
                    context.transport(), context.ipAddress(), context.userAgent()));
            sessionId = session.getId();
            meterRegistry.counter("wealthview.auth.session_created",
                    "transport", context.transport()).increment();
        }

        var accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getTenantId(), role, user.getEmail(),
                user.getTokenGeneration(), sessionId);
        var refreshJti = UUID.randomUUID();
        var refreshToken = jwtTokenProvider.generateRefreshToken(
                user.getId(), user.getTokenGeneration(), refreshJti);
        var now = OffsetDateTime.now();
        var expiresAt = now.plus(java.time.Duration.ofMillis(jwtTokenProvider.getRefreshTokenExpirationMs()));
        refreshTokenRepository.save(new RefreshTokenEntity(
                user.getTenantId(), user.getId(), sessionId, refreshJti, now, expiresAt));

        return new AuthResult(
                accessToken,
                refreshToken,
                user.getId(),
                user.getTenantId(),
                user.getEmail(),
                role
        );
    }

    /**
     * Validate and rotate a refresh token. Throws {@link BadCredentialsException}
     * for every rejection reason; on success rotates the pair, marks the old JTI
     * used and bumps the user's token generation. Behavior preserved verbatim
     * from the original {@code AuthService.refresh}.
     */
    AuthResult refresh(String refreshToken, AuthRequestContext context) {
        var transport = context.transport();
        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
            log.warn("Token refresh failed: invalid or non-refresh token");
            meterRegistry.counter("wealthview.auth.refresh",
                    "result", "failure", "reason", "invalid_token", "transport", transport).increment();
            throw new BadCredentialsException("Invalid refresh token");
        }

        var userId = jwtTokenProvider.extractUserId(refreshToken);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    meterRegistry.counter("wealthview.auth.refresh",
                            "result", "failure", "reason", "unknown_user", "transport", transport).increment();
                    return Entities.notFound("User").get();
                });

        if (!user.isActive()) {
            log.warn("Token refresh failed: user {} is disabled", userId);
            meterRegistry.counter("wealthview.auth.refresh",
                    "result", "failure", "reason", "disabled_user", "transport", transport).increment();
            throw new BadCredentialsException("Account is disabled");
        }

        if (!user.getTenant().isActive()) {
            log.warn("Token refresh failed: tenant {} is disabled for user {}", user.getTenantId(), userId);
            meterRegistry.counter("wealthview.auth.refresh",
                    "result", "failure", "reason", "disabled_tenant", "transport", transport).increment();
            throw new BadCredentialsException("Account disabled — contact your administrator");
        }

        // Order matters: JTI checks come BEFORE the generation check so that
        // reuse detection can fire even when an attacker is replaying an old
        // (already-rotated, generation-stale) refresh token. Without this
        // ordering, the second submission of an old token returns
        // stale_generation and we never realize that two parties have held
        // the same JTI — i.e. that a leak occurred.
        var jti = jwtTokenProvider.extractJti(refreshToken);
        if (jti == null) {
            log.warn("Token refresh failed: refresh token missing JTI for user {}", userId);
            meterRegistry.counter("wealthview.auth.refresh",
                    "result", "failure", "reason", "unknown_jti", "transport", transport).increment();
            throw new BadCredentialsException("Refresh token has been revoked");
        }
        var stored = refreshTokenRepository.findByJti(jti).orElse(null);
        if (stored == null) {
            log.warn("Token refresh failed: unknown JTI {} for user {}", jti, userId);
            meterRegistry.counter("wealthview.auth.refresh",
                    "result", "failure", "reason", "unknown_jti", "transport", transport).increment();
            throw new BadCredentialsException("Refresh token has been revoked");
        }
        if (stored.getUsedAt() != null) {
            // Reuse of an already-consumed refresh token: treat as compromise.
            // Run the bump in a NEW transaction so it commits even when this
            // method throws BadCredentialsException — otherwise the rollback
            // would leave the legitimate replacement valid alongside the
            // attacker's reused token.
            log.warn("REUSE DETECTED: refresh token JTI {} reused for user {}; revoking all tokens",
                    jti, userId);
            requiresNewTx.executeWithoutResult(status -> {
                var u = userRepository.findById(userId).orElseThrow();
                u.setTokenGeneration(u.getTokenGeneration() + 1);
                u.setUpdatedAt(OffsetDateTime.now());
                userRepository.save(u);
                refreshTokenRepository.revokeAllForUser(userId, OffsetDateTime.now());
            });
            meterRegistry.counter("wealthview.auth.refresh_reuse_detected",
                    "transport", transport).increment();
            meterRegistry.counter("wealthview.auth.refresh",
                    "result", "failure", "reason", "refresh_reuse_detected",
                    "transport", transport).increment();
            throw new BadCredentialsException("Refresh token has been revoked");
        }
        if (stored.getRevokedAt() != null) {
            log.warn("Token refresh failed: revoked JTI {} for user {}", jti, userId);
            meterRegistry.counter("wealthview.auth.refresh",
                    "result", "failure", "reason", "revoked", "transport", transport).increment();
            throw new BadCredentialsException("Refresh token has been revoked");
        }
        if (stored.getExpiresAt().isBefore(OffsetDateTime.now())) {
            log.warn("Token refresh failed: expired JTI {} for user {}", jti, userId);
            meterRegistry.counter("wealthview.auth.refresh",
                    "result", "failure", "reason", "expired", "transport", transport).increment();
            throw new BadCredentialsException("Refresh token has been revoked");
        }

        var tokenGeneration = jwtTokenProvider.extractGeneration(refreshToken);
        if (tokenGeneration != user.getTokenGeneration()) {
            log.warn("Token refresh failed: stale generation for user {} (token={}, current={})",
                    userId, tokenGeneration, user.getTokenGeneration());
            meterRegistry.counter("wealthview.auth.refresh",
                    "result", "failure", "reason", "stale_generation", "transport", transport).increment();
            throw new BadCredentialsException("Refresh token has been revoked");
        }

        user.setTokenGeneration(user.getTokenGeneration() + 1);
        user.setUpdatedAt(OffsetDateTime.now());
        try {
            userRepository.save(user);
        } catch (ObjectOptimisticLockingFailureException e) {
            // Concurrent refresh from the same client: another worker bumped
            // the token generation first. We treat that as token-revoked and
            // tell the client to log in again. Log without the stack — this
            // is a known, recoverable race, not a bug.
            log.warn("Token refresh lost race for user {} (concurrent refresh): {}",
                    userId, e.getMessage());
            meterRegistry.counter("wealthview.auth.refresh",
                    "result", "failure", "reason", "race_lost", "transport", transport).increment();
            throw new BadCredentialsException("Refresh token has been revoked", e);
        }

        // Refresh keeps the device's session id stable: rotating a refresh
        // token should NOT spawn a new user_sessions row. The session id was
        // recorded against the original refresh row at login time.
        var sessionId = stored.getSessionId();
        var result = issueTokens(user, context, sessionId);
        var newJti = jwtTokenProvider.extractJti(result.refreshToken());
        var now = OffsetDateTime.now();
        stored.setUsedAt(now);
        stored.setReplacedByJti(newJti);
        stored.setUpdatedAt(now);
        refreshTokenRepository.save(stored);

        meterRegistry.counter("wealthview.auth.refresh",
                "result", "success", "reason", "ok", "transport", transport).increment();
        return result;
    }

    /**
     * Revoke every refresh token and active session for the user, bumping the
     * token generation so outstanding access tokens stop validating. Behavior
     * preserved verbatim from the original {@code AuthService.logout}.
     */
    void revokeAllTokens(UUID userId, String transport) {
        var user = userRepository.findById(userId)
                .orElseThrow(Entities.notFound("User"));
        user.setTokenGeneration(user.getTokenGeneration() + 1);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        var now = OffsetDateTime.now();
        refreshTokenRepository.revokeAllForUser(userId, now);
        // Revoke every active session row too, even though token_generation
        // already invalidates them. Keeping the rows in sync makes the GET
        // /sessions endpoint reflect reality without an additional join.
        userSessionRepository.revokeAllExcept(userId, NIL_UUID, now);
        meterRegistry.counter("wealthview.auth.logout", "transport", transport).increment();
        log.info("User {} logged out (token generation incremented)", userId);
    }
}
