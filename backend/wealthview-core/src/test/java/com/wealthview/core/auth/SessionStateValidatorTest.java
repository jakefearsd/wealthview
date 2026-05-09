package com.wealthview.core.auth;

import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.entity.UserSessionEntity;
import com.wealthview.persistence.repository.UserRepository;
import com.wealthview.persistence.repository.UserSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionStateValidatorTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSessionRepository userSessionRepository;

    @InjectMocks
    private SessionStateValidator validator;

    @Test
    void isSessionValid_userMissing_returnsFalse() {
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(validator.isSessionValid(userId, 0)).isFalse();
    }

    @Test
    void isSessionValid_userInactive_returnsFalse() {
        // Disabled users must not be able to use tokens issued before disabling.
        var tenant = new TenantEntity("Test");
        var user = new UserEntity(tenant, "u@e.com", "hash", "member");
        user.setActive(false);
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(validator.isSessionValid(userId, user.getTokenGeneration())).isFalse();
    }

    @Test
    void isSessionValid_tenantInactive_returnsFalse() {
        // Disabling a tenant must immediately revoke all member sessions.
        var tenant = new TenantEntity("Test");
        tenant.setActive(false);
        var user = new UserEntity(tenant, "u@e.com", "hash", "member");
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(validator.isSessionValid(userId, user.getTokenGeneration())).isFalse();
    }

    @Test
    void isSessionValid_generationStale_returnsFalse() {
        // When logout/password-reset/refresh bumps tokenGeneration, any access
        // token minted before the bump must be rejected.
        var tenant = new TenantEntity("Test");
        var user = new UserEntity(tenant, "u@e.com", "hash", "member");
        user.setTokenGeneration(5);
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(validator.isSessionValid(userId, 4)).isFalse();
    }

    @Test
    void isSessionValid_generationNewerThanDb_returnsFalse() {
        // Future-dated generations should never happen; reject defensively.
        var tenant = new TenantEntity("Test");
        var user = new UserEntity(tenant, "u@e.com", "hash", "member");
        user.setTokenGeneration(3);
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(validator.isSessionValid(userId, 99)).isFalse();
    }

    @Test
    void isSessionValid_everythingCurrent_returnsTrue() {
        var tenant = new TenantEntity("Test");
        var user = new UserEntity(tenant, "u@e.com", "hash", "member");
        user.setTokenGeneration(7);
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(validator.isSessionValid(userId, 7)).isTrue();
    }

    @Test
    void isSessionValid_withSid_sessionMissing_returnsFalse() {
        var tenant = new TenantEntity("Test");
        var user = new UserEntity(tenant, "u@e.com", "hash", "member");
        var userId = UUID.randomUUID();
        var sid = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userSessionRepository.findByIdAndUserId(sid, userId)).thenReturn(Optional.empty());

        assertThat(validator.isSessionValid(userId, 0, sid)).isFalse();
    }

    @Test
    void isSessionValid_withSid_sessionRevoked_returnsFalse() {
        var tenant = new TenantEntity("Test");
        var user = new UserEntity(tenant, "u@e.com", "hash", "member");
        var userId = UUID.randomUUID();
        var sid = UUID.randomUUID();
        var session = new UserSessionEntity(UUID.randomUUID(), userId, null,
                "bearer", "1.2.3.4", "ua");
        session.setRevokedAt(OffsetDateTime.now().minusMinutes(1));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userSessionRepository.findByIdAndUserId(sid, userId)).thenReturn(Optional.of(session));

        assertThat(validator.isSessionValid(userId, 0, sid)).isFalse();
    }

    @Test
    void isSessionValid_withSid_sessionActive_returnsTrueAndCallsThrottledTouch() {
        var tenant = new TenantEntity("Test");
        var user = new UserEntity(tenant, "u@e.com", "hash", "member");
        var userId = UUID.randomUUID();
        var sid = UUID.randomUUID();
        var session = new UserSessionEntity(UUID.randomUUID(), userId, null,
                "bearer", "1.2.3.4", "ua");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userSessionRepository.findByIdAndUserId(sid, userId)).thenReturn(Optional.of(session));

        assertThat(validator.isSessionValid(userId, 0, sid)).isTrue();
        verify(userSessionRepository, times(1)).touchLastUsedIfStale(
                ArgumentMatchers.eq(sid),
                ArgumentMatchers.any(OffsetDateTime.class),
                ArgumentMatchers.any(OffsetDateTime.class));
    }

    @Test
    void isSessionValid_noSid_doesNotTouchSessionRepository() {
        var tenant = new TenantEntity("Test");
        var user = new UserEntity(tenant, "u@e.com", "hash", "member");
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(validator.isSessionValid(userId, 0)).isTrue();
        verify(userSessionRepository, never()).findByIdAndUserId(
                ArgumentMatchers.any(UUID.class), ArgumentMatchers.any(UUID.class));
    }
}
