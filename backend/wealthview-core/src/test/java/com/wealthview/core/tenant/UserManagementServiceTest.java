package com.wealthview.core.tenant;

import com.wealthview.core.audit.AuditEvent;
import com.wealthview.core.auth.CommonPasswordChecker;
import com.wealthview.core.auth.TenantContext;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.testutil.TestEntityHelper;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private UserManagementService service;

    private TenantEntity tenant;
    private UUID tenantId;
    private UUID actorUserId;

    @BeforeEach
    void setUp() {
        service = new UserManagementService(
                userRepository, passwordEncoder, new CommonPasswordChecker(), eventPublisher);
        tenant = new TenantEntity("Test");
        tenantId = UUID.randomUUID();
        TestEntityHelper.setId(tenant, tenantId);

        actorUserId = UUID.randomUUID();
        var actor = new TestAuthenticatedUser(actorUserId, tenantId, "admin");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private record TestAuthenticatedUser(UUID userId, UUID tenantId, String role)
            implements TenantContext.AuthenticatedUser {
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
    void updateUserRole_bumpsTokenGenerationToRevokeStaleRoleClaim() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "hash", "admin");
        user.setTokenGeneration(5);
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateUserRole(tenantId, userId, "member");

        assertThat(user.getTokenGeneration()).isEqualTo(6);
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
    void resetPassword_bumpsTokenGenerationToRevokeExistingSessions() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "old-hash", "member");
        user.setTokenGeneration(7);
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass123")).thenReturn("new-encoded-hash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resetPassword(tenantId, userId, "newpass123");

        assertThat(user.getTokenGeneration()).isEqualTo(8);
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

    @Test
    void updateUserRole_invalidRole_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.updateUserRole(tenantId, UUID.randomUUID(), "super_admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid role");
    }

    @Test
    void updateUserRole_emptyRole_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.updateUserRole(tenantId, UUID.randomUUID(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateUserRole_superAdminUser_throwsIllegalState() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "superadmin@test.com", "hash", "admin");
        user.setSuperAdmin(true);
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.updateUserRole(tenantId, userId, "member"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot modify super admin role");
    }

    @Test
    void deleteUser_nonExistent_throwsNotFound() {
        var userId = UUID.randomUUID();
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUser(tenantId, userId))
                .isInstanceOf(EntityNotFoundException.class);

        verify(userRepository, never()).delete(any(UserEntity.class));
    }

    @Test
    void resetPassword_commonPassword_throwsAndDoesNotLookupUser() {
        assertThatThrownBy(() -> service.resetPassword(tenantId, UUID.randomUUID(), "password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too common");

        verify(userRepository, never()).findByTenant_IdAndId(any(), any());
        verify(userRepository, never()).save(any(UserEntity.class));
    }

    @Test
    void getAllUsers_returnsAllUsersFromRepository() {
        var u1 = new UserEntity(tenant, "a@test.com", "hash", "admin");
        var u2 = new UserEntity(tenant, "b@test.com", "hash", "member");
        when(userRepository.findAllWithTenant()).thenReturn(List.of(u1, u2));

        var result = service.getAllUsers();

        assertThat(result).containsExactly(u1, u2);
    }

    @Test
    void resetPasswordByUserId_validUser_updatesPasswordHash() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "old-hash", "member");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass123")).thenReturn("fresh-hash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resetPasswordByUserId(userId, "newpass123");

        assertThat(user.getPasswordHash()).isEqualTo("fresh-hash");
        verify(userRepository).save(user);
    }

    @Test
    void resetPasswordByUserId_bumpsTokenGenerationToRevokeExistingSessions() {
        // A password reset must invalidate every outstanding access and
        // refresh token for this user — otherwise an attacker who knew the
        // old password (or still holds a stolen token) retains access.
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "old-hash", "member");
        user.setTokenGeneration(3);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass123")).thenReturn("fresh-hash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resetPasswordByUserId(userId, "newpass123");

        assertThat(user.getTokenGeneration()).isEqualTo(4);
    }

    @Test
    void resetPasswordByUserId_commonPassword_throwsAndDoesNotLookupUser() {
        var userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.resetPasswordByUserId(userId, "password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too common");

        verify(userRepository, never()).findById(any(UUID.class));
        verify(userRepository, never()).save(any(UserEntity.class));
    }

    @Test
    void resetPasswordByUserId_nonExistent_throwsNotFound() {
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPasswordByUserId(userId, "newpass123"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(userId.toString());

        verify(userRepository, never()).save(any(UserEntity.class));
    }

    @Test
    void setUserActiveById_deactivatesUser() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "hash", "member");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setUserActiveById(userId, false);

        assertThat(user.isActive()).isFalse();
        verify(userRepository).save(user);
    }

    @Test
    void setUserActiveById_activatesUser() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "hash", "member");
        user.setActive(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setUserActiveById(userId, true);

        assertThat(user.isActive()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void setUserActiveById_nonExistent_throwsNotFound() {
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setUserActiveById(userId, true))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(userId.toString());
    }

    @Test
    void updateUserRole_publishesAuditEventWithOldAndNewRole() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "hash", "member");
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateUserRole(tenantId, userId, "admin");

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.userId()).isEqualTo(actorUserId);
        assertThat(event.action()).isEqualTo("USER_ROLE_UPDATE");
        assertThat(event.entityType()).isEqualTo("user");
        assertThat(event.entityId()).isEqualTo(userId);
        assertThat(event.details()).containsEntry("old_role", "member").containsEntry("new_role", "admin");
    }

    @Test
    void deleteUser_publishesAuditEventWithEmail() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "gone@test.com", "hash", "member");
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));

        service.deleteUser(tenantId, userId);

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.userId()).isEqualTo(actorUserId);
        assertThat(event.action()).isEqualTo("USER_DELETE");
        assertThat(event.entityType()).isEqualTo("user");
        assertThat(event.entityId()).isEqualTo(userId);
        assertThat(event.details()).containsEntry("email", "gone@test.com");
    }

    @Test
    void resetPassword_publishesAuditEventWithoutPasswordInDetails() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "old-hash", "member");
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass123")).thenReturn("new-hash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resetPassword(tenantId, userId, "newpass123");

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.userId()).isEqualTo(actorUserId);
        assertThat(event.action()).isEqualTo("USER_PASSWORD_RESET");
        assertThat(event.entityType()).isEqualTo("user");
        assertThat(event.entityId()).isEqualTo(userId);
        assertThat(event.details()).doesNotContainKey("new_password").doesNotContainKey("password_hash");
    }

    @Test
    void setUserActive_publishesAuditEventWithActiveFlag() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "hash", "member");
        when(userRepository.findByTenant_IdAndId(tenantId, userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setUserActive(tenantId, userId, false);

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.userId()).isEqualTo(actorUserId);
        assertThat(event.action()).isEqualTo("USER_SET_ACTIVE");
        assertThat(event.entityId()).isEqualTo(userId);
        assertThat(event.details()).containsEntry("active", false);
    }

    @Test
    void resetPasswordByUserId_publishesAuditEventUnderTargetTenant() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "target@test.com", "old-hash", "member");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass123")).thenReturn("new-hash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resetPasswordByUserId(userId, "newpass123");

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.userId()).isEqualTo(actorUserId);
        assertThat(event.action()).isEqualTo("USER_PASSWORD_RESET");
        assertThat(event.entityId()).isEqualTo(userId);
    }

    @Test
    void setUserActiveById_publishesAuditEventUnderTargetTenant() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "target@test.com", "hash", "member");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setUserActiveById(userId, true);

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.userId()).isEqualTo(actorUserId);
        assertThat(event.action()).isEqualTo("USER_SET_ACTIVE");
        assertThat(event.entityId()).isEqualTo(userId);
        assertThat(event.details()).containsEntry("active", true);
    }
}
