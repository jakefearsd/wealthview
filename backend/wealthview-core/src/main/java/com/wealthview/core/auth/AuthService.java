package com.wealthview.core.auth;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.audit.AuditEvent;
import com.wealthview.core.auth.dto.AuthResult;
import com.wealthview.core.auth.dto.LoginOutcome;
import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.RegisterRequest;
import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.InvalidInviteCodeException;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Authentication orchestrator. Owns the password-credential checks, account
 * lockout, login activity recording and invite-based registration, and the
 * {@code @Transactional} boundaries for the whole auth surface. Delegates the
 * MFA challenge flow to {@link MfaChallengeService} and the token issue/refresh
 * lifecycle to {@link TokenService}, both injected as beans.
 */
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
    private final ApplicationEventPublisher eventPublisher;
    private final LoginActivityService loginActivityService;
    private final MeterRegistry meterRegistry;
    private final LoginAttemptService loginAttemptService;
    private final CommonPasswordChecker commonPasswordChecker;
    private final MfaChallengeService mfaChallengeService;
    private final TokenService tokenService;

    public AuthService(UserRepository userRepository,
                       InviteCodeRepository inviteCodeRepository,
                       PasswordEncoder passwordEncoder,
                       ApplicationEventPublisher eventPublisher,
                       LoginActivityService loginActivityService,
                       MeterRegistry meterRegistry,
                       LoginAttemptService loginAttemptService,
                       CommonPasswordChecker commonPasswordChecker,
                       MfaChallengeService mfaChallengeService,
                       TokenService tokenService) {
        this.userRepository = userRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.loginActivityService = loginActivityService;
        this.meterRegistry = meterRegistry;
        this.loginAttemptService = loginAttemptService;
        this.commonPasswordChecker = commonPasswordChecker;
        this.mfaChallengeService = mfaChallengeService;
        this.tokenService = tokenService;
    }

    @Transactional
    public LoginOutcome loginInitiate(LoginRequest request, AuthRequestContext context) {
        var transport = context.transport();
        var ipAddress = context.ipAddress();
        var user = authenticateCredentials(request, transport, ipAddress);

        loginAttemptService.recordSuccess(request.email());

        if (user.isMfaEnabled()) {
            return new LoginOutcome.MfaRequired(mfaChallengeService.issueChallenge(user, transport));
        }

        return new LoginOutcome.Tokens(completeLoginSuccess(user, context, ipAddress));
    }

    /**
     * Verify the password credential and account/tenant state. Throws
     * {@link BadCredentialsException} for every failure mode (locked, unknown
     * email, wrong password, disabled user, disabled tenant) with the failure
     * recorded to login activity, metrics and the lockout counter.
     */
    private UserEntity authenticateCredentials(LoginRequest request, String transport, String ipAddress) {
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
            recordLoginFailure(request.email(), null, ipAddress, transport, "unknown_email");
            throw new BadCredentialsException("Invalid email or password");
        }
        var user = userOpt.get();

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Login failed: wrong password for user {}", user.getId());
            recordLoginFailure(request.email(), user.getTenantId(), ipAddress, transport, "wrong_password");
            throw new BadCredentialsException("Invalid email or password");
        }

        if (!user.isActive()) {
            log.warn("Login failed: user {} is disabled", user.getId());
            recordLoginFailure(request.email(), user.getTenantId(), ipAddress, transport, "disabled_user");
            throw new BadCredentialsException("Account is disabled");
        }

        if (!user.getTenant().isActive()) {
            log.warn("Login failed: tenant {} disabled for user {}", user.getTenantId(), user.getId());
            recordLoginFailure(request.email(), user.getTenantId(), ipAddress, transport, "disabled_tenant");
            throw new BadCredentialsException("Account disabled — contact your administrator");
        }

        return user;
    }

    private void recordLoginFailure(String email, UUID tenantId, String ipAddress,
                                    String transport, String reason) {
        loginActivityService.record(email, tenantId, false, ipAddress);
        meterRegistry.counter("wealthview.auth.login",
                "result", "failure", "reason", reason, "transport", transport).increment();
        loginAttemptService.recordFailure(email);
    }

    private AuthResult completeLoginSuccess(UserEntity user, AuthRequestContext context, String ipAddress) {
        loginActivityService.record(user.getEmail(), user.getTenantId(), true, ipAddress);
        meterRegistry.counter("wealthview.auth.login",
                "result", "success", "reason", "ok", "transport", context.transport()).increment();
        log.info("User {} logged in for tenant {}", user.getId(), user.getTenantId());
        eventPublisher.publishEvent(new AuditEvent(user.getTenantId(), user.getId(), "LOGIN", "user",
                user.getId(), Map.of("email", user.getEmail())));
        return tokenService.issueTokens(user, context, null);
    }

    @Transactional
    public AuthResult completeMfaChallenge(String mfaToken, String totpCode, String recoveryCode,
                                           AuthRequestContext context) {
        var user = mfaChallengeService.complete(mfaToken, totpCode, recoveryCode);
        return completeLoginSuccess(user, context, context.ipAddress());
    }

    @Transactional
    public AuthResult register(RegisterRequest request, AuthRequestContext context) {
        var transport = context.transport();
        if (commonPasswordChecker.isCommon(request.password())) {
            throw new IllegalArgumentException(
                    "This password is too common and easily guessed. Please choose a different password.");
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
        return tokenService.issueTokens(user, context, null);
    }

    @Transactional
    public AuthResult refresh(String refreshToken, AuthRequestContext context) {
        return tokenService.refresh(refreshToken, context);
    }

    @Transactional
    public void logout(UUID userId) {
        logout(userId, "cookie");
    }

    @Transactional
    public void logout(UUID userId, String transport) {
        tokenService.revokeAllTokens(userId, transport);
    }
}
