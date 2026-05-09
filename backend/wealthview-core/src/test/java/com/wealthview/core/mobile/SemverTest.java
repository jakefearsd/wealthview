package com.wealthview.core.mobile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemverTest {

    @Test
    void compare_equalVersions_returnsZero() {
        assertThat(Semver.compare("1.2.3", "1.2.3")).isZero();
    }

    @Test
    void compare_majorVersionDifference_dominates() {
        assertThat(Semver.compare("2.0.0", "1.99.99")).isPositive();
        assertThat(Semver.compare("1.99.99", "2.0.0")).isNegative();
    }

    @Test
    void compare_minorVersionDifference_dominatesPatch() {
        assertThat(Semver.compare("1.3.0", "1.2.99")).isPositive();
        assertThat(Semver.compare("1.2.99", "1.3.0")).isNegative();
    }

    @Test
    void compare_patchVersionDifference_isUsedWhenMajorMinorEqual() {
        assertThat(Semver.compare("1.2.4", "1.2.3")).isPositive();
        assertThat(Semver.compare("1.2.3", "1.2.4")).isNegative();
    }

    @Test
    void compare_preReleaseVersion_isOlderThanReleaseEquivalent() {
        // For force-update use, pre-releases are treated as < the same release
        // (e.g., 1.2.3-alpha < 1.2.3) so a beta build never satisfies a
        // minimum-version floor that demands the GA.
        assertThat(Semver.compare("1.2.3-alpha", "1.2.3")).isNegative();
        assertThat(Semver.compare("1.2.3", "1.2.3-alpha")).isPositive();
    }

    @Test
    void compare_preReleaseVsPreRelease_treatsAsEqual() {
        // We don't try to order pre-release suffixes against each other —
        // operators won't ship pre-release minimum-version floors. Equal numeric
        // triples with any pre-release suffix compare as equal.
        assertThat(Semver.compare("1.2.3-alpha", "1.2.3-beta")).isZero();
    }

    @Test
    void compare_largeNumericComponents_areParsedCorrectly() {
        assertThat(Semver.compare("100.200.300", "99.999.999")).isPositive();
    }

    @ParameterizedTest
    @CsvSource({
            "''",
            "1",
            "1.2",
            "1.2.3.4",
            "not-a-version",
            "v1.2.3",
            "1.2.x",
            "-1.0.0",
            "1.-2.0"
    })
    void compare_malformedString_throwsIllegalArgumentException(String bad) {
        assertThatThrownBy(() -> Semver.compare(bad, "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compare_nullArgument_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> Semver.compare(null, "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Semver.compare("1.0.0", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isValid_acceptsCanonicalSemver() {
        assertThat(Semver.isValid("1.2.3")).isTrue();
        assertThat(Semver.isValid("0.0.1")).isTrue();
        assertThat(Semver.isValid("100.200.300")).isTrue();
    }

    @Test
    void isValid_acceptsPreReleaseSuffix() {
        assertThat(Semver.isValid("1.2.3-alpha")).isTrue();
        assertThat(Semver.isValid("1.2.3-beta.1")).isTrue();
        assertThat(Semver.isValid("1.2.3-rc.0.1")).isTrue();
    }

    @Test
    void isValid_rejectsMalformed() {
        assertThat(Semver.isValid("")).isFalse();
        assertThat(Semver.isValid("1")).isFalse();
        assertThat(Semver.isValid("1.2")).isFalse();
        assertThat(Semver.isValid("1.2.3.4")).isFalse();
        assertThat(Semver.isValid("v1.2.3")).isFalse();
        assertThat(Semver.isValid("1.2.x")).isFalse();
        assertThat(Semver.isValid(null)).isFalse();
    }
}
