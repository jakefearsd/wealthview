package com.wealthview.core.tenant;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private UserManagementService service;

    private TenantEntity tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenant = new TenantEntity("Test");
        tenantId = UUID.randomUUID();
    }

    @Test
    void getUsersForTenant_returnsList() {
        when(userRepository.findByTenantId(tenantId))
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
        when(userRepository.findByTenantIdAndId(tenantId, userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.updateUserRole(tenantId, userId, "admin");

        assertThat(result.getRole()).isEqualTo("admin");
    }

    @Test
    void updateUserRole_nonExistent_throwsNotFound() {
        var userId = UUID.randomUUID();
        when(userRepository.findByTenantIdAndId(tenantId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateUserRole(tenantId, userId, "admin"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void deleteUser_validUser_deletesUser() {
        var userId = UUID.randomUUID();
        var user = new UserEntity(tenant, "user@test.com", "hash", "member");
        when(userRepository.findByTenantIdAndId(tenantId, userId)).thenReturn(Optional.of(user));

        service.deleteUser(tenantId, userId);

        verify(userRepository).delete(user);
    }
}
