package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.household.HouseholdContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Household task 8 (T7 gap): the MC engine's {@link IncomeProjector} precompute must apply the SAME
 * owner-age income windows the deterministic engine's {@code IncomeSourceProcessor} already applies
 * (task 7) — a spouse-owned age-gated source activates at the SPOUSE's own age, not the uniform
 * retirement-anchored primary age. Task 7 explicitly flagged {@code computeDeterministic}/{@code
 * socialSecurityBenefitByYear}/{@code computeRentalAwareTaxable} as an out-of-scope latent gap in the
 * MC engine; this class closes it, mirroring {@code IncomeSourceProcessorTest}'s household fixture
 * (primary born 1958, spouse born 1970 — a 12-year gap large enough that evaluating a source's window
 * against the wrong person's age produces a materially different, observable activation year).
 */
class IncomeProjectorHouseholdTest {

    private static final HouseholdContext AGE_GAP_HOUSEHOLD = HouseholdContext.of(1958, 90, 1970, 90, 2070);

    // === computeDeterministic ===

    @Test
    void computeDeterministic_householdNull_spouseOwnedSourceIncorrectlyUsesPrimaryAge_theT7GapMadeConcrete() {
        // RED against the pre-task-8 MC behavior: with no household threaded (the 5-arg overload,
        // every pre-task-8 call site), a spouse-owned source with start_age 65 wrongly activates
        // once the PRIMARY reaches 66 (retirementAge=66) even though the spouse — its actual owner —
        // is only 54 that year (1958 vs 1970 birth years). Pinned to prove the household-aware
        // overload below is a genuine fix, not a no-op.
        var spouseSource = ownedSource(65, null, "spouse");

        var result = IncomeProjector.computeDeterministic(List.of(spouseSource), 66, 1, 0, 0.0);

        assertThat(result[0].totalIncome()).isEqualTo(24_000.0, within(0.01));
    }

    @Test
    void computeDeterministic_householdBothAlive_spouseOwnedSourceNotYetActiveAtSpouseRealAge() {
        // Same source, same primary age (66, retirement-anchored), WITH the household threaded: the
        // spouse (born 1970) is only 54 in the corresponding calendar year (1958 + 66 = 2024,
        // spouse age = 2024 - 1970 = 54) — below start_age 65 — so the source must NOT be active.
        var spouseSource = ownedSource(65, null, "spouse");

        var result = IncomeProjector.computeDeterministic(List.of(spouseSource), 66, 1, 0, 0.0,
                1958, AGE_GAP_HOUSEHOLD);

        assertThat(result[0].totalIncome()).isZero();
        assertThat(result[0].taxableIncome()).isZero();
    }

    @Test
    void computeDeterministic_householdBothAlive_spouseOwnedSourceActivatesAtSpouseOwnRealAge() {
        // 12 years later (primary age 78, calendar year 2036), the spouse turns 66 — past the
        // start_age boundary — full amount active.
        var spouseSource = ownedSource(65, null, "spouse");

        var result = IncomeProjector.computeDeterministic(List.of(spouseSource), 78, 1, 0, 0.0,
                1958, AGE_GAP_HOUSEHOLD);

        assertThat(result[0].totalIncome()).isEqualTo(24_000.0, within(0.01));
        assertThat(result[0].taxableIncome()).isEqualTo(24_000.0, within(0.01));
    }

    @Test
    void computeDeterministic_householdBothAlive_primaryOwnedSourceStillEvaluatesAgainstPrimaryAge() {
        // A primary-owned source is unaffected by threading a household — it keeps evaluating
        // against the uniform retirement-anchored age exactly as before.
        var primarySource = ownedSource(65, null, "primary");

        var result = IncomeProjector.computeDeterministic(List.of(primarySource), 66, 1, 0, 0.0,
                1958, AGE_GAP_HOUSEHOLD);

        assertThat(result[0].totalIncome()).isEqualTo(24_000.0, within(0.01));
    }

    // === socialSecurityBenefitByYear ===

    @Test
    void socialSecurityBenefitByYear_householdBothAlive_spouseOwnedBenefitNotYetActiveAtSpouseRealAge() {
        var spouseSs = ownedSource(65, null, "spouse", IncomeSourceType.SOCIAL_SECURITY, new BigDecimal("18000"));

        var result = IncomeProjector.socialSecurityBenefitByYear(List.of(spouseSs), 66, 1, 0, 0.0,
                1958, AGE_GAP_HOUSEHOLD);

        assertThat(result[0]).isZero();
    }

    @Test
    void socialSecurityBenefitByYear_householdBothAlive_spouseOwnedBenefitActivatesAtSpouseOwnRealAge() {
        var spouseSs = ownedSource(65, null, "spouse", IncomeSourceType.SOCIAL_SECURITY, new BigDecimal("18000"));

        var result = IncomeProjector.socialSecurityBenefitByYear(List.of(spouseSs), 78, 1, 0, 0.0,
                1958, AGE_GAP_HOUSEHOLD);

        assertThat(result[0]).isEqualTo(18_000.0, within(0.01));
    }

    @Test
    void socialSecurityBenefitByYear_householdNull_matchesUniformAgeBehaviorOfFiveArgOverload() {
        var spouseSs = ownedSource(65, null, "spouse", IncomeSourceType.SOCIAL_SECURITY, new BigDecimal("18000"));

        double[] fiveArg = IncomeProjector.socialSecurityBenefitByYear(List.of(spouseSs), 66, 1, 0, 0.0);
        double[] sevenArgNullHousehold = IncomeProjector.socialSecurityBenefitByYear(
                List.of(spouseSs), 66, 1, 0, 0.0, 1958, null);

        assertThat(sevenArgNullHousehold).containsExactly(fiveArg);
        assertThat(fiveArg[0]).isEqualTo(18_000.0, within(0.01)); // uniform primary-age 66 >= start_age 65
    }

    // === computeRentalAwareTaxable ===

    @Test
    void computeRentalAwareTaxable_householdBothAlive_spouseOwnedRentalNotYetActiveAtSpouseRealAge() {
        var spouseRental = ownedSource(65, null, "spouse", IncomeSourceType.RENTAL_PROPERTY, new BigDecimal("12000"));
        double[] baseTaxableIncome = {5_000.0};

        var result = IncomeProjector.computeRentalAwareTaxable(
                baseTaxableIncome, List.of(spouseRental), 66, 1958, 1, AGE_GAP_HOUSEHOLD);

        // Spouse (54) hasn't reached start_age 65 yet -- no rental adjustment applied, base unchanged.
        assertThat(result[0]).isEqualTo(5_000.0, within(0.01));
    }

    @Test
    void computeRentalAwareTaxable_householdBothAlive_spouseOwnedRentalActivatesAtSpouseOwnRealAge() {
        var spouseRental = ownedSource(65, null, "spouse", IncomeSourceType.RENTAL_PROPERTY, new BigDecimal("12000"));
        double[] baseTaxableIncome = {5_000.0};

        var result = IncomeProjector.computeRentalAwareTaxable(
                baseTaxableIncome, List.of(spouseRental), 78, 1958, 1, AGE_GAP_HOUSEHOLD);

        // Spouse (66) is past start_age 65 -- the rental's net taxable income (no expenses/depreciation
        // configured, so the full 12,000 gross) is added on top of the base.
        assertThat(result[0]).isEqualTo(17_000.0, within(0.01));
    }

    private static ProjectionIncomeSourceInput ownedSource(int startAge, Integer endAge, String owner) {
        return ownedSource(startAge, endAge, owner, IncomeSourceType.OTHER, new BigDecimal("24000"));
    }

    private static ProjectionIncomeSourceInput ownedSource(int startAge, Integer endAge, String owner,
                                                            IncomeSourceType incomeType, BigDecimal annualAmount) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Test " + incomeType.getValue(), incomeType, annualAmount,
                startAge, endAge, BigDecimal.ZERO, false, "taxable",
                null, null, null, null, null, null, owner, BigDecimal.ONE);
    }
}
