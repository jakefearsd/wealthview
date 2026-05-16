package com.wealthview.core.auth;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.RegisterRequest;
import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.InvalidInviteCodeException;
import com.wealthview.core.testutil.TestEntityHelper;
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.MfaChallengeRepository;
import com.wealthview.persistence.repository.RefreshTokenRepository;
import com.wealthview.persistence.repository.UserRepository;
import com.wealthview.persistence.repository.UserSessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private InviteCodeRepository inviteCodeRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private LoginActivityService loginActivityService;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserSessionRepository userSessionRepository;

    @Mock
    private MfaChallengeRepository mfaChallengeRepository;

    @Mock
    private com.wealthview.core.auth.mfa.MfaService mfaService;

    private final org.springframework.transaction.PlatformTransactionManager transactionManager =
            new org.springframework.transaction.PlatformTransactionManager() {
                @Override
                public org.springframework.transaction.TransactionStatus getTransaction(
                        org.springframework.transaction.TransactionDefinition def) {
                    return new org.springframework.transaction.support.SimpleTransactionStatus();
                }
                @Override
                public void commit(org.springframework.transaction.TransactionStatus status) {}
                @Override
                public void rollback(org.springframework.transaction.TransactionStatus status) {}
            };

    private JwtTokenProvider jwtTokenProvider;
    private AuthService authService;
    private SimpleMeterRegistry meterRegistry;

    private TenantEntity tenant;
    private UserEntity user;

    private static final String TEST_IP = "127.0.0.1";

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(
                "test-secret-key-that-is-at-least-32-characters-long",
                3600000, 86400000);
        meterRegistry = new SimpleMeterRegistry();
        authService = new AuthService(userRepository, inviteCodeRepository,
                passwordEncoder, jwtTokenProvider, eventPublisher, loginActivityService,
                meterRegistry, new LoginAttemptService(), new CommonPasswordChecker(),
                refreshTokenRepository, userSessionRepository, mfaChallengeRepository, mfaService,
                transactionManager);
        // Default mocking: when AuthService asks for a new session row, give it
        // back something with an id set so the access-token sid claim is populated.
        org.mockito.Mockito.lenient().when(userSessionRepository.save(any(com.wealthview.persistence.entity.UserSessionEntity.class)))
                .thenAnswer(inv -> {
                    var s = (com.wealthview.persistence.entity.UserSessionEntity) inv.getArgument(0);
                    com.wealthview.core.testutil.TestEntityHelper.setId(s, java.util.UUID.randomUUID());
                    return s;
                });

        tenant = new TenantEntity("Test Tenant");
        TestEntityHelper.setId(tenant, UUID.randomUUID());
        user = new UserEntity(tenant, "test@example.com", "encoded", "admin");
        TestEntityHelper.setId(user, UUID.randomUUID());
    }

    @Test
    void login_validCredentials_returnsAuthResponse() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);

        var response = authService.login(new LoginRequest("test@example.com", "password"), TEST_IP);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.email()).isEqualTo("test@example.com");
        verify(loginActivityService).record("test@example.com", user.getTenantId(), true, TEST_IP);
    }

    @Test
    void login_accessTokenEmbedsUsersCurrentTokenGeneration() {
        // The access token's generation claim is what lets the auth filter
        // detect revoked sessions. If the token is minted with generation=0
        // while the user sits at generation=5, every request will be rejected.
        user.setTokenGeneration(5);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);

        var response = authService.login(new LoginRequest("test@example.com", "password"), TEST_IP);

        assertThat(jwtTokenProvider.extractGeneration(response.accessToken())).isEqualTo(5);
    }

    @Test
    void login_badPassword_throwsBadCredentialsAndRecordsFailure() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("test@example.com", "wrong"), TEST_IP))
                .isInstanceOf(BadCredentialsException.class);

        verify(loginActivityService).record("test@example.com", user.getTenantId(), false, TEST_IP);
    }

    @Test
    void login_unknownEmail_throwsBadCredentialsAndRecordsFailure() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nobody@example.com", "pass"), TEST_IP))
                .isInstanceOf(BadCredentialsException.class);

        verify(loginActivityService).record(eq("nobody@example.com"), isNull(), eq(false), eq(TEST_IP));
    }

    @Test
    void login_unknownEmail_runsPasswordCheckToPreventTimingEnumeration() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nobody@example.com", "pass"), TEST_IP))
                .isInstanceOf(BadCredentialsException.class);

        // A BCrypt comparison must run even when the email is unknown, otherwise
        // an attacker can enumerate registered users by measuring response time.
        verify(passwordEncoder).matches(eq("pass"), anyString());
    }

    @Test
    void login_unknownEmail_errorMessageMatchesBadPassword() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        var unknownEmailError = org.assertj.core.api.Assertions.catchThrowable(
                () -> authService.login(new LoginRequest("nobody@example.com", "wrong"), TEST_IP));
        var badPasswordError = org.assertj.core.api.Assertions.catchThrowable(
                () -> authService.login(new LoginRequest("test@example.com", "wrong"), TEST_IP));

        assertThat(unknownEmailError).isInstanceOf(BadCredentialsException.class);
        assertThat(badPasswordError).isInstanceOf(BadCredentialsException.class);
        assertThat(unknownEmailError.getMessage()).isEqualTo(badPasswordError.getMessage());
    }

    @Test
    void login_disabledUser_throwsBadCredentials() {
        user.setActive(false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("test@example.com", "password"), TEST_IP))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Account is disabled");

        verify(loginActivityService).record("test@example.com", user.getTenantId(), false, TEST_IP);
    }

    @Test
    void login_disabledTenant_throwsBadCredentials() {
        tenant.setActive(false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("test@example.com", "password"), TEST_IP))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("disabled");

        verify(loginActivityService).record("test@example.com", user.getTenantId(), false, TEST_IP);
    }

    @Test
    void register_validInvite_createsUserAndReturnsAuth() {
        var invite = new InviteCodeEntity(tenant, "VALID", user,
                OffsetDateTime.now().plusDays(7));

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(inviteCodeRepository.findByCode("VALID")).thenReturn(Optional.of(invite));
        when(passwordEncoder.encode("mytestpass")).thenReturn("encoded");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity saved = inv.getArgument(0);
            TestEntityHelper.setId(saved, UUID.randomUUID());
            return saved;
        });
        when(inviteCodeRepository.save(any(InviteCodeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = authService.register(new RegisterRequest("new@example.com", "mytestpass", "VALID"));

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.role()).isEqualTo("member");
    }

    @Test
    void register_duplicateEmail_throwsDuplicateEntity() {
        var invite = new InviteCodeEntity(tenant, "CODE", user,
                OffsetDateTime.now().plusDays(7));
        when(inviteCodeRepository.findByCode("CODE")).thenReturn(Optional.of(invite));
        when(userRepository.existsByEmail("exists@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("exists@example.com", "mytestpass", "CODE")))
                .isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void register_invalidInviteCode_throwsBeforeEmailCheck() {
        // Invite validation must happen before the email-existence lookup so a
        // caller cannot enumerate registered emails by spraying arbitrary invite
        // codes and watching for DuplicateEntity vs InvalidInviteCode responses.
        when(inviteCodeRepository.findByCode("BOGUS")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("any@example.com", "mytestpass", "BOGUS")))
                .isInstanceOf(InvalidInviteCodeException.class);

        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    void register_expiredInvite_throwsInvalidInviteCode() {
        var expired = new InviteCodeEntity(tenant, "EXPIRED", user,
                OffsetDateTime.now().minusDays(1));

        when(inviteCodeRepository.findByCode("EXPIRED")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("new@example.com", "mytestpass", "EXPIRED")))
                .isInstanceOf(InvalidInviteCodeException.class)
                .hasMessageContaining("Invalid or expired invite code");
    }

    @Test
    void register_consumedInvite_throwsInvalidInviteCode() {
        var consumed = new InviteCodeEntity(tenant, "USED", user,
                OffsetDateTime.now().plusDays(7));
        consumed.setConsumedBy(user);
        consumed.setConsumedAt(OffsetDateTime.now());

        when(inviteCodeRepository.findByCode("USED")).thenReturn(Optional.of(consumed));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("new@example.com", "mytestpass", "USED")))
                .isInstanceOf(InvalidInviteCodeException.class)
                .hasMessageContaining("Invalid or expired invite code");
    }

    @Test
    void register_revokedInvite_throwsInvalidInviteCode() {
        var revoked = new InviteCodeEntity(tenant, "REVOKED", user,
                OffsetDateTime.now().plusDays(7));
        revoked.setRevoked(true);

        when(inviteCodeRepository.findByCode("REVOKED")).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("new@example.com", "mytestpass", "REVOKED")))
                .isInstanceOf(InvalidInviteCodeException.class)
                .hasMessageContaining("Invalid or expired invite code");
    }

    @Test
    void refresh_validToken_returnsNewAuthResponse() {
        var refreshToken = trackedRefreshToken(0);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = authService.refresh(refreshToken);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
    }

    private String trackedRefreshToken(int generation) {
        var jti = UUID.randomUUID();
        var token = jwtTokenProvider.generateRefreshToken(user.getId(), generation, jti);
        var stored = new com.wealthview.persistence.entity.RefreshTokenEntity(
                tenant.getId(), user.getId(), jti,
                OffsetDateTime.now(), OffsetDateTime.now().plusDays(1));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(Optional.of(stored));
        return token;
    }

    @Test
    void refresh_invalidToken_throwsBadCredentials() {
        assertThatThrownBy(() -> authService.refresh("invalid.token"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void refresh_withAccessTokenInsteadOfRefresh_throwsBadCredentials() {
        var accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), tenant.getId(), "admin", "test@example.com");

        assertThatThrownBy(() -> authService.refresh(accessToken))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void refresh_withDisabledUser_throwsBadCredentials() {
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), 0);
        user.setActive(false);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void refresh_withDisabledTenant_throwsBadCredentials() {
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), 0);
        tenant.setActive(false);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void refresh_staleGeneration_throwsBadCredentials() {
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), 0);
        user.setTokenGeneration(1);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void refresh_concurrentRefreshRacesToSave_losingCallerThrowsBadCredentials() {
        // Two requests with the same valid refresh token can race on the generation bump.
        // The first to save wins; the second must surface as a revoked-session error rather
        // than bubble up a raw OptimisticLockingFailureException to the HTTP layer.
        var refreshToken = trackedRefreshToken(0);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(UserEntity.class, user.getId()));

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void refresh_incrementsGeneration() {
        var refreshToken = trackedRefreshToken(0);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.refresh(refreshToken);

        assertThat(user.getTokenGeneration()).isEqualTo(1);
    }

    @Test
    void refresh_unknownJti_throwsBadCredentialsAndCountsUnknownJti() {
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), 0);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        // Note: trackedRefreshToken NOT called, so findByJti returns Optional.empty()

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("revoked");

        var counter = meterRegistry.find("wealthview.auth.refresh")
                .tag("reason", "unknown_jti").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void refresh_reusedJti_bumpsGenerationAndCountsReuseDetected() {
        var jti = UUID.randomUUID();
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), 0, jti);
        var alreadyUsed = new com.wealthview.persistence.entity.RefreshTokenEntity(
                tenant.getId(), user.getId(), jti,
                OffsetDateTime.now().minusMinutes(10), OffsetDateTime.now().plusDays(1));
        alreadyUsed.setUsedAt(OffsetDateTime.now().minusMinutes(5));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(Optional.of(alreadyUsed));

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(user.getTokenGeneration()).isEqualTo(1);
        var reuseCounter = meterRegistry.find("wealthview.auth.refresh_reuse_detected").counter();
        assertThat(reuseCounter).isNotNull();
        assertThat(reuseCounter.count()).isEqualTo(1.0);
    }

    @Test
    void refresh_revokedJti_throwsBadCredentialsAndCountsRevoked() {
        var jti = UUID.randomUUID();
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), 0, jti);
        var revoked = new com.wealthview.persistence.entity.RefreshTokenEntity(
                tenant.getId(), user.getId(), jti,
                OffsetDateTime.now().minusMinutes(10), OffsetDateTime.now().plusDays(1));
        revoked.setRevokedAt(OffsetDateTime.now().minusMinutes(1));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BadCredentialsException.class);

        var counter = meterRegistry.find("wealthview.auth.refresh")
                .tag("reason", "revoked").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void refresh_dbExpiredJti_throwsBadCredentialsAndCountsExpired() {
        var jti = UUID.randomUUID();
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), 0, jti);
        var dbExpired = new com.wealthview.persistence.entity.RefreshTokenEntity(
                tenant.getId(), user.getId(), jti,
                OffsetDateTime.now().minusDays(2), OffsetDateTime.now().minusHours(1));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(Optional.of(dbExpired));

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BadCredentialsException.class);

        var counter = meterRegistry.find("wealthview.auth.refresh")
                .tag("reason", "expired").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void refresh_marksOldJtiUsedAndStoresReplacement() {
        var jti = UUID.randomUUID();
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), 0, jti);
        var stored = new com.wealthview.persistence.entity.RefreshTokenEntity(
                tenant.getId(), user.getId(), jti,
                OffsetDateTime.now(), OffsetDateTime.now().plusDays(1));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.refresh(refreshToken);

        assertThat(stored.getUsedAt()).isNotNull();
        assertThat(stored.getReplacedByJti()).isNotNull();
    }

    @Test
    void logout_incrementsTokenGeneration() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.logout(user.getId());

        assertThat(user.getTokenGeneration()).isEqualTo(1);
        verify(userRepository).save(user);
    }

    @Test
    void login_accountLocked_throwsBadCredentials() {
        var attemptService = new LoginAttemptService();
        for (int i = 0; i < 5; i++) {
            attemptService.recordFailure("test@example.com");
        }
        var lockedAuthService = new AuthService(userRepository, inviteCodeRepository,
                passwordEncoder, jwtTokenProvider, eventPublisher, loginActivityService,
                new SimpleMeterRegistry(), attemptService, new CommonPasswordChecker(),
                refreshTokenRepository, userSessionRepository, mfaChallengeRepository, mfaService,
                transactionManager);

        assertThatThrownBy(() -> lockedAuthService.login(
                new LoginRequest("test@example.com", "password"), "127.0.0.1"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("temporarily locked");
    }

    @Test
    void register_commonPassword_throwsIllegalArgument() {
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("new@example.com", "password123", "CODE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too common");
    }

    @Test
    void register_uncommonPassword_succeeds() {
        var inviteCode = new InviteCodeEntity(tenant, "VALID", user,
                OffsetDateTime.now().plusDays(7));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(inviteCodeRepository.findByCode("VALID")).thenReturn(Optional.of(inviteCode));
        when(passwordEncoder.encode("myuniquephrase")).thenReturn("encoded");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity saved = inv.getArgument(0);
            TestEntityHelper.setId(saved, UUID.randomUUID());
            return saved;
        });
        when(inviteCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = authService.register(
                new RegisterRequest("new@example.com", "myuniquephrase", "VALID"));

        assertThat(response.accessToken()).isNotBlank();
    }

    @Test
    void refresh_validToken_recordsSuccessMetric() {
        var refreshToken = trackedRefreshToken(0);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.refresh(refreshToken);

        var counter = meterRegistry.find("wealthview.auth.refresh")
                .tag("result", "success").tag("reason", "ok").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void refresh_invalidToken_recordsFailureMetric() {
        assertThatThrownBy(() -> authService.refresh("invalid.token"))
                .isInstanceOf(BadCredentialsException.class);

        var counter = meterRegistry.find("wealthview.auth.refresh")
                .tag("result", "failure").tag("reason", "invalid_token").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void logout_recordsCounter() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.logout(user.getId());

        var counter = meterRegistry.find("wealthview.auth.logout").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // --- MFA-required login branch + completeMfaChallenge -------------------

    @Test
    void loginInitiate_mfaEnabledUser_returnsMfaRequiredWithoutTokens() {
        // An MFA-enabled user must NOT receive access/refresh tokens directly;
        // loginInitiate must hand back an MfaRequired challenge instead.
        user.setMfaEnabled(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);

        var outcome = authService.loginInitiate(
                new LoginRequest("test@example.com", "password"),
                AuthRequestContext.cookie(TEST_IP));

        assertThat(outcome).isInstanceOf(com.wealthview.core.auth.dto.LoginOutcome.MfaRequired.class);
        var challenge = (com.wealthview.core.auth.dto.LoginOutcome.MfaRequired) outcome;
        assertThat(challenge.mfaToken()).isNotBlank();
        verify(mfaChallengeRepository).save(any(com.wealthview.persistence.entity.MfaChallengeEntity.class));
    }

    @Test
    void login_mfaEnabledUser_throwsBecauseLegacyEntryPointCannotIssueTokens() {
        // The legacy login() entry point cannot complete an MFA login — it must
        // reject rather than silently hand back tokens.
        user.setMfaEnabled(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("test@example.com", "password"), TEST_IP))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("MFA");
    }

    @Test
    void completeMfaChallenge_invalidMfaToken_throwsBadCredentials() {
        assertThatThrownBy(() -> authService.completeMfaChallenge(
                "not-a-real-token", "123456", null, AuthRequestContext.cookie(TEST_IP)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid MFA challenge");
    }

    @Test
    void completeMfaChallenge_jtiNotFound_throwsBadCredentials() {
        var jti = UUID.randomUUID();
        var mfaToken = jwtTokenProvider.generateMfaChallenge(user.getId(), "cookie", jti, 60_000L);
        when(mfaChallengeRepository.findByJti(jti)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.completeMfaChallenge(
                mfaToken, "123456", null, AuthRequestContext.cookie(TEST_IP)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid MFA challenge");
    }

    @Test
    void completeMfaChallenge_alreadyUsedChallenge_throwsBadCredentials() {
        // A challenge whose used_at is already set must be rejected: it is
        // single-use, so replay must fail.
        var jti = UUID.randomUUID();
        var mfaToken = jwtTokenProvider.generateMfaChallenge(user.getId(), "cookie", jti, 60_000L);
        var stored = new com.wealthview.persistence.entity.MfaChallengeEntity(
                tenant.getId(), user.getId(), jti, "cookie", OffsetDateTime.now().plusMinutes(5));
        stored.setUsedAt(OffsetDateTime.now().minusSeconds(10));
        when(mfaChallengeRepository.findByJti(jti)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.completeMfaChallenge(
                mfaToken, "123456", null, AuthRequestContext.cookie(TEST_IP)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid MFA challenge");
    }

    @Test
    void completeMfaChallenge_expiredChallenge_throwsBadCredentials() {
        var jti = UUID.randomUUID();
        var mfaToken = jwtTokenProvider.generateMfaChallenge(user.getId(), "cookie", jti, 60_000L);
        var stored = new com.wealthview.persistence.entity.MfaChallengeEntity(
                tenant.getId(), user.getId(), jti, "cookie", OffsetDateTime.now().minusSeconds(1));
        when(mfaChallengeRepository.findByJti(jti)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.completeMfaChallenge(
                mfaToken, "123456", null, AuthRequestContext.cookie(TEST_IP)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid MFA challenge");
    }

    @Test
    void completeMfaChallenge_challengeBoundToDifferentUser_throwsBadCredentials() {
        // The stored challenge's user_id must match the user_id in the JWT;
        // a mismatch must be rejected even when the token itself is valid.
        var jti = UUID.randomUUID();
        var mfaToken = jwtTokenProvider.generateMfaChallenge(user.getId(), "cookie", jti, 60_000L);
        var stored = new com.wealthview.persistence.entity.MfaChallengeEntity(
                tenant.getId(), UUID.randomUUID(), jti, "cookie", OffsetDateTime.now().plusMinutes(5));
        when(mfaChallengeRepository.findByJti(jti)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.completeMfaChallenge(
                mfaToken, "123456", null, AuthRequestContext.cookie(TEST_IP)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid MFA challenge");
    }

    @Test
    void completeMfaChallenge_noTotpOrRecoveryCode_throwsMissingCode() {
        var jti = UUID.randomUUID();
        var mfaToken = jwtTokenProvider.generateMfaChallenge(user.getId(), "cookie", jti, 60_000L);
        var stored = new com.wealthview.persistence.entity.MfaChallengeEntity(
                tenant.getId(), user.getId(), jti, "cookie", OffsetDateTime.now().plusMinutes(5));
        when(mfaChallengeRepository.findByJti(jti)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.completeMfaChallenge(
                mfaToken, "  ", "  ", AuthRequestContext.cookie(TEST_IP)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Missing TOTP or recovery code");
    }

    @Test
    void completeMfaChallenge_wrongTotpCode_throwsInvalidMfaCode() {
        var jti = UUID.randomUUID();
        var mfaToken = jwtTokenProvider.generateMfaChallenge(user.getId(), "cookie", jti, 60_000L);
        var stored = new com.wealthview.persistence.entity.MfaChallengeEntity(
                tenant.getId(), user.getId(), jti, "cookie", OffsetDateTime.now().plusMinutes(5));
        when(mfaChallengeRepository.findByJti(jti)).thenReturn(Optional.of(stored));
        when(mfaService.verifyTotp(user.getId(), "000000")).thenReturn(false);

        assertThatThrownBy(() -> authService.completeMfaChallenge(
                mfaToken, "000000", null, AuthRequestContext.cookie(TEST_IP)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid MFA code");
    }

    @Test
    void completeMfaChallenge_validTotpCode_marksChallengeUsedAndReturnsTokens() {
        var jti = UUID.randomUUID();
        var mfaToken = jwtTokenProvider.generateMfaChallenge(user.getId(), "cookie", jti, 60_000L);
        var stored = new com.wealthview.persistence.entity.MfaChallengeEntity(
                tenant.getId(), user.getId(), jti, "cookie", OffsetDateTime.now().plusMinutes(5));
        when(mfaChallengeRepository.findByJti(jti)).thenReturn(Optional.of(stored));
        when(mfaService.verifyTotp(user.getId(), "123456")).thenReturn(true);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        var result = authService.completeMfaChallenge(
                mfaToken, "123456", null, AuthRequestContext.cookie(TEST_IP));

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.email()).isEqualTo("test@example.com");
        assertThat(stored.getUsedAt()).isNotNull();
        verify(mfaChallengeRepository).save(stored);
    }

    @Test
    void completeMfaChallenge_validRecoveryCode_consumesCodeAndReturnsTokens() {
        var jti = UUID.randomUUID();
        var mfaToken = jwtTokenProvider.generateMfaChallenge(user.getId(), "cookie", jti, 60_000L);
        var stored = new com.wealthview.persistence.entity.MfaChallengeEntity(
                tenant.getId(), user.getId(), jti, "cookie", OffsetDateTime.now().plusMinutes(5));
        when(mfaChallengeRepository.findByJti(jti)).thenReturn(Optional.of(stored));
        when(mfaService.consumeRecoveryCode(user.getId(), "ABCD1234")).thenReturn(true);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        var result = authService.completeMfaChallenge(
                mfaToken, null, "ABCD1234", AuthRequestContext.cookie(TEST_IP));

        assertThat(result.accessToken()).isNotBlank();
        verify(mfaService).consumeRecoveryCode(user.getId(), "ABCD1234");
        verify(mfaService, never()).verifyTotp(any(), anyString());
    }
}
