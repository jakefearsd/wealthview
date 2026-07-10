package com.wealthview.persistence.repository;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.AssetClassReturnEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AssetClassReturnRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AssetClassReturnRepository repository;

    @Test
    void findAllByOrderByYearAscAssetClassAsc_returnsSortedRows() {
        repository.save(new AssetClassReturnEntity(1973, "bond", new BigDecimal("0.010000")));
        repository.save(new AssetClassReturnEntity(1972, "us_stock", new BigDecimal("0.150000")));

        var rows = repository.findAllByOrderByYearAscAssetClassAsc();

        assertThat(rows).extracting(AssetClassReturnEntity::getYear).containsExactly(1972, 1973);
    }
}
