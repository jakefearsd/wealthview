package com.wealthview.core.tenant;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserManagementService service;

    private TenantEntity tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        service = new UserManagementService(userRepository, passwordEncoder);
        tenant = new TenantEntity("Test");
        tenantId = UUID.randomUUID();
    }

    @Test
    void getUsersForTenant_returnsList() {
        when(userRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of(
                        new UserEntity(tenant, "a@test.com", "hash", "admin"),
                        new UserEntity(tenant, "b@test.com", "hash", "member")));

        var result = service.getUsersForTenant(tenantId);

        assertThat(result).hasSize(2);
    }

    @Test
    void updateUserRole_validUser_updatesRole() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "hash", "member");
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.updateUserRole(tenantId, userId, "admin");

        assertThat(result.getRole()).isEqualTo("admin");
    }

    @Test
    void updateUserRole_nonExistent_throwsNotFound() {
        var userId = UUID.randomUUID();
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateUserRole(tenantId, userId, "admin"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void deleteUser_validUser_deletesUser() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "hash", "member");
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));

        service.deleteUser(tenantId, userId);

        verify(userRepository).delete(user);
    }

    @Test
    void resetPassword_validUser_updatesPasswordHash() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "old-hash", "member");
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass123")).thenReturn("new-encoded-hash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resetPassword(tenantId, userId, "newpass123");

        assertThat(user.getPasswordHash()).isEqualTo("new-encoded-hash");
        verify(userRepository).save(user);
    }

    @Test
    void resetPassword_nonExistent_throwsNotFound() {
        var userId = UUID.randomUUID();
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(tenantId, userId, "newpass"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void setUserActive_deactivatesUser() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "hash", "member");
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setUserActive(tenantId, userId, false);

        assertThat(user.isActive()).isFalse();
        verify(userRepository).save(user);
    }

    @Test
    void setUserActive_activatesUser() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "hash", "member");
        user.setActive(false);
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setUserActive(tenantId, userId, true);

        assertThat(user.isActive()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void setUserActive_nonExistent_throwsNotFound() {
        var userId = UUID.randomUUID();
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setUserActive(tenantId, userId, false))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
