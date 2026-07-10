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
        // Years chosen outside the seeded 1972-2025 range (R__seed_asset_class_returns.sql)
        // so this test's manual rows never collide with the seeded uq_asset_class_returns_year_class rows.
        // 1900 and 1901 sort before every seeded year, so they land at the head of the result.
        repository.save(new AssetClassReturnEntity(1901, "bond", new BigDecimal("0.010000")));
        repository.save(new AssetClassReturnEntity(1900, "us_stock", new BigDecimal("0.150000")));

        var rows = repository.findAllByOrderByYearAscAssetClassAsc();

        assertThat(rows.get(0).getYear()).isEqualTo(1900);
        assertThat(rows.get(1).getYear()).isEqualTo(1901);
    }
}
