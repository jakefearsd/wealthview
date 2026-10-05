package com.wealthview.core.projection.household;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgeMilestonesTest {

    static Stream<Arguments> earlyAccessAgeCases() {
        return Stream.of(
                Arguments.of(1, 59),
                Arguments.of(3, 59),
                Arguments.of(6, 59),
                Arguments.of(7, 60),
                Arguments.of(9, 60),
                Arguments.of(12, 60));
    }

    @ParameterizedTest
    @MethodSource("earlyAccessAgeCases")
    void earlyAccessAge_knownBirthMonth_returnsAgeOfThe59AndAHalfCalendarYear(int birthMonth, int expectedAge) {
        int age = AgeMilestones.earlyAccessAge(birthMonth);

        assertThat(age).isEqualTo(expectedAge);
    }

    @Test
    void earlyAccessAge_nullBirthMonth_returnsLegacy60() {
        int age = AgeMilestones.earlyAccessAge(null);

        assertThat(age).isEqualTo(AgeMilestones.LEGACY_EARLY_ACCESS_AGE).isEqualTo(60);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 13, -1})
    void earlyAccessAge_monthOutsideOneToTwelve_throws(int badMonth) {
        assertThatThrownBy(() -> AgeMilestones.earlyAccessAge(badMonth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 12");
    }

    @Test
    void earlyAccessYear_bornMarch1970_is2029() {
        // Born March 1970 -> reaches 59.5 in September 2029 -> 2029 is the penalty-free year.
        int year = AgeMilestones.earlyAccessYear(1970, 3);

        assertThat(year).isEqualTo(2029);
    }

    @Test
    void earlyAccessYear_bornSeptember1970_is2030() {
        // Born September 1970 -> reaches 59.5 in March 2030.
        int year = AgeMilestones.earlyAccessYear(1970, 9);

        assertThat(year).isEqualTo(2030);
    }

    @Test
    void earlyAccessYear_nullMonth_isLegacyBirthYearPlus60() {
        int year = AgeMilestones.earlyAccessYear(1970, null);

        assertThat(year).isEqualTo(2030);
    }

    @Test
    void medicareStartMonth_knownMonth_returnsBirthMonth() {
        int month = AgeMilestones.medicareStartMonth(8);

        assertThat(month).isEqualTo(8);
    }

    @Test
    void medicareStartMonth_nullMonth_returnsJanuaryForWholeYearLegacy() {
        int month = AgeMilestones.medicareStartMonth(null);

        assertThat(month).isEqualTo(1);
    }

    @Test
    void medicareStartMonth_invalidMonth_throws() {
        assertThatThrownBy(() -> AgeMilestones.medicareStartMonth(13))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
