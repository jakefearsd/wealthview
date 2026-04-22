package com.wealthview.core.auth;

import com.wealthview.core.audit.AuditEvent;
import com.wealthview.core.auth.dto.AuthResponse;
import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.RegisterRequest;
import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidInviteCodeException;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public AuthService(UserRepository userRepository,
                       InviteCodeRepository inviteCodeRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       ApplicationEventPublisher eventPublisher,
                       LoginActivityService loginActivityService,
                       MeterRegistry meterRegistry,
                       LoginAttemptService loginAttemptService,
                       CommonPasswordChecker commonPasswordChecker) {
        this.userRepository = userRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.eventPublisher = eventPublisher;
        this.loginActivityService = loginActivityService;
        this.meterRegistry = meterRegistry;
        this.loginAttemptService = loginAttemptService;
        this.commonPasswordChecker = commonPasswordChecker;
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress) {
        if (loginAttemptService.isBlocked(request.email())) {
            meterRegistry.counter("wealthview.auth.login", "result", "failure", "reason", "account_locked").increment();
            throw new BadCredentialsException("Account temporarily locked due to too many failed attempts");
        }

        var userOpt = userRepository.findByEmail(request.email());
        if (userOpt.isEmpty()) {
            // Burn the same BCrypt budget as a real check so response timing
            // does not reveal whether the email exists.
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            log.warn("Login failed: unknown email");
            loginActivityService.record(request.email(), null, false, ipAddress);
            meterRegistry.counter("wealthview.auth.login", "result", "failure", "reason", "unknown_email").increment();
            loginAttemptService.recordFailure(request.email());
            throw new BadCredentialsException("Invalid email or password");
        }
        var user = userOpt.get();

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Login failed: wrong password for user {}", user.getId());
            loginActivityService.record(request.email(), user.getTenantId(), false, ipAddress);
            meterRegistry.counter("wealthview.auth.login", "result", "failure", "reason", "wrong_password").increment();
            loginAttemptService.recordFailure(request.email());
            throw new BadCredentialsException("Invalid email or password");
        }

        if (!user.isActive()) {
            log.warn("Login failed: user {} is disabled", user.getId());
            loginActivityService.record(request.email(), user.getTenantId(), false, ipAddress);
            meterRegistry.counter("wealthview.auth.login", "result", "failure", "reason", "disabled_user").increment();
            loginAttemptService.recordFailure(request.email());
            throw new BadCredentialsException("Account is disabled");
        }

        if (!user.getTenant().isActive()) {
            log.warn("Login failed: tenant {} disabled for user {}", user.getTenantId(), user.getId());
            loginActivityService.record(request.email(), user.getTenantId(), false, ipAddress);
            meterRegistry.counter("wealthview.auth.login", "result", "failure", "reason", "disabled_tenant").increment();
            loginAttemptService.recordFailure(request.email());
            throw new BadCredentialsException("Account disabled — contact your administrator");
        }

        loginAttemptService.recordSuccess(request.email());
        loginActivityService.record(request.email(), user.getTenantId(), true, ipAddress);
        meterRegistry.counter("wealthview.auth.login", "result", "success").increment();
        log.info("User {} logged in for tenant {}", user.getId(), user.getTenantId());
        eventPublisher.publishEvent(new AuditEvent(user.getTenantId(), user.getId(), "LOGIN", "user",
                user.getId(), Map.of("email", user.getEmail())));
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (commonPasswordChecker.isCommon(request.password())) {
            throw new IllegalArgumentException("This password is too common and easily guessed. Please choose a different password.");
        }

        if (userRepository.existsByEmail(request.email())) {
            log.warn("Registration failed: duplicate email");
            meterRegistry.counter("wealthview.auth.registration", "result", "failure", "reason", "duplicate_email").increment();
            throw new DuplicateEntityException("Email already registered");
        }

        var inviteCode = inviteCodeRepository.findByCode(request.inviteCode())
                .orElseThrow(() -> {
                    log.warn("Registration failed: invalid invite code");
                    meterRegistry.counter("wealthview.auth.registration", "result", "failure", "reason", "invalid_invite").increment();
                    return new InvalidInviteCodeException("Invalid or expired invite code");
                });

        if (inviteCode.isConsumed() || inviteCode.isRevoked() || inviteCode.isExpired()) {
            log.warn("Registration failed: invite code unusable (consumed={}, revoked={}, expired={})",
                    inviteCode.isConsumed(), inviteCode.isRevoked(), inviteCode.isExpired());
            meterRegistry.counter("wealthview.auth.registration", "result", "failure", "reason", "invalid_invite").increment();
            throw new InvalidInviteCodeException("Invalid or expired invite code");
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

        meterRegistry.counter("wealthview.auth.registration", "result", "success").increment();
        log.info("User {} registered for tenant {}", user.getId(), user.getTenantId());
        eventPublisher.publishEvent(new AuditEvent(user.getTenantId(), user.getId(), "REGISTER", "user",
                user.getId(), Map.of("email", request.email())));
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refresh(String refreshToken) {
        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
            log.warn("Token refresh failed: invalid or non-refresh token");
            throw new BadCredentialsException("Invalid refresh token");
        }

        var userId = jwtTokenProvider.extractUserId(refreshToken);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        if (!user.isActive()) {
            log.warn("Token refresh failed: user {} is disabled", userId);
            throw new BadCredentialsException("Account is disabled");
        }

        if (!user.getTenant().isActive()) {
            log.warn("Token refresh failed: tenant {} is disabled for user {}", user.getTenantId(), userId);
            throw new BadCredentialsException("Account disabled — contact your administrator");
        }

        var tokenGeneration = jwtTokenProvider.extractGeneration(refreshToken);
        if (tokenGeneration != user.getTokenGeneration()) {
            log.warn("Token refresh failed: stale generation for user {} (token={}, current={})",
                    userId, tokenGeneration, user.getTokenGeneration());
            throw new BadCredentialsException("Refresh token has been revoked");
        }

        user.setTokenGeneration(user.getTokenGeneration() + 1);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);

        return buildAuthResponse(user);
    }

    @Transactional
    public void logout(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        user.setTokenGeneration(user.getTokenGeneration() + 1);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        log.info("User {} logged out (token generation incremented)", userId);
    }

    private AuthResponse buildAuthResponse(UserEntity user) {
        var role = user.isSuperAdmin() ? "super_admin" : user.getRole();
        var accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getTenantId(), role, user.getEmail(), user.getTokenGeneration());
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getTokenGeneration());

        return new AuthResponse(
                accessToken,
                refreshToken,
                user.getId(),
                user.getTenantId(),
                user.getEmail(),
                role
        );
    }
}
