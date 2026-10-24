package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentMix;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioParamsValidatorTest {

    private static final String COVERAGE_MESSAGE = "Bond tent coverage must be between 1 and 10 years, "
            + "in half-year steps";
    private static final String BUILD_UP_MESSAGE = "Bond tent build-up start must be between 0 and 40 "
            + "years before retirement";

    private static BondMaturityValues values(String us, String s, String i, String l) {
        return new BondMaturityValues(new BigDecimal(us), new BigDecimal(s), new BigDecimal(i), new BigDecimal(l));
    }

    private static BondTentMix mix(String name, String us, String s, String i, String l) {
        return new BondTentMix(name, values(us, s, i, l));
    }

    private static BondTentMix balanced() {
        return mix("Balanced", "0.2", "0.3", "0.5", "0");
    }

    private static BondMaturityValues yields() {
        return values("0.0394", "0.0465", "0.048", "0.0539");
    }

    private static BondTentConfig tent(String coverage, int buildUp, List<BondTentMix> mixes,
                                       BondMaturityValues yields) {
        return new BondTentConfig(new BigDecimal(coverage), buildUp, mixes, yields);
    }

    private static BondTentConfig tentWith(List<BondTentMix> mixes) {
        return tent("2.5", 3, mixes, yields());
    }

    @Test
    void validateBondTent_valid_passes() {
        assertThatCode(() -> ScenarioParamsValidator.validateBondTent(tentWith(List.of(balanced())), false))
                .doesNotThrowAnyException();
    }

    @Test
    void validateBondTent_null_passes() {
        assertThatCode(() -> ScenarioParamsValidator.validateBondTent(null, true)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("badCoverages")
    void validateBondTent_coverageNotHalfStep_throws(String coverage) {
        var config = tent(coverage, 3, List.of(balanced()), yields());

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(COVERAGE_MESSAGE);
    }

    static Stream<String> badCoverages() {
        return Stream.of("2.3", "0.5", "10.5");
    }

    @Test
    void validateBondTent_nullCoverage_throws() {
        var config = new BondTentConfig(null, 3, List.of(balanced()), yields());

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(COVERAGE_MESSAGE);
    }

    @Test
    void validateBondTent_boundaryCoverages_pass() {
        assertThatCode(() -> {
            ScenarioParamsValidator.validateBondTent(tent("1", 0, List.of(balanced()), yields()), null);
            ScenarioParamsValidator.validateBondTent(tent("10.0", 40, List.of(balanced()), yields()), null);
        }).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("badBuildUps")
    void validateBondTent_buildUpOutOfRange_throws(Integer buildUp) {
        var config = new BondTentConfig(new BigDecimal("2"), buildUp, List.of(balanced()), yields());

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(BUILD_UP_MESSAGE);
    }

    static Stream<Integer> badBuildUps() {
        return Stream.of(-1, 41, null);
    }

    @ParameterizedTest
    @MethodSource("badMixCounts")
    void validateBondTent_noMixesOrFour_throws(List<BondTentMix> mixes) {
        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(tentWith(mixes), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A bond tent needs one to three bond mixes");
    }

    static Stream<List<BondTentMix>> badMixCounts() {
        var m = balanced();
        return Stream.of(List.of(), List.of(m, m, m, m), (List<BondTentMix>) null);
    }

    @ParameterizedTest
    @MethodSource("badNames")
    void validateBondTent_blankOrLongName_throws(String name) {
        var config = tentWith(List.of(mix(name, "0.2", "0.3", "0.5", "0")));

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Each bond mix needs a name of at most 40 characters");
    }

    static Stream<String> badNames() {
        return Stream.of(" ", "x".repeat(41));
    }

    @Test
    void validateBondTent_duplicateMixNames_throws() {
        var config = tentWith(List.of(mix("Plan", "1", "0", "0", "0"), mix(" plan ", "0", "1", "0", "0")));

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mix names must be unique");
    }

    @Test
    void validateBondTent_reservedNoTentName_throws() {
        var config = tentWith(List.of(mix(" no TENT", "1", "0", "0", "0")));

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("\"No tent\" is reserved; choose another mix name");
    }

    @Test
    void validateBondTent_nullName_throws() {
        var config = tentWith(List.of(new BondTentMix(null, values("1", "0", "0", "0"))));

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Each bond mix needs a name of at most 40 characters");
    }

    @Test
    void validateBondTent_sharesNotSummingToOne_throws() {
        var config = tentWith(List.of(mix("Balanced", "0.2", "0.3", "0.4", "0")));

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bond mix 'Balanced' shares must each be between 0% and 100% and add up to 100%");
    }

    @Test
    void validateBondTent_shareNegative_throws() {
        var config = tentWith(List.of(mix("Balanced", "-0.2", "0.7", "0.5", "0")));

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shares must each be between 0% and 100%");
    }

    @Test
    void validateBondTent_missingShares_throws() {
        var config = tentWith(List.of(new BondTentMix("Balanced", null)));

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shares must each be between 0% and 100%");
    }

    @Test
    void validateBondTent_nullShareComponent_throws() {
        var config = tentWith(List.of(new BondTentMix("Balanced",
                new BondMaturityValues(BigDecimal.ONE, null, BigDecimal.ZERO, BigDecimal.ZERO))));

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shares must each be between 0% and 100%");
    }

    @ParameterizedTest
    @MethodSource("badYields")
    void validateBondTent_yieldOutOfRange_throws(BondMaturityValues badYields) {
        var config = tent("2.5", 3, List.of(balanced()), badYields);

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bond tent yields must each be between -2% and 20%");
    }

    static Stream<BondMaturityValues> badYields() {
        return Stream.of(
                values("0.25", "0.04", "0.04", "0.04"),
                values("0.04", "-0.03", "0.04", "0.04"),
                new BondMaturityValues(new BigDecimal("0.04"), null, new BigDecimal("0.04"), new BigDecimal("0.04")),
                null);
    }

    @Test
    void validateBondTent_yieldsAtBounds_pass() {
        var config = tent("2.5", 3, List.of(balanced()), values("-0.02", "0.20", "0", "0.05"));

        assertThatCode(() -> ScenarioParamsValidator.validateBondTent(config, null)).doesNotThrowAnyException();
    }

    @Test
    void validateBondTent_withDepressionYears_throws() {
        var config = tentWith(List.of(balanced()));

        assertThatThrownBy(() -> ScenarioParamsValidator.validateBondTent(config, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Turn off the 1928–1971 market history to use a bond tent: "
                        + "bond maturity data starts in 1954");
    }
}
