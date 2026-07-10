package com.wealthview.persistence.repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.AssetClassReturnEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AssetClassReturnsSeedIntegrationTest extends AbstractIntegrationTest {

    private static final int FIRST_YEAR = 1972;
    private static final int LAST_YEAR = 2025;
    private static final List<String> CLASSES = List.of("us_stock", "intl_stock", "bond", "cash");

    @Autowired
    private AssetClassReturnRepository repository;

    @Test
    void seed_everyYearHasAllFourClasses() {
        var byYear = repository.findAllByOrderByYearAscAssetClassAsc().stream()
                .collect(Collectors.groupingBy(AssetClassReturnEntity::getYear,
                        Collectors.mapping(AssetClassReturnEntity::getAssetClass, Collectors.toSet())));

        for (int y = FIRST_YEAR; y <= LAST_YEAR; y++) {
            assertThat(byYear.get(y))
                    .as("year %d must have all four classes", y)
                    .containsExactlyInAnyOrderElementsOf(CLASSES);
        }
    }

    @Test
    void seed_perClassGeometricMean_isWithinHistoricalSanityBand() {
        var rows = repository.findAllByOrderByYearAscAssetClassAsc();
        Map<String, List<AssetClassReturnEntity>> byClass = rows.stream()
                .collect(Collectors.groupingBy(AssetClassReturnEntity::getAssetClass));

        // Real geometric means, generous bands from long-run literature.
        assertGeoMeanBetween(byClass.get("us_stock"), 0.04, 0.09);
        assertGeoMeanBetween(byClass.get("intl_stock"), 0.02, 0.08);
        assertGeoMeanBetween(byClass.get("bond"), 0.005, 0.045);
        assertGeoMeanBetween(byClass.get("cash"), -0.01, 0.025);
    }

    private static void assertGeoMeanBetween(List<AssetClassReturnEntity> rows, double lo, double hi) {
        double product = 1.0;
        for (var r : rows) {
            product *= (1.0 + r.getRealReturn().doubleValue());
        }
        double geoMean = Math.pow(product, 1.0 / rows.size()) - 1.0;
        assertThat(geoMean).isBetween(lo, hi);
    }
}
