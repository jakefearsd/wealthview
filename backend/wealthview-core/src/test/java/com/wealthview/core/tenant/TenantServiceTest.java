package com.wealthview.core.tenant;

import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.UserRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private InviteCodeRepository inviteCodeRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TenantService tenantService;

    @Test
    void createTenant_validName_returnsSavedTenant() {
        var tenant = new TenantEntity("Test Family");
        when(tenantRepository.save(any(TenantEntity.class))).thenReturn(tenant);

        var result = tenantService.createTenant("Test Family");

        assertThat(result.getName()).isEqualTo("Test Family");
    }

    @Test
    void getAllTenants_returnsList() {
        when(tenantRepository.findAll()).thenReturn(
                List.of(new TenantEntity("A"), new TenantEntity("B")));

        var result = tenantService.getAllTenants();

        assertThat(result).hasSize(2);
    }

    @Test
    void generateInviteCode_validTenant_returnsInviteCode() {
        var tenant = new TenantEntity("Test");
        var user = new UserEntity(tenant, "admin@test.com", "hash", "admin");
        var tenantId = UUID.randomUUID();
        var userId = UUID.randomUUID();

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(inviteCodeRepository.save(any(InviteCodeEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = tenantService.generateInviteCode(tenantId, userId);

        assertThat(result.getCode()).isNotBlank();
        assertThat(result.getCode()).hasSize(8);
    }

    @Test
    void generateInviteCode_nonExistentTenant_throwsNotFound() {
        var tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.generateInviteCode(tenantId, UUID.randomUUID()))
                .isInstanceOf(InvalidSessionException.class);
    }
}
