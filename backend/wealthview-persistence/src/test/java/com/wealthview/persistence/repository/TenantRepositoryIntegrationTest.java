package com.wealthview.persistence.repository;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.TenantEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class TenantRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    void save_validTenant_persistsAndGeneratesId() {
        var tenant = new TenantEntity("Test Family");

        var saved = tenantRepository.save(tenant);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Test Family");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void findById_existingTenant_returnsTenant() {
        var tenant = tenantRepository.save(new TenantEntity("Find Me"));

        var found = tenantRepository.findById(tenant.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Find Me");
    }

    @Test
    void findById_nonExistentId_returnsEmpty() {
        var found = tenantRepository.findById(java.util.UUID.randomUUID());

        assertThat(found).isEmpty();
    }
}
