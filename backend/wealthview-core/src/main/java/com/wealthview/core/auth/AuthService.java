package com.wealthview.core.auth;

import com.wealthview.core.audit.AuditEvent;
import com.wealthview.core.auth.dto.AuthResult;
import com.wealthview.core.auth.dto.LoginOutcome;
import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.RegisterRequest;
import com.wealthview.core.auth.mfa.MfaService;
import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.common.Entities;
import com.wealthview.core.exception.InvalidInviteCodeException;
import com.wealthview.persistence.entity.MfaChallengeEntity;
import com.wealthview.persistence.entity.RefreshTokenEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.entity.UserSessionEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.MfaChallengeRepository;
import com.wealthview.persistence.repository.RefreshTokenRepository;
import com.wealthview.persistence.repository.UserRepository;
import com.wealthview.persistence.repository.UserSessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // BCrypt hash of "dummy-password-for-timing-equalization" used only to make
    // the unknown-email path spend the same ~250 ms BCrypt budget as the
    // known-email path. Prevents user enumeration via login response timing.
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoO.TfL4QhV0Q.mGvKfpcEsE3NZ4Q1UE9.";

    private final UserRepository userRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final LoginActivityService loginActivityService;
    private final MeterRegistry meterRegistry;
    private final LoginAttemptService loginAttemptService;
    private final CommonPasswordChecker commonPasswordChecker;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserSessionRepository userSessionRepository;
    private final MfaChallengeRepository mfaChallengeRepository;
    private final MfaService mfaService;
    private final TransactionTemplate requiresNewTx;

    private static final long MFA_CHALLENGE_TTL_MS = 5 * 60 * 1000L;

    public AuthService(UserRepository userRepository,
                       InviteCodeRepository inviteCodeRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       ApplicationEventPublisher eventPublisher,
                       LoginActivityService loginActivityService,
                       MeterRegistry meterRegistry,
                       LoginAttemptService loginAttemptService,
                       CommonPasswordChecker commonPasswordChecker,
                       RefreshTokenRepository refreshTokenRepository,
                       UserSessionRepository userSessionRepository,
                       MfaChallengeRepository mfaChallengeRepository,
                       MfaService mfaService,
                       PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.eventPublisher = eventPublisher;
        this.loginActivityService = loginActivityService;
        this.meterRegistry = meterRegistry;
        this.loginAttemptService = loginAttemptService;
        this.commonPasswordChecker = commonPasswordChecker;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userSessionRepository = userSessionRepository;
        this.mfaChallengeRepository = mfaChallengeRepository;
        this.mfaService = mfaService;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public AuthResult login(LoginRequest request, String ipAddress) {
        return login(request, AuthRequestContext.cookie(ipAddress));
    }

    @Transactional
    public AuthResult login(LoginRequest request, String ipAddress, String transport) {
        return login(request, new AuthRequestContext(transport, ipAddress, null, null));
    }

    @Transactional
    public AuthResult login(LoginRequest request, AuthRequestContext context) {
        var outcome = loginInitiate(request, context);
        if (outcome instanceof LoginOutcome.Tokens tokens) {
            return tokens.result();
        }
        // Legacy callers (older direct calls / older tests) called login()
        // for accounts that ARE NOT MFA-enabled. If MFA is now required they
        // must use loginInitiate() and the challenge flow instead.
        throw new BadCredentialsException("Account requires MFA — use loginInitiate");
    }

    @Transactional
    public LoginOutcome loginInitiate(LoginRequest request, AuthRequestContext context) {
        var transport = context.transport();
        var ipAddress = context.ipAddress();
        if (loginAttemptService.isBlocked(request.email())) {
            meterRegistry.counter("wealthview.auth.login",
                    "result", "failure", "reason", "account_locked", "transport", transport).increment();
            throw new BadCredentialsException("Account temporarily locked due to too many failed attempts");
        }

        var userOpt = userRepository.findByEmail(request.email());
        if (userOpt.isEmpty()) {
            // Burn the same BCrypt budget as a real check so response timing
            // does not reveal whether the email exists.
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            log.warn("Login failed: unknown email");
            loginActivityService.record(request.email(), null, false, ipAddress);
            meterRegistry.counter("wealthview.auth.login",
                    "result", "failure", "reason", "unknown_email", "transport", transport).increment();
            loginAttemptService.recordFailure(request.email());
            throw new BadCredentialsException("Invalid email or password");
        }
        var user = userOpt.get();

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Login failed: wrong password for user {}", user.getId());
            loginActivityService.record(request.email(), user.getTenantId(), false, ipAddress);
            meterRegistry.counter("wealthview.auth.login",
                    "result", "failure", "reason", "wrong_password", "transport", transport).increment();
            loginAttemptService.recordFailure(request.email());
            throw new BadCredentialsException("Invalid email or password");
        }

        if (!user.isActive()) {
            log.warn("Login failed: user {} is disabled", user.getId());
            loginActivityService.record(request.email(), user.getTenantId(), false, ipAddress);
            meterRegistry.counter("wealthview.auth.login",
                    "result", "failure", "reason", "disabled_user", "transport", transport).increment();
            loginAttemptService.recordFailure(request.email());
            throw new BadCredentialsException("Account is disabled");
        }

        if (!user.getTenant().isActive()) {
            log.warn("Login failed: tenant {} disabled for user {}", user.getTenantId(), user.getId());
            loginActivityService.record(request.email(), user.getTenantId(), false, ipAddress);
            meterRegistry.counter("wealthview.auth.login",
                    "result", "failure", "reason", "disabled_tenant", "transport", transport).increment();
            loginAttemptService.recordFailure(request.email());
            throw new BadCredentialsException("Account disabled — contact your administrator");
        }

        loginAttemptService.recordSuccess(request.email());

        if (user.isMfaEnabled()) {
            // Don't issue access/refresh tokens yet. The caller must clear
            // the MFA challenge first by POSTing the totp/recovery code along
            // with the short-lived mfa_token JWT we hand back here.
            var jti = UUID.randomUUID();
            var mfaToken = jwtTokenProvider.generateMfaChallenge(
                    user.getId(), transport, jti, MFA_CHALLENGE_TTL_MS);
            mfaChallengeRepository.save(new MfaChallengeEntity(
                    user.getTenantId(), user.getId(), jti, transport,
                    OffsetDateTime.now().plus(java.time.Duration.ofMillis(MFA_CHALLENGE_TTL_MS))));
            meterRegistry.counter("wealthview.auth.login",
                    "result", "mfa_required", "reason", "ok", "transport", transport).increment();
            log.info("MFA challenge issued for user {}", user.getId());
            return new LoginOutcome.MfaRequired(mfaToken);
        }

        return new LoginOutcome.Tokens(completeLoginSuccess(user, context, ipAddress));
    }

    private AuthResult completeLoginSuccess(UserEntity user, AuthRequestContext context, String ipAddress) {
        loginActivityService.record(user.getEmail(), user.getTenantId(), true, ipAddress);
        meterRegistry.counter("wealthview.auth.login",
                "result", "success", "reason", "ok", "transport", context.transport()).increment();
        log.info("User {} logged in for tenant {}", user.getId(), user.getTenantId());
        eventPublisher.publishEvent(new AuditEvent(user.getTenantId(), user.getId(), "LOGIN", "user",
                user.getId(), Map.of("email", user.getEmail())));
        return buildAuthResult(user, context);
    }

    @Transactional
    public AuthResult completeMfaChallenge(String mfaToken, String totpCode, String recoveryCode,
                                           AuthRequestContext context) {
        var transport = context.transport();
        if (!jwtTokenProvider.validateMfaChallenge(mfaToken)) {
            log.warn("MFA challenge failed: invalid token");
            meterRegistry.counter("wealthview.mfa.failed", "scope", "challenge_token").increment();
            throw new BadCredentialsException("Invalid MFA challenge");
        }
        var jti = jwtTokenProvider.extractJti(mfaToken);
        var stored = mfaChallengeRepository.findByJti(jti).orElse(null);
        if (stored == null || stored.getUsedAt() != null
                || stored.getExpiresAt().isBefore(OffsetDateTime.now())) {
            log.warn("MFA challenge failed: jti {} not found / used / expired", jti);
            meterRegistry.counter("wealthview.mfa.failed", "scope", "challenge_token").increment();
            throw new BadCredentialsException("Invalid MFA challenge");
        }
        var userId = jwtTokenProvider.extractUserId(mfaToken);
        if (!stored.getUserId().equals(userId)) {
            log.warn("MFA challenge failed: jti {} bound to user {} but token says {}",
                    jti, stored.getUserId(), userId);
            meterRegistry.counter("wealthview.mfa.failed", "scope", "challenge_token").increment();
            throw new BadCredentialsException("Invalid MFA challenge");
        }
        boolean codeOk;
        if (totpCode != null && !totpCode.isBlank()) {
            codeOk = mfaService.verifyTotp(userId, totpCode);
        } else if (recoveryCode != null && !recoveryCode.isBlank()) {
            codeOk = mfaService.consumeRecoveryCode(userId, recoveryCode);
        } else {
            throw new BadCredentialsException("Missing TOTP or recovery code");
        }
        if (!codeOk) {
            throw new BadCredentialsException("Invalid MFA code");
        }
        stored.setUsedAt(OffsetDateTime.now());
        mfaChallengeRepository.save(stored);

        var user = userRepository.findById(userId).orElseThrow(Entities.notFound("User"));
        return completeLoginSuccess(user, context, context.ipAddress());
    }

    @Transactional
    public AuthResult register(RegisterRequest request) {
        return register(request, AuthRequestContext.cookie(null));
    }

    @Transactional
    public AuthResult register(RegisterRequest request, String transport) {
        return register(request, new AuthRequestContext(transport, null, null, null));
    }

    @Transactional
    public AuthResult register(RegisterRequest request, AuthRequestContext context) {
        var transport = context.transport();
        if (commonPasswordChecker.isCommon(request.password())) {
            throw new IllegalArgumentException("This password is too common and easily guessed. Please choose a different password.");
        }

        // Invite validation must run before the email-existence check: otherwise
        // an attacker can enumerate registered emails by spraying arbitrary
        // invite codes and distinguishing DuplicateEntityException (email known)
        // from InvalidInviteCodeException (email unknown) via status or timing.
        var inviteCode = inviteCodeRepository.findByCode(request.inviteCode())
                .orElseThrow(() -> {
                    log.warn("Registration failed: invalid invite code");
                    meterRegistry.counter("wealthview.auth.registration",
                            "result", "failure", "reason", "invalid_invite", "transport", transport).increment();
                    return new InvalidInviteCodeException("Invalid or expired invite code");
                });

        if (inviteCode.isConsumed() || inviteCode.isRevoked() || inviteCode.isExpired()) {
            log.warn("Registration failed: invite code unusable (consumed={}, revoked={}, expired={})",
                    inviteCode.isConsumed(), inviteCode.isRevoked(), inviteCode.isExpired());
            meterRegistry.counter("wealthview.auth.registration",
                    "result", "failure", "reason", "invalid_invite", "transport", transport).increment();
            throw new InvalidInviteCodeException("Invalid or expired invite code");
        }

        if (userRepository.existsByEmail(request.email())) {
            log.warn("Registration failed: duplicate email");
            meterRegistry.counter("wealthview.auth.registration",
                    "result", "failure", "reason", "duplicate_email", "transport", transport).increment();
            throw new DuplicateEntityException("Email already registered");
        }

        var user = new UserEntity(
                inviteCode.getTenant(),
                request.email(),
                passwordEncoder.encode(request.password()),
                "member"
        );
        user = userRepository.save(user);

        inviteCode.setConsumedBy(user);
        inviteCode.setConsumedAt(OffsetDateTime.now());
        inviteCodeRepository.save(inviteCode);

        meterRegistry.counter("wealthview.auth.registration",
                "result", "success", "transport", transport).increment();
        log.info("User {} registered for tenant {}", user.getId(), user.getTenantId());
        eventPublisher.publishEvent(new AuditEvent(user.getTenantId(), user.getId(), "REGISTER", "user",
                user.getId(), Map.of("email", request.email())));
        return buildAuthResult(user, context);
    }

    @Transactional
    public AuthResult refresh(String refreshToken) {
        return refresh(refreshToken, AuthRequestContext.cookie(null));
    }

    @Transactional
    public AuthResult refresh(String refreshToken, String transport) {
        return refresh(refreshToken, new AuthRequestContext(transport, null, null, null));
    }

    @Transactional
    public AuthResult refresh(String refreshToken, AuthRequestContext context) {
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
        var result = buildAuthResult(user, context, sessionId);
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

    @Transactional
    public void logout(UUID userId) {
        logout(userId, "cookie");
    }

    @Transactional
    public void logout(UUID userId, String transport) {
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
        userSessionRepository.revokeAllExcept(userId, java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"), now);
        meterRegistry.counter("wealthview.auth.logout", "transport", transport).increment();
        log.info("User {} logged out (token generation incremented)", userId);
    }

    private AuthResult buildAuthResult(UserEntity user, AuthRequestContext context) {
        return buildAuthResult(user, context, null);
    }

    /**
     * Mint a fresh access/refresh pair, persist the refresh JTI, and ensure a
     * user_sessions row exists for this device. {@code existingSessionId} is
     * non-null on the refresh path: refreshing rotates tokens within an
     * existing session, never creates a new one.
     */
    private AuthResult buildAuthResult(UserEntity user, AuthRequestContext context, UUID existingSessionId) {
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
}
