package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentMix;
import com.wealthview.core.projection.dto.ScenarioParamsSource;

/**
 * Validates the household, stochastic-mortality, and birth-month fields of a scenario's params.
 * Extracted from {@link ScenarioCrudService} so that service's cyclomatic complexity stays within
 * PMD's class threshold as new params-level validation lands; behavior is unchanged.
 */
public final class ScenarioParamsValidator {

    private static final int MIN_DEATH_AGE = 50;
    private static final int MAX_DEATH_AGE = 120;
    private static final BigDecimal MIN_SURVIVOR_SPENDING_FACTOR = new BigDecimal("0.5");
    private static final BigDecimal MAX_SURVIVOR_SPENDING_FACTOR = BigDecimal.ONE;
    private static final Set<String> VALID_SEXES = Set.of("male", "female");
    private static final int MIN_LONGEVITY_CONDITIONAL_AGE = 80;
    private static final int MAX_LONGEVITY_CONDITIONAL_AGE = 110;
    private static final int MIN_BIRTH_MONTH = 1;
    private static final int MAX_BIRTH_MONTH = 12;
    private static final BigDecimal MAX_HEIR_TAX_RATE = new BigDecimal("0.50");

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal MIN_TENT_COVERAGE_YEARS = BigDecimal.ONE;
    private static final BigDecimal MAX_TENT_COVERAGE_YEARS = BigDecimal.TEN;
    private static final int MAX_TENT_BUILD_UP_START_YEARS = 40;
    private static final int MAX_TENT_MIXES = 3;
    private static final int MAX_TENT_MIX_NAME_LENGTH = 40;
    private static final String RESERVED_NO_TENT_NAME = "no tent";
    private static final BigDecimal SHARE_SUM_TOLERANCE = new BigDecimal("0.000001");
    private static final BigDecimal MIN_TENT_YIELD = new BigDecimal("-0.02");
    private static final BigDecimal MAX_TENT_YIELD = new BigDecimal("0.20");

    private ScenarioParamsValidator() {
    }

    /** Runs every params-level validation; throws {@link IllegalArgumentException} on the first violation. */
    public static void validate(ScenarioParamsSource request) {
        validateHouseholdFields(request);
        validateStochasticMortalityFields(request);
        validateBirthMonths(request);
        validateHeirTaxRate(request.heirTaxRate());
        validateBondTent(request.bondTent(), request.includeDepressionYears());
    }

    /**
     * Household/survivor modeling (sub-project A): death ages (when set) must be plausible
     * planning ages; every spouse-scoped or household-only field requires {@code spouse_birth_year}
     * to be set (a household field with no spouse is meaningless and almost certainly a client
     * bug); the survivor spending factor (when set) must be in its documented 0.5-1.0 range.
     * {@code primary_death_age} is exempt from the spouse-presence check but is currently INERT
     * for single-person scenarios: {@code ProjectionInputBuilder.resolveHousehold} builds the
     * degenerate {@code HouseholdContext.single(...)} (empty transition/second-death optionals),
     * so no truncation occurs without a spouse — deliberately preserving pre-household behavior
     * for singles. Range-validated anyway so a stored value is sane if a future change wires it.
     */
    private static void validateHouseholdFields(ScenarioParamsSource request) {
        validateDeathAge(request.primaryDeathAge(), "primary_death_age");
        validateDeathAge(request.spouseDeathAge(), "spouse_death_age");
        validateSurvivorSpendingFactor(request.survivorSpendingFactor());
        if (request.spouseBirthYear() == null && (request.spouseDeathAge() != null
                || request.survivorSpendingFactor() != null || request.communityProperty() != null)) {
            throw new IllegalArgumentException(
                    "spouse_death_age, survivor_spending_factor, and community_property require "
                            + "spouse_birth_year to be set");
        }
    }

    private static void validateDeathAge(Integer deathAge, String fieldName) {
        if (deathAge == null) {
            return;
        }
        if (deathAge < MIN_DEATH_AGE || deathAge > MAX_DEATH_AGE) {
            throw new IllegalArgumentException(fieldName + " must be between " + MIN_DEATH_AGE
                    + " and " + MAX_DEATH_AGE);
        }
    }

    private static void validateSurvivorSpendingFactor(BigDecimal survivorSpendingFactor) {
        if (survivorSpendingFactor == null) {
            return;
        }
        if (survivorSpendingFactor.compareTo(MIN_SURVIVOR_SPENDING_FACTOR) < 0
                || survivorSpendingFactor.compareTo(MAX_SURVIVOR_SPENDING_FACTOR) > 0) {
            throw new IllegalArgumentException("survivor_spending_factor must be between 0.5 and 1.0");
        }
    }

    /**
     * Stochastic-mortality modeling (sub-project B): {@code primary_sex}/{@code spouse_sex} (when
     * present) must be a recognized value -- a garbled or unsupported sex would silently fall back
     * to blended qx in {@code MortalityTable.qx}, masking a client bug. {@code spouse_sex} is
     * spouse-scoped like every other household-only field validated in
     * {@link #validateHouseholdFields}, so it requires {@code spouse_birth_year}.
     * {@code longevity_conditional_age} (when present) must be a plausible SSA-adjacent planning
     * age for the "the survivor lives to this age" success metric (i.e. the last surviving spouse
     * reaches that age -- the metric filters on the survivor's death age).
     */
    private static void validateStochasticMortalityFields(ScenarioParamsSource request) {
        validateSex(request.primarySex(), "primary_sex");
        validateSex(request.spouseSex(), "spouse_sex");
        if (request.spouseSex() != null && request.spouseBirthYear() == null) {
            throw new IllegalArgumentException("spouse_sex requires spouse_birth_year to be set");
        }
        validateLongevityConditionalAge(request.longevityConditionalAge());
    }

    private static void validateSex(String sex, String fieldName) {
        if (sex == null) {
            return;
        }
        if (!VALID_SEXES.contains(sex)) {
            throw new IllegalArgumentException(fieldName + " must be one of: " + VALID_SEXES);
        }
    }

    private static void validateLongevityConditionalAge(Integer longevityConditionalAge) {
        if (longevityConditionalAge == null) {
            return;
        }
        if (longevityConditionalAge < MIN_LONGEVITY_CONDITIONAL_AGE
                || longevityConditionalAge > MAX_LONGEVITY_CONDITIONAL_AGE) {
            throw new IllegalArgumentException("longevity_conditional_age must be between "
                    + MIN_LONGEVITY_CONDITIONAL_AGE + " and " + MAX_LONGEVITY_CONDITIONAL_AGE);
        }
    }

    /**
     * Phase 1a: birth months (when present) must be 1-12. Each month needs its matching birth
     * year: a month with no year is meaningless, and the engine would pair it with the
     * {@code currentYear - 35} fallback birth year.
     */
    private static void validateBirthMonths(ScenarioParamsSource request) {
        validateBirthMonth(request.birthMonth(), "birth_month");
        validateBirthMonth(request.spouseBirthMonth(), "spouse_birth_month");
        if (request.birthMonth() != null && request.birthYear() == null) {
            throw new IllegalArgumentException("birth_month requires birth_year to be set");
        }
        if (request.spouseBirthMonth() != null && request.spouseBirthYear() == null) {
            throw new IllegalArgumentException("spouse_birth_month requires spouse_birth_year to be set");
        }
    }

    private static void validateBirthMonth(Integer birthMonth, String fieldName) {
        if (birthMonth == null) {
            return;
        }
        if (birthMonth < MIN_BIRTH_MONTH || birthMonth > MAX_BIRTH_MONTH) {
            throw new IllegalArgumentException(fieldName + " must be between " + MIN_BIRTH_MONTH
                    + " and " + MAX_BIRTH_MONTH);
        }
    }

    /** Phase 1a: the heirs' assumed ordinary tax rate (when present) must be within 0 to 0.50 inclusive. */
    private static void validateHeirTaxRate(BigDecimal heirTaxRate) {
        if (heirTaxRate == null) {
            return;
        }
        if (heirTaxRate.signum() < 0 || heirTaxRate.compareTo(MAX_HEIR_TAX_RATE) > 0) {
            throw new IllegalArgumentException("heir_tax_rate must be between 0 and 0.50");
        }
    }

    /**
     * Bond tent plan 3: validates a tent configuration (no-op when {@code tent} is null). Bond
     * maturity data starts in 1954, so a tent cannot be combined with the 1928-1971 history.
     */
    public static void validateBondTent(@Nullable BondTentConfig tent, @Nullable Boolean includeDepressionYears) {
        if (tent == null) {
            return;
        }
        if (Boolean.TRUE.equals(includeDepressionYears)) {
            throw new IllegalArgumentException("Turn off the 1928–1971 market history to use a bond tent: "
                    + "bond maturity data starts in 1954");
        }
        validateTentCoverage(tent.coverageYears());
        validateTentBuildUp(tent.buildUpStartYears());
        validateTentMixes(tent);
        validateTentYields(tent.yields());
    }

    private static void validateTentCoverage(BigDecimal coverageYears) {
        if (coverageYears == null
                || coverageYears.compareTo(MIN_TENT_COVERAGE_YEARS) < 0
                || coverageYears.compareTo(MAX_TENT_COVERAGE_YEARS) > 0
                || coverageYears.multiply(TWO).remainder(BigDecimal.ONE).signum() != 0) {
            throw new IllegalArgumentException(
                    "Bond tent coverage must be between 1 and 10 years, in half-year steps");
        }
    }

    private static void validateTentBuildUp(Integer buildUpStartYears) {
        if (buildUpStartYears == null || buildUpStartYears < 0 || buildUpStartYears > MAX_TENT_BUILD_UP_START_YEARS) {
            throw new IllegalArgumentException(
                    "Bond tent build-up start must be between 0 and 40 years before retirement");
        }
    }

    private static void validateTentMixes(BondTentConfig tent) {
        if (tent.mixes() == null || tent.mixes().isEmpty() || tent.mixes().size() > MAX_TENT_MIXES) {
            throw new IllegalArgumentException("A bond tent needs one to three bond mixes");
        }
        var seen = new HashSet<String>();
        for (var mix : tent.mixes()) {
            validateTentMix(mix);
            var key = mix.name().trim().toLowerCase(Locale.ROOT);
            if (RESERVED_NO_TENT_NAME.equals(key)) {
                throw new IllegalArgumentException("\"No tent\" is reserved; choose another mix name");
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Mix names must be unique");
            }
        }
    }

    private static void validateTentMix(BondTentMix mix) {
        if (mix == null || mix.name() == null || mix.name().isBlank()
                || mix.name().length() > MAX_TENT_MIX_NAME_LENGTH) {
            throw new IllegalArgumentException("Each bond mix needs a name of at most 40 characters");
        }
        if (!hasValidShares(mix.shares())) {
            throw new IllegalArgumentException("Bond mix '" + mix.name()
                    + "' shares must each be between 0% and 100% and add up to 100%");
        }
    }

    private static boolean hasValidShares(BondMaturityValues shares) {
        if (shares == null) {
            return false;
        }
        for (var maturity : BondMaturity.values()) {
            var share = shares.get(maturity);
            if (share == null || share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0) {
                return false;
            }
        }
        return shares.sum().subtract(BigDecimal.ONE).abs().compareTo(SHARE_SUM_TOLERANCE) <= 0;
    }

    private static void validateTentYields(BondMaturityValues yields) {
        var valid = yields != null;
        if (valid) {
            for (var maturity : BondMaturity.values()) {
                var value = yields.get(maturity);
                valid &= value != null
                        && value.compareTo(MIN_TENT_YIELD) >= 0 && value.compareTo(MAX_TENT_YIELD) <= 0;
            }
        }
        if (!valid) {
            throw new IllegalArgumentException("Bond tent yields must each be between -2% and 20%");
        }
    }
}
