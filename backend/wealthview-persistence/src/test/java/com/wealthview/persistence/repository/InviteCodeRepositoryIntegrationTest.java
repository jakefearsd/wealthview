package com.wealthview.persistence.repository;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;

import static org.assertj.core.api.Assertions.assertThat;

class InviteCodeRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private InviteCodeRepository inviteCodeRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    private TenantEntity tenant;
    private UserEntity admin;

    @BeforeEach
    void setUp() {
        tenant = tenantRepository.save(new TenantEntity("Test Tenant"));
        admin = userRepository.save(new UserEntity(tenant, "admin@test.com", "hash", "admin"));
    }

    @Test
    void findByCode_existingCode_returnsInviteCode() {
        var invite = new InviteCodeEntity(tenant, "ABC123", admin,
                OffsetDateTime.now().plusDays(7));
        inviteCodeRepository.save(invite);

        var found = inviteCodeRepository.findByCode("ABC123");

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo("ABC123");
    }

    @Test
    void findByCode_nonExistent_returnsEmpty() {
        var found = inviteCodeRepository.findByCode("NOPE");

        assertThat(found).isEmpty();
    }

    @Test
    void findByTenantId_returnsCodesForTenant() {
        inviteCodeRepository.save(new InviteCodeEntity(tenant, "CODE1", admin,
                OffsetDateTime.now().plusDays(7)));
        inviteCodeRepository.save(new InviteCodeEntity(tenant, "CODE2", admin,
                OffsetDateTime.now().plusDays(7)));

        var codes = inviteCodeRepository.findByTenant_Id(tenant.getId());

        assertThat(codes).hasSize(2);
    }

    @Test
    void findByTenantIdWithUsers_returnsCodesWithEagerUsers() {
        var invite = new InviteCodeEntity(tenant, "EAGER1", admin,
                OffsetDateTime.now().plusDays(7));
        inviteCodeRepository.save(invite);

        var codes = inviteCodeRepository.findByTenantIdWithUsers(tenant.getId());

        assertThat(codes).hasSize(1);
        // createdBy is eagerly loaded — accessing it should not trigger a lazy-load proxy fault
        assertThat(codes.get(0).getCreatedBy()).isNotNull();
        assertThat(codes.get(0).getCreatedBy().getEmail()).isEqualTo("admin@test.com");
    }

    @Test
    void findByTenantIdWithUsers_otherTenant_returnsEmpty() {
        var otherTenant = tenantRepository.save(new TenantEntity("Other Tenant"));
        var otherAdmin = userRepository.save(new UserEntity(otherTenant, "other@test.com", "hash", "admin"));
        inviteCodeRepository.save(new InviteCodeEntity(otherTenant, "OTHER1", otherAdmin,
                OffsetDateTime.now().plusDays(7)));
        // Ensure code for tenant A does NOT appear when querying otherTenant
        inviteCodeRepository.save(new InviteCodeEntity(tenant, "MINE1", admin,
                OffsetDateTime.now().plusDays(7)));

        var codes = inviteCodeRepository.findByTenantIdWithUsers(otherTenant.getId());

        assertThat(codes).hasSize(1);
        assertThat(codes.get(0).getCode()).isEqualTo("OTHER1");
    }

    @Test
    void deleteByTenantIdAndConsumedByIsNotNull_deletesOnlyConsumedCodes() {
        // Active (unconsumed) invite
        inviteCodeRepository.save(new InviteCodeEntity(tenant, "OPEN1", admin,
                OffsetDateTime.now().plusDays(7)));

        // Consumed invite — simulate by saving then marking consumed
        var consumed = new InviteCodeEntity(tenant, "CONSUMED1", admin,
                OffsetDateTime.now().plusDays(7));
        consumed.setConsumedBy(admin);
        consumed.setConsumedAt(OffsetDateTime.now());
        inviteCodeRepository.save(consumed);

        int deleted = inviteCodeRepository.deleteByTenant_IdAndConsumedByIsNotNull(tenant.getId());

        assertThat(deleted).isEqualTo(1);
        assertThat(inviteCodeRepository.findByCode("OPEN1")).isPresent();
        assertThat(inviteCodeRepository.findByCode("CONSUMED1")).isEmpty();
    }

    @Test
    void deleteByTenantIdAndConsumedByIsNotNull_noConsumedCodes_returnsZero() {
        inviteCodeRepository.save(new InviteCodeEntity(tenant, "OPEN2", admin,
                OffsetDateTime.now().plusDays(7)));

        int deleted = inviteCodeRepository.deleteByTenant_IdAndConsumedByIsNotNull(tenant.getId());

        assertThat(deleted).isZero();
    }
}
