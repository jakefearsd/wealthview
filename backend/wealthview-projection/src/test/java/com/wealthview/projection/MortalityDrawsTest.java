package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.mortality.MortalityTable;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sub-project B, task 5: the per-trial death-age precompute. Pins that
 * {@link MortalityDrawGenerator#generate} samples each spouse conditional on being alive at their
 * retirement-year age and maps the sampled ages to the SAME transition/truncate indices the
 * fixed-death {@link HouseholdMcResolver#resolve} derives from {@code HouseholdContext.of}, while
 * carrying the RAW (un-horizon-clamped) sampled death ages for the task-7 longevity metrics.
 */
class MortalityDrawsTest {

    /**
     * Sex-keyed step table forcing a MALE to die at exactly 80 and a FEMALE at exactly 90. Per the
     * task-4 caveat, {@link MortalityTable#qx} returns 1.0 for ANY untabulated age at/above the
     * table's minimum tabulated age, so every intermediate age must be explicitly tabulated at 0.0
     * (a sparse two-point table would force death at the first untabulated age instead). Ages 60..79
     * male / 60..89 female are zero-hazard; the terminal death age carries qx 1.0.
     */
    private static MortalityTable stepTableMale80Female90() {
        Map<Integer, Double> maleQx = new HashMap<>();
        for (int age = 60; age < 80; age++) {
            maleQx.put(age, 0.0);
        }
        maleQx.put(80, 1.0);
        Map<Integer, Double> femaleQx = new HashMap<>();
        for (int age = 60; age < 90; age++) {
            femaleQx.put(age, 0.0);
        }
        femaleQx.put(90, 1.0);
        return new MortalityTable(maleQx, femaleQx);
    }

    @Test
    void generate_primaryDies80SpouseDies90_derivesTransitionTruncateAndRawAges() {
        // Both born 1990, retire 2050 -> each age 60 at retirement (the sampling start age). Primary
        // is male (dies 80 -> calendar 2070), spouse female (dies 90 -> calendar 2080). endAge 95 ->
        // horizon end 2085, years = 95 - 60 = 35. First death (primary) index = 2070 - 2050 = 20;
        // second death (spouse) truncate = min(35, 2080 - 2050 + 1) = 31; survivor is the spouse.
        var input = householdMortalityInput(1990, 1990, LocalDate.of(2050, 1, 1), 95,
                stepTableMale80Female90());

        var draws = MortalityDrawGenerator.generate(input, 2050, 35, new Random(123), 50);

        assertThat(draws.transitionIdx()).containsOnly(20);
        assertThat(draws.truncateIdx()).containsOnly(31);
        assertThat(draws.survivorIsPrimary()).containsOnly(false);
        assertThat(draws.firstDeathAge()).containsOnly(80);
        assertThat(draws.secondDeathAge()).containsOnly(90);
    }

    @Test
    void generate_bothDeathsBeyondHorizon_clampsIndicesToSentinelButKeepsRawAges() {
        // Same primary-80 / spouse-90 table, but endAge 70 -> horizon end 1990 + 70 = 2060, BEFORE
        // both death years (2070, 2080). years = 70 - 60 = 10. HouseholdContext.of clamps both the
        // transition and second-death years to empty, so the indices fall back to the `years`
        // sentinel (no in-window transition; trial runs the full horizon) -- yet the RAW sampled ages
        // must still be the true 80/90 (un-horizon-clamped) for the task-7 longevity metrics.
        var input = householdMortalityInput(1990, 1990, LocalDate.of(2050, 1, 1), 70,
                stepTableMale80Female90());

        var draws = MortalityDrawGenerator.generate(input, 2050, 10, new Random(7), 40);

        assertThat(draws.transitionIdx()).containsOnly(10);
        assertThat(draws.truncateIdx()).containsOnly(10);
        assertThat(draws.survivorIsPrimary()).containsOnly(false);
        assertThat(draws.firstDeathAge()).containsOnly(80);
        assertThat(draws.secondDeathAge()).containsOnly(90);
    }

    private static GuardrailOptimizationInput householdMortalityInput(int primaryBirthYear, int spouseBirthYear,
            LocalDate retirementDate, int endAge, MortalityTable table) {
        return GuardrailOptimizationInputBuilder.builder()
                .withRetirementDate(retirementDate)
                .withBirthYear(primaryBirthYear)
                .withEndAge(endAge)
                .withInflationRate(BigDecimal.ZERO)
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
                .withSurvivorSpendingFactor(new BigDecimal("0.75"))
                .withStochasticMortality(true)
                .withPrimarySex("male")
                .withSpouseSex("female")
                .withMortalityTable(table)
                .build();
    }
}
