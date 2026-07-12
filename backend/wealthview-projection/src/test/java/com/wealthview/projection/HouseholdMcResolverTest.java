package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.tax.FilingStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Household task 8 (T6-review, item 4): pins the MC engine's "already-survivor-by-retirement"
 * clamping behavior — a first death that lands BEFORE the MC's own retirement-anchored window
 * (index 0 == {@code retirementYear}) resolves to {@code inWindowTransitionIdx == 0}, not a
 * negative/out-of-window sentinel, so rollover/step-up/single-filing tables apply from trial year 0.
 * See the class-level rationale in {@link HouseholdMcResolver#resolve} (the deterministic-engine
 * scope difference this intentionally diverges from).
 */
class HouseholdMcResolverTest {

    private static final BigDecimal INFLATION = BigDecimal.ZERO;

    @Test
    void resolve_firstDeathBeforeRetirementWindow_clampsTransitionIndexToZero() {
        // Primary born 1962, dies at 65 (calendar 2027) -- three years BEFORE retirement (2030).
        // Spouse born 1968, dies at 85 (calendar 2053) -- beyond the 2052 horizon (never transitions
        // on its own; irrelevant to this pin).
        var input = householdInput(1962, 65, 1968, 85, LocalDate.of(2030, 1, 1), 90);

        var resolved = HouseholdMcResolver.resolve(input, 2030, 22, INFLATION.doubleValue(),
                FilingStatus.MARRIED_FILING_JOINTLY);

        assertThat(resolved.inWindowTransitionIdx()).isZero();
        assertThat(resolved.postStatus()).isEqualTo(FilingStatus.SINGLE);
        assertThat(resolved.sim()).isNotNull();
        assertThat(resolved.sim().transitionYearIndex()).isZero();
        // Primary died first -- survivor is the spouse.
        assertThat(resolved.sim().survivorIsPrimary()).isFalse();
        assertThat(resolved.survivorSources()).isNotNull();
        assertThat(resolved.context()).isNotNull();
        assertThat(resolved.context().isHousehold()).isTrue();
    }

    @Test
    void resolve_firstDeathInsideRetirementWindow_usesItsActualYearIndex_notClampedToZero() {
        // Contrast fixture: primary dies at 78 (calendar 2040), ten years INTO the 2030-retirement
        // window -- the transition index must be the true offset (10), not the pre-window clamp (0).
        var input = householdInput(1962, 78, 1968, 90, LocalDate.of(2030, 1, 1), 90);

        var resolved = HouseholdMcResolver.resolve(input, 2030, 22, INFLATION.doubleValue(),
                FilingStatus.MARRIED_FILING_JOINTLY);

        assertThat(resolved.inWindowTransitionIdx()).isEqualTo(10);
        assertThat(resolved.sim().transitionYearIndex()).isEqualTo(10);
    }

    @Test
    void resolve_singlePersonInput_returnsSingleWithNoTransition() {
        var input = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1962, 90, INFLATION,
                List.of(), List.of(),
                new BigDecimal("40000"), BigDecimal.ZERO, null,
                300, new BigDecimal("0.90"), List.of(), 42L, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, BigDecimal.ZERO, "single", "taxable_first",
                false, null, null, 5, null, null, null, null, 2025, false, null, true,
                null, null, null, null, false);

        var resolved = HouseholdMcResolver.resolve(input, 2030, 22, INFLATION.doubleValue(), FilingStatus.SINGLE);

        assertThat(resolved.sim()).isNull();
        assertThat(resolved.survivorFactor()).isEqualTo(1.0);
        assertThat(resolved.inWindowTransitionIdx()).isEqualTo(-1);
        assertThat(resolved.context()).isNull();
    }

    private static GuardrailOptimizationInput householdInput(int primaryBirthYear, int primaryDeathAge,
            int spouseBirthYear, int spouseDeathAge, LocalDate retirementDate, int endAge) {
        return new GuardrailOptimizationInput(
                retirementDate, primaryBirthYear, endAge, INFLATION,
                List.of(), List.of(),
                new BigDecimal("40000"), BigDecimal.ZERO, null,
                300, new BigDecimal("0.90"), List.of(), 42L, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, BigDecimal.ZERO, "married_filing_jointly", "taxable_first",
                false, null, null, 5, null, null, null, null, 2025, false, null, true,
                spouseBirthYear, primaryDeathAge, spouseDeathAge, new BigDecimal("0.75"), false);
    }
}
