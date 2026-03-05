package com.wealthview.persistence.repository;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;

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
}
