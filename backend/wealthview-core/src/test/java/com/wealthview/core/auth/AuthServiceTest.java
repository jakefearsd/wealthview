package com.wealthview.core.auth;

import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.RegisterRequest;
import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.testutil.TestEntityHelper;
import com.wealthview.core.exception.InvalidInviteCodeException;
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private InviteCodeRepository inviteCodeRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private JwtTokenProvider jwtTokenProvider;
    private AuthService authService;

    private TenantEntity tenant;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(
                "test-secret-key-that-is-at-least-32-characters-long",
                3600000, 86400000);
        authService = new AuthService(userRepository, inviteCodeRepository,
                passwordEncoder, jwtTokenProvider);

        tenant = new TenantEntity("Test Tenant");
        TestEntityHelper.setId(tenant, UUID.randomUUID());
        user = new UserEntity(tenant, "test@example.com", "encoded", "admin");
        TestEntityHelper.setId(user, UUID.randomUUID());
    }

    @Test
    void login_validCredentials_returnsAuthResponse() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);

        var response = authService.login(new LoginRequest("test@example.com", "password"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.email()).isEqualTo("test@example.com");
    }

    @Test
    void login_badPassword_throwsBadCredentials() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("test@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_unknownEmail_throwsBadCredentials() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "pass")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void register_validInvite_createsUserAndReturnsAuth() {
        var invite = new InviteCodeEntity(tenant, "VALID", user,
                OffsetDateTime.now().plusDays(7));

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(inviteCodeRepository.findByCode("VALID")).thenReturn(Optional.of(invite));
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity saved = inv.getArgument(0);
            TestEntityHelper.setId(saved, UUID.randomUUID());
            return saved;
        });
        when(inviteCodeRepository.save(any(InviteCodeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = authService.register(new RegisterRequest("new@example.com", "password123", "VALID"));

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.role()).isEqualTo("member");
    }

    @Test
    void register_duplicateEmail_throwsDuplicateEntity() {
        when(userRepository.existsByEmail("exists@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("exists@example.com", "password123", "CODE")))
                .isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void register_expiredInvite_throwsInvalidInviteCode() {
        var expired = new InviteCodeEntity(tenant, "EXPIRED", user,
                OffsetDateTime.now().minusDays(1));

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(inviteCodeRepository.findByCode("EXPIRED")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("new@example.com", "password123", "EXPIRED")))
                .isInstanceOf(InvalidInviteCodeException.class)
                .hasMessageContaining("expired");
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
                new RegisterRequest("new@example.com", "password123", "USED")))
                .isInstanceOf(InvalidInviteCodeException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void login_disabledTenant_throwsBadCredentials() {
        tenant.setActive(false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("test@example.com", "password")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void refresh_validToken_returnsNewAuthResponse() {
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        var response = authService.refresh(refreshToken);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
    }

    @Test
    void refresh_invalidToken_throwsBadCredentials() {
        assertThatThrownBy(() -> authService.refresh("invalid.token"))
                .isInstanceOf(BadCredentialsException.class);
    }
}
