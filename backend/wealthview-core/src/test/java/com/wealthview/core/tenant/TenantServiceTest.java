package com.wealthview.core.tenant;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.testutil.TestEntityHelper;
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private InviteCodeRepository inviteCodeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

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
    void generateInviteCode_withCustomExpiry_setsCorrectExpiry() {
        var tenant = new TenantEntity("Test");
        var user = new UserEntity(tenant, "admin@test.com", "hash", "admin");
        var tenantId = UUID.randomUUID();
        var userId = UUID.randomUUID();

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(inviteCodeRepository.save(any(InviteCodeEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = tenantService.generateInviteCode(tenantId, userId, 30);

        assertThat(result.getCode()).isNotBlank();
        assertThat(result.getExpiresAt()).isAfter(OffsetDateTime.now().plusDays(29));
        assertThat(result.getExpiresAt()).isBefore(OffsetDateTime.now().plusDays(31));
    }

    @Test
    void generateInviteCode_nonExistentTenant_throwsNotFound() {
        var tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.generateInviteCode(tenantId, UUID.randomUUID()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void getAllTenantDetails_returnsTenantDetailsWithCounts() {
        var tenant1 = new TenantEntity("Tenant A");
        var tenant2 = new TenantEntity("Tenant B");
        TestEntityHelper.setId(tenant1, UUID.randomUUID());
        TestEntityHelper.setId(tenant2, UUID.randomUUID());

        when(tenantRepository.findAll()).thenReturn(List.of(tenant1, tenant2));
        when(userRepository.countByTenant_Id(tenant1.getId())).thenReturn(3L);
        when(userRepository.countByTenant_Id(tenant2.getId())).thenReturn(1L);
        when(accountRepository.countByTenant_Id(tenant1.getId())).thenReturn(5L);
        when(accountRepository.countByTenant_Id(tenant2.getId())).thenReturn(2L);

        var result = tenantService.getAllTenantDetails();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("Tenant A");
        assertThat(result.get(0).userCount()).isEqualTo(3);
        assertThat(result.get(0).accountCount()).isEqualTo(5);
        assertThat(result.get(1).userCount()).isEqualTo(1);
    }

    @Test
    void getTenantDetail_found_returnsTenantDetail() {
        var tenant = new TenantEntity("Test");
        var tenantId = UUID.randomUUID();
        TestEntityHelper.setId(tenant, tenantId);

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.countByTenant_Id(tenantId)).thenReturn(2L);
        when(accountRepository.countByTenant_Id(tenantId)).thenReturn(3L);

        var result = tenantService.getTenantDetail(tenantId);

        assertThat(result.name()).isEqualTo("Test");
        assertThat(result.isActive()).isTrue();
        assertThat(result.userCount()).isEqualTo(2);
        assertThat(result.accountCount()).isEqualTo(3);
    }

    @Test
    void getTenantDetail_notFound_throwsEntityNotFoundException() {
        var tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.getTenantDetail(tenantId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void setTenantActive_disablesTenant() {
        var tenant = new TenantEntity("Test");
        var tenantId = UUID.randomUUID();
        TestEntityHelper.setId(tenant, tenantId);

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        tenantService.setTenantActive(tenantId, false);

        assertThat(tenant.isActive()).isFalse();
        verify(tenantRepository).save(tenant);
    }

    @Test
    void revokeInviteCode_validCode_setsRevoked() {
        var tenant = new TenantEntity("Test");
        var tenantId = UUID.randomUUID();
        TestEntityHelper.setId(tenant, tenantId);
        var user = new UserEntity(tenant, "admin@test.com", "hash", "admin");
        var invite = new InviteCodeEntity(tenant, "CODE1234", user, OffsetDateTime.now().plusDays(7));
        var codeId = UUID.randomUUID();
        TestEntityHelper.setId(invite, codeId);

        when(inviteCodeRepository.findById(codeId)).thenReturn(Optional.of(invite));
        when(inviteCodeRepository.save(any(InviteCodeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        tenantService.revokeInviteCode(tenantId, codeId);

        assertThat(invite.isRevoked()).isTrue();
        verify(inviteCodeRepository).save(invite);
    }

    @Test
    void revokeInviteCode_wrongTenant_throwsNotFound() {
        var tenant = new TenantEntity("Test");
        var tenantId = UUID.randomUUID();
        TestEntityHelper.setId(tenant, tenantId);
        var user = new UserEntity(tenant, "admin@test.com", "hash", "admin");
        var invite = new InviteCodeEntity(tenant, "CODE1234", user, OffsetDateTime.now().plusDays(7));
        var codeId = UUID.randomUUID();
        TestEntityHelper.setId(invite, codeId);

        when(inviteCodeRepository.findById(codeId)).thenReturn(Optional.of(invite));

        var wrongTenantId = UUID.randomUUID();
        assertThatThrownBy(() -> tenantService.revokeInviteCode(wrongTenantId, codeId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void revokeInviteCode_notFound_throwsNotFound() {
        var codeId = UUID.randomUUID();
        when(inviteCodeRepository.findById(codeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.revokeInviteCode(UUID.randomUUID(), codeId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void deleteUsedCodes_returnsDeletedCount() {
        var tenantId = UUID.randomUUID();
        when(inviteCodeRepository.deleteByTenant_IdAndConsumedByIsNotNull(tenantId)).thenReturn(3);

        var result = tenantService.deleteUsedCodes(tenantId);

        assertThat(result).isEqualTo(3);
    }

    @Test
    void deleteUsedCodes_noUsedCodes_returnsZero() {
        var tenantId = UUID.randomUUID();
        when(inviteCodeRepository.deleteByTenant_IdAndConsumedByIsNotNull(tenantId)).thenReturn(0);

        var result = tenantService.deleteUsedCodes(tenantId);

        assertThat(result).isEqualTo(0);
    }
}
