package com.wealthview.persistence.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.SecurityAssetClassEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityAssetClassSeedIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SecurityAssetClassRepository repo;

    @Test
    void seed_classifiesKnownTickers() {
        assertThat(repo.findBySymbol("BND")).get()
                .extracting(SecurityAssetClassEntity::getAssetClass).isEqualTo("bond");
        assertThat(repo.findBySymbol("VXUS")).get()
                .extracting(SecurityAssetClassEntity::getAssetClass).isEqualTo("intl_stock");
        assertThat(repo.findBySymbol("SPAXX")).get()
                .extracting(SecurityAssetClassEntity::getAssetClass).isEqualTo("cash");
    }
}
