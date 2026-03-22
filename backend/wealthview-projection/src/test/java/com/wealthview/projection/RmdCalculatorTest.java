package com.wealthview.projection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RmdCalculatorTest {

    @ParameterizedTest
    @CsvSource({
        "72, 27.4",
        "73, 26.5",
        "74, 25.5",
        "75, 24.6",
        "80, 20.2",
        "85, 16.0",
        "90, 12.2",
        "95, 8.9",
        "100, 6.4",
        "105, 4.6",
        "110, 3.2",
        "115, 2.1",
        "120, 2.0"
    })
    void distributionPeriod_matchesIrsTable(int age, double expectedPeriod) {
        assertThat(RmdCalculator.distributionPeriod(age)).isEqualTo(expectedPeriod);
    }

    @Test
    void computeRmd_atAge73_correctAmount() {
        double balance = 1_000_000;
        double rmd = RmdCalculator.computeRmd(balance, 73);
        assertThat(rmd).isCloseTo(37_735.85, within(0.01));
    }

    @Test
    void computeRmd_zeroBalance_returnsZero() {
        assertThat(RmdCalculator.computeRmd(0, 73)).isEqualTo(0);
    }

    @Test
    void computeRmd_negativeBalance_returnsZero() {
        assertThat(RmdCalculator.computeRmd(-100, 73)).isEqualTo(0);
    }

    @Test
    void computeRmd_belowAge72_returnsZero() {
        assertThat(RmdCalculator.computeRmd(1_000_000, 71)).isEqualTo(0);
        assertThat(RmdCalculator.computeRmd(1_000_000, 50)).isEqualTo(0);
    }

    @Test
    void rmdStartAge_bornBefore1960_returns73() {
        assertThat(RmdCalculator.rmdStartAge(1959)).isEqualTo(73);
        assertThat(RmdCalculator.rmdStartAge(1950)).isEqualTo(73);
    }

    @Test
    void rmdStartAge_born1960OrLater_returns75() {
        assertThat(RmdCalculator.rmdStartAge(1960)).isEqualTo(75);
        assertThat(RmdCalculator.rmdStartAge(1970)).isEqualTo(75);
        assertThat(RmdCalculator.rmdStartAge(2000)).isEqualTo(75);
    }
}
