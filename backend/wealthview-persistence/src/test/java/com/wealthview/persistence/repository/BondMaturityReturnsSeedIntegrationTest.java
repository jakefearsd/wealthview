package com.wealthview.persistence.repository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.BondMaturityReturnEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class BondMaturityReturnsSeedIntegrationTest extends AbstractIntegrationTest {

    private static final int FIRST_YEAR = 1954;
    private static final int LAST_YEAR = 2025;
    private static final int TOTAL_YEARS = LAST_YEAR - FIRST_YEAR + 1;
    private static final List<String> MATURITIES = List.of("short", "intermediate", "long");

    @Autowired
    private BondMaturityReturnRepository repository;

    @Test
    void seed_everyYear1954To2025HasShortIntermediateAndLong() {
        var byYear = byYear();

        for (int y = FIRST_YEAR; y <= LAST_YEAR; y++) {
            assertThat(byYear.get(y))
                    .as("year %d must have all three maturities", y)
                    .isNotNull()
                    .satisfies(m -> assertThat(m.keySet()).isEqualTo(Set.copyOf(MATURITIES)));
        }
    }

    @Test
    void seed_rowCount_isExactlyThreePerYear() {
        assertThat(repository.findAllByOrderByYearAscMaturityAsc()).hasSize(TOTAL_YEARS * MATURITIES.size());
    }

    @Test
    void seed_2008_longTreasuriesRallied_shortBarelyMoved() {
        assertThat(r(2008, "long")).isGreaterThan(0.15);
        assertThat(r(2008, "intermediate")).isBetween(0.04, r(2008, "long"));
        assertThat(r(2008, "short")).isBetween(0.0, 0.06);
    }

    @Test
    void seed_2022_allMaturitiesFell_longestFellMost() {
        assertThat(r(2022, "long")).isLessThan(-0.25);
        assertThat(r(2022, "intermediate")).isBetween(r(2022, "long"), -0.08);
        assertThat(r(2022, "short")).isBetween(r(2022, "intermediate"), 0.0);
        assertThat(r(2022, "short")).isGreaterThan(r(2022, "long") / 2);
    }

    @Test
    void seed_volatility_risesWithMaturity() {
        assertThat(stdev("short")).isLessThan(stdev("intermediate"));
        assertThat(stdev("intermediate")).isLessThan(stdev("long"));
    }

    @Test
    void seed_perMaturityGeometricMean_isWithinHistoricalSanityBand() {
        assertThat(geoMean("short")).isBetween(0.0, 0.025);
        assertThat(geoMean("intermediate")).isBetween(0.0, 0.03);
        assertThat(geoMean("long")).isBetween(0.0, 0.04);
    }

    @Test
    void seed_spotValues_matchTheAmendedMethodPrototype() {
        // Algorithm-fidelity pins from the controller's independent prototype of the amended method (2026-10-22):
        // 25-year long priced off the 20/30-year midpoint, January 1973 long bridged. They prove the script follows
        // that method; the shape tests above are the independent sanity checks.
        assertThat(r(1954, "intermediate")).isCloseTo(0.024519, within(0.000002));
        assertThat(r(1973, "long")).isCloseTo(-0.064879, within(0.000002));
        assertThat(r(2008, "long")).isCloseTo(0.296490, within(0.000002));
        assertThat(r(2022, "short")).isCloseTo(-0.111088, within(0.000002));
        assertThat(r(2022, "long")).isCloseTo(-0.347061, within(0.000002));
        assertThat(r(2025, "intermediate")).isCloseTo(0.042347, within(0.000002));
    }

    @Test
    void insert_ultrashortMaturity_violatesCheckConstraint() {
        var bad = new BondMaturityReturnEntity(1900, "ultrashort", BigDecimal.ZERO);

        assertThatThrownBy(() -> repository.saveAndFlush(bad))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Map<Integer, Map<String, Double>> byYear() {
        Map<Integer, Map<String, Double>> out = new HashMap<>();
        for (var row : repository.findAllByOrderByYearAscMaturityAsc()) {
            out.computeIfAbsent(row.getYear(), k -> new HashMap<>())
                    .put(row.getMaturity(), row.getRealReturn().doubleValue());
        }
        return out;
    }

    private double r(int year, String maturity) {
        return byYear().get(year).get(maturity);
    }

    private List<Double> series(String maturity) {
        var byYear = byYear();
        return java.util.stream.IntStream.rangeClosed(FIRST_YEAR, LAST_YEAR)
                .mapToObj(y -> byYear.get(y).get(maturity))
                .toList();
    }

    private double geoMean(String maturity) {
        double product = 1.0;
        var values = series(maturity);
        for (double v : values) {
            product *= 1.0 + v;
        }
        return Math.pow(product, 1.0 / values.size()) - 1.0;
    }

    private double stdev(String maturity) {
        var values = series(maturity);
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElseThrow();
        return Math.sqrt(variance);
    }
}
