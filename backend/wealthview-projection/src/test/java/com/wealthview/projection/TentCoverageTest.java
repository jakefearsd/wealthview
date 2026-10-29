package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TentCoverageTest {

    @Test
    void drawnShare_zeroPlanned_isZero() {
        assertThat(TentCoverage.drawnShare(5_000.0, 0.0)).isZero();
    }

    @Test
    void drawnShare_positivePlanned_isDrawnOverPlanned() {
        assertThat(TentCoverage.drawnShare(20_000.0, 40_000.0)).isEqualTo(0.5);
    }

    @Test
    void unusedYears_isLeftOverMeanPlanned() {
        assertThat(TentCoverage.unusedYears(60_000.0, 30_000.0, 30_000.0)).isEqualTo(2.0);
    }

    @Test
    void unusedYears_zeroMean_isZero() {
        assertThat(TentCoverage.unusedYears(60_000.0, 0.0, 0.0)).isZero();
    }

    @Test
    void unusedYears_noPlanned_isZero() {
        assertThat(TentCoverage.unusedYears(60_000.0, new double[0])).isZero();
    }
}
