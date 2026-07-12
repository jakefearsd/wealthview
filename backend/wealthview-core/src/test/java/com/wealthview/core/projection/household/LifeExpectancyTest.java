package com.wealthview.core.projection.household;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LifeExpectancyTest {

    @ParameterizedTest
    @CsvSource({
            "1955, 86",
            "1990, 89",
            "1940, 84",
            "1941, 85",
            "1950, 85",
            "1951, 86",
            "1960, 86",
            "1961, 87",
            "1970, 87",
            "1971, 88",
            "1980, 88",
            "1981, 89",
            "1991, 90",
            "2010, 90"
    })
    void defaultDeathAge_birthYearInCohort_returnsSsaCohortDefault(int birthYear, int expectedDeathAge) {
        assertThat(LifeExpectancy.defaultDeathAge(birthYear)).isEqualTo(expectedDeathAge);
    }

    @Test
    void defaultDeathAge_extremeBirthYears_staysWithinClampBounds() {
        assertThat(LifeExpectancy.defaultDeathAge(1000)).isBetween(50, 120);
        assertThat(LifeExpectancy.defaultDeathAge(3000)).isBetween(50, 120);
    }
}
