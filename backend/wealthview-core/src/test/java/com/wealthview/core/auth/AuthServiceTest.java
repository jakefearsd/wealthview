package com.wealthview.core.auth;

import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.RegisterRequest;
import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.InvalidInviteCodeException;
import com.wealthview.core.testutil.TestEntityHelper;
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    private JwtTokenProvider jwtTokenProvider;
    private AuthService authService;

    private TenantEntity tenant;
    private UserEntity user;

    private static final String TEST_IP = "127.0.0.1";

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(
                "test-secret-key-that-is-at-least-32-characters-long",
                3600000, 86400000);
        authService = new AuthService(userRepository, inviteCodeRepository,
                passwordEncoder, jwtTokenProvider, eventPublisher, loginActivityService,
                new SimpleMeterRegistry(), new LoginAttemptService(), new CommonPasswordChecker());

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
        when(userRepository.existsByEmail("exists@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("exists@example.com", "mytestpass", "CODE")))
                .isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void register_expiredInvite_throwsInvalidInviteCode() {
        var expired = new InviteCodeEntity(tenant, "EXPIRED", user,
                OffsetDateTime.now().minusDays(1));

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
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

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
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

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(inviteCodeRepository.findByCode("REVOKED")).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("new@example.com", "mytestpass", "REVOKED")))
                .isInstanceOf(InvalidInviteCodeException.class)
                .hasMessageContaining("Invalid or expired invite code");
    }

    @Test
    void refresh_validToken_returnsNewAuthResponse() {
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), 0);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = authService.refresh(refreshToken);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
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
    void refresh_incrementsGeneration() {
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), 0);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.refresh(refreshToken);

        assertThat(user.getTokenGeneration()).isEqualTo(1);
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
                new SimpleMeterRegistry(), attemptService, new CommonPasswordChecker());

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
}
