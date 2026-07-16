package com.wealthview.core.projection.mortality;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MortalityTableTest {

    @Test
    void qx_femaleAt70_returnsSeededValue() {
        var table = new MortalityTable(Map.of(70, 0.02, 120, 1.0), Map.of(70, 0.012, 120, 1.0));

        double qx = table.qx("female", 70);

        assertThat(qx).isEqualTo(0.012);
    }

    @Test
    void qx_nullSex_returnsBlendedMean() {
        var table = new MortalityTable(Map.of(70, 0.02, 120, 1.0), Map.of(70, 0.012, 120, 1.0));

        double qx = table.qx(null, 70);

        assertThat(qx).isEqualTo(0.016);   // (0.02 + 0.012) / 2
    }

    @Test
    void qx_nullSex_oneSexMissingAge_blendsEachSexIndependentFallback() {
        // Divergent age keys: at age 50 the male map has no entry (falls back to its min-age 60 qx),
        // while the female map has an exact 50 entry -- the blend must average the two INDEPENDENT
        // per-sex lookups, not blend a single shared fallback.
        var table = new MortalityTable(Map.of(60, 0.005, 120, 1.0), Map.of(50, 0.003, 120, 1.0));

        double qx = table.qx(null, 50);

        assertThat(qx).isEqualTo(0.004);   // (male min-age fallback 0.005 + female exact 0.003) / 2
    }

    @Test
    void qx_ageAboveMax_forcesDeath() {
        var table = new MortalityTable(Map.of(120, 1.0), Map.of(120, 1.0));

        double qx = table.qx("male", 130);

        assertThat(qx).isEqualTo(1.0);
    }

    @Test
    void qx_maleAt70_returnsSeededValue() {
        var table = new MortalityTable(Map.of(70, 0.02, 120, 1.0), Map.of(70, 0.012, 120, 1.0));

        double qx = table.qx("male", 70);

        assertThat(qx).isEqualTo(0.02);
    }

    @Test
    void qx_ageBelowMin_returnsMinAgeQx() {
        var table = new MortalityTable(Map.of(40, 0.001, 120, 1.0), Map.of(40, 0.0005, 120, 1.0));

        double qx = table.qx("male", 10);

        assertThat(qx).isEqualTo(0.001);
    }

    @Test
    void maxAge_returnsHighestTabulatedAge() {
        var table = new MortalityTable(Map.of(40, 0.001, 120, 1.0), Map.of(40, 0.0005, 120, 1.0));

        int maxAge = table.maxAge();

        assertThat(maxAge).isEqualTo(120);
    }
}
