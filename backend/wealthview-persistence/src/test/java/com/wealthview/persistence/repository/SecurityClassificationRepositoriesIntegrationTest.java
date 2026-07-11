package com.wealthview.persistence.repository;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.SecurityAssetClassEntity;
import com.wealthview.persistence.entity.SecurityClassOverrideEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityClassificationRepositoriesIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SecurityAssetClassRepository seedRepo;

    @Autowired
    private SecurityClassOverrideRepository overrideRepo;

    @Test
    void findBySymbol_returnsSeededClass() {
        seedRepo.save(new SecurityAssetClassEntity("TEST_SYM", "bond"));

        assertThat(seedRepo.findBySymbol("TEST_SYM")).get()
                .extracting(SecurityAssetClassEntity::getAssetClass).isEqualTo("bond");
    }

    @Test
    void findByTenantIdAndSymbol_returnsOverride() {
        var tenant = UUID.randomUUID();
        overrideRepo.save(new SecurityClassOverrideEntity(tenant, "XYZ", "intl_stock"));

        assertThat(overrideRepo.findByTenantIdAndSymbol(tenant, "XYZ")).get()
                .extracting(SecurityClassOverrideEntity::getAssetClass).isEqualTo("intl_stock");
    }

    @Test
    void upsert_existingRow_updatesInPlaceAndLeavesOtherTenantUntouched() {
        var tenant = UUID.randomUUID();
        var otherTenant = UUID.randomUUID();
        overrideRepo.save(new SecurityClassOverrideEntity(tenant, "SPAXX", "us_stock"));
        overrideRepo.save(new SecurityClassOverrideEntity(otherTenant, "SPAXX", "us_stock"));

        // Simulate SecurityClassificationService#setOverride: find-then-mutate-then-save.
        var existing = overrideRepo.findByTenantIdAndSymbol(tenant, "SPAXX").orElseThrow();
        existing.setAssetClass("cash");
        overrideRepo.save(existing);

        assertThat(overrideRepo.findByTenantIdAndSymbol(tenant, "SPAXX")).get()
                .extracting(SecurityClassOverrideEntity::getAssetClass).isEqualTo("cash");
        assertThat(overrideRepo.findByTenantIdAndSymbol(otherTenant, "SPAXX")).get()
                .extracting(SecurityClassOverrideEntity::getAssetClass).isEqualTo("us_stock");
    }
}
