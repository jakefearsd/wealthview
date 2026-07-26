package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;

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
        var input = GuardrailOptimizationInputBuilder.builder()
                .withBirthYear(1962)
                .withInflationRate(INFLATION)
                .withAccounts(List.of())
                .withEssentialFloor(new BigDecimal("40000"))
                .withReturnMean(null)
                .withTrialCount(300)
                .withConfidenceLevel(new BigDecimal("0.90"))
                .withMaxAnnualAdjustmentRate(BigDecimal.ZERO)
                .withFilingStatus("single")
                .withWithdrawalOrder("taxable_first")
                .withBaseYear(2025)
                .withGateOnAdaptiveRules(true)
                .build();

        var resolved = HouseholdMcResolver.resolve(input, 2030, 22, INFLATION.doubleValue(), FilingStatus.SINGLE);

        assertThat(resolved.sim()).isNull();
        assertThat(resolved.survivorFactor()).isEqualTo(1.0);
        assertThat(resolved.inWindowTransitionIdx()).isEqualTo(-1);
        assertThat(resolved.context()).isNull();
    }

    /**
     * HP2: the household-keyed {@link HouseholdMcResolver#scaleFromTransition(double[],
     * TrialSimulator.HouseholdSim, double, int)} overload scales from the sim's transition index by
     * the factor exactly once — a null household is a no-op (single-person, byte-identical anchor).
     */
    @Test
    void scaleFromTransition_householdOverload_appliesFactorFromTransitionExactlyOnce() {
        var sim = new TrialSimulator.HouseholdSim(0, 0, 0, 75, 2, true, 0.5, 4,
                TrialSimulator.TaxableSeed.EMPTY);
        double[] values = {100, 100, 100, 100};

        HouseholdMcResolver.scaleFromTransition(values, sim, 0.75, 4);

        // Pre-transition untouched; from index 2 scaled by 0.75 (once — not 0.5625).
        assertThat(values).containsExactly(100.0, 100.0, 75.0, 75.0);
    }

    @Test
    void scaleFromTransition_householdOverload_nullHousehold_isNoOp() {
        double[] values = {100, 100, 100, 100};

        HouseholdMcResolver.scaleFromTransition(values, (TrialSimulator.HouseholdSim) null, 0.75, 4);

        assertThat(values).containsExactly(100.0, 100.0, 100.0, 100.0);
    }

    private static GuardrailOptimizationInput householdInput(int primaryBirthYear, int primaryDeathAge,
            int spouseBirthYear, int spouseDeathAge, LocalDate retirementDate, int endAge) {
        return GuardrailOptimizationInputBuilder.builder()
                .withRetirementDate(retirementDate)
                .withBirthYear(primaryBirthYear)
                .withEndAge(endAge)
                .withInflationRate(INFLATION)
                .withAccounts(List.of())
                .withEssentialFloor(new BigDecimal("40000"))
                .withReturnMean(null)
                .withTrialCount(300)
                .withConfidenceLevel(new BigDecimal("0.90"))
                .withMaxAnnualAdjustmentRate(BigDecimal.ZERO)
                .withFilingStatus("married_filing_jointly")
                .withWithdrawalOrder("taxable_first")
                .withBaseYear(2025)
                .withGateOnAdaptiveRules(true)
                .withSpouseBirthYear(spouseBirthYear)
                .withPrimaryDeathAge(primaryDeathAge)
                .withSpouseDeathAge(spouseDeathAge)
                .withSurvivorSpendingFactor(new BigDecimal("0.75"))
                .build();
    }

    // === HP3 Part B-2: defensive survivor_spending_factor clamp at the engine seam ===
    // ScenarioCrudService enforces [0.5, 1.0] only at write time (validateSurvivorSpendingFactor);
    // a directly-constructed GuardrailOptimizationInput (a test, a future write path, a stale
    // persisted value written before validation existed) bypasses that check entirely and this
    // resolver previously trusted the caller's raw value. Clamp defensively at the seam too.

    private static GuardrailOptimizationInput householdInputWithFactor(BigDecimal survivorSpendingFactor) {
        // Same "first death inside the window" shape as resolve_firstDeathInsideRetirementWindow...
        // above (primary dies 2040, ten years into the 2030 window) -- only the factor varies.
        return GuardrailOptimizationInputBuilder.builder()
                .withBirthYear(1962)
                .withInflationRate(INFLATION)
                .withAccounts(List.of())
                .withEssentialFloor(new BigDecimal("40000"))
                .withReturnMean(null)
                .withTrialCount(300)
                .withConfidenceLevel(new BigDecimal("0.90"))
                .withMaxAnnualAdjustmentRate(BigDecimal.ZERO)
                .withFilingStatus("married_filing_jointly")
                .withWithdrawalOrder("taxable_first")
                .withBaseYear(2025)
                .withGateOnAdaptiveRules(true)
                .withSpouseBirthYear(1968)
                .withPrimaryDeathAge(78)
                .withSpouseDeathAge(90)
                .withSurvivorSpendingFactor(survivorSpendingFactor)
                .build();
    }

    @Test
    void resolve_survivorSpendingFactorAboveOne_clampsToOne() {
        var resolved = HouseholdMcResolver.resolve(householdInputWithFactor(new BigDecimal("1.5")),
                2030, 22, INFLATION.doubleValue(), FilingStatus.MARRIED_FILING_JOINTLY);

        assertThat(resolved.survivorFactor()).isEqualTo(1.0);
    }

    @Test
    void resolve_survivorSpendingFactorBelowHalf_clampsToHalf() {
        var resolved = HouseholdMcResolver.resolve(householdInputWithFactor(new BigDecimal("0.2")),
                2030, 22, INFLATION.doubleValue(), FilingStatus.MARRIED_FILING_JOINTLY);

        assertThat(resolved.survivorFactor()).isEqualTo(0.5);
    }

    @Test
    void resolve_survivorSpendingFactorAtBounds_passesThroughUnclamped() {
        // Boundary-inclusive: exactly 0.5 or exactly 1.0 must NOT be nudged by the clamp.
        assertThat(HouseholdMcResolver.resolve(householdInputWithFactor(BigDecimal.ONE), 2030, 22,
                INFLATION.doubleValue(), FilingStatus.MARRIED_FILING_JOINTLY).survivorFactor()).isEqualTo(1.0);
        assertThat(HouseholdMcResolver.resolve(householdInputWithFactor(new BigDecimal("0.5")), 2030, 22,
                INFLATION.doubleValue(), FilingStatus.MARRIED_FILING_JOINTLY).survivorFactor()).isEqualTo(0.5);
    }
}
