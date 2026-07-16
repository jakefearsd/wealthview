package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.household.PersonId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Household task 7 rework: {@link SurvivorIncomeAdjuster} now relabels a surviving deceased-owned
 * source's {@code owner} to the survivor (instead of shifting its {@code start_age}/{@code end_age}
 * by a fixed birth-year gap) -- see that class's Javadoc. These tests pin the relabeling directly and
 * the resulting age-window continuity across the transition boundary, for BOTH survivor orderings.
 */
class SurvivorIncomeAdjusterTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int PRIMARY_BIRTH = 1958;
    private static final int SPOUSE_BIRTH = 1970; // 12-year gap

    private static ProjectionIncomeSourceInput pension(int startAge, Integer endAge, String owner,
                                                        String survivorPercent) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Pension", IncomeSourceType.OTHER, new BigDecimal("24000"),
                startAge, endAge, ZERO, false, "taxable",
                null, null, null, null, null, null, owner, new BigDecimal(survivorPercent));
    }

    private static ProjectionIncomeSourceInput find(List<ProjectionIncomeSourceInput> sources, String owner) {
        return sources.stream().filter(s -> owner.equals(s.owner())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + owner + "-owned source in " + sources));
    }

    private static ProjectionIncomeSourceInput ss(UUID id, String amount, String inflationRate, String owner) {
        return new ProjectionIncomeSourceInput(id, "SS-" + owner, IncomeSourceType.SOCIAL_SECURITY,
                new BigDecimal(amount), 62, null, new BigDecimal(inflationRate), false, "taxable",
                null, null, null, null, null, null, owner, BigDecimal.ONE);
    }

    // === Ordering A: survivor is the SPOUSE (primary dies first) ===

    @Test
    void adjust_survivorIsSpouse_deceasedOwnedSourceRelabeledToSpouseWithAgesUnchanged() {
        var household = HouseholdContext.of(PRIMARY_BIRTH, 85, SPOUSE_BIRTH, 95, 2070);
        assertThat(household.survivor()).isEqualTo(PersonId.SPOUSE);
        var deceasedOwned = pension(62, 85, "primary", "0.5"); // primary-owned, primary dies

        var adjusted = SurvivorIncomeAdjuster.adjust(
                List.of(deceasedOwned), household, 2043, 2020, ZERO);

        var result = find(adjusted, "spouse");
        assertThat(result.startAge()).isEqualTo(62);   // UNCHANGED -- no age shift
        assertThat(result.endAge()).isEqualTo(85);     // UNCHANGED -- no age shift
        assertThat(result.annualAmount()).isEqualByComparingTo(new BigDecimal("12000")); // 24000 * 0.5
    }

    @Test
    void adjust_survivorIsSpouse_survivorOwnedSourcePassesThroughIdentical() {
        var household = HouseholdContext.of(PRIMARY_BIRTH, 85, SPOUSE_BIRTH, 95, 2070);
        var survivorOwned = pension(60, null, "spouse", "1.0");

        var adjusted = SurvivorIncomeAdjuster.adjust(
                List.of(survivorOwned), household, 2043, 2020, ZERO);

        assertThat(adjusted).containsExactly(survivorOwned); // reduces to identity, per class Javadoc
    }

    // === Ordering B: survivor is the PRIMARY (spouse dies first) ===

    @Test
    void adjust_survivorIsPrimary_deceasedOwnedSourceRelabeledToPrimaryWithAgesUnchanged() {
        var household = HouseholdContext.of(PRIMARY_BIRTH, 95, SPOUSE_BIRTH, 60, 2070);
        assertThat(household.survivor()).isEqualTo(PersonId.PRIMARY);
        var deceasedOwned = pension(58, 82, "spouse", "0.75"); // spouse-owned, spouse dies

        var adjusted = SurvivorIncomeAdjuster.adjust(
                List.of(deceasedOwned), household, 2030, 2020, ZERO);

        var result = find(adjusted, "primary");
        assertThat(result.startAge()).isEqualTo(58);
        assertThat(result.endAge()).isEqualTo(82);
        assertThat(result.annualAmount()).isEqualByComparingTo(new BigDecimal("18000")); // 24000 * 0.75
    }

    @Test
    void adjust_survivorIsPrimary_survivorOwnedSourcePassesThroughIdentical() {
        var household = HouseholdContext.of(PRIMARY_BIRTH, 95, SPOUSE_BIRTH, 60, 2070);
        var survivorOwned = pension(60, null, "primary", "1.0");

        var adjusted = SurvivorIncomeAdjuster.adjust(
                List.of(survivorOwned), household, 2030, 2020, ZERO);

        assertThat(adjusted).containsExactly(survivorOwned);
    }

    // === Age-window continuity across the transition boundary, both orderings ===
    // The relabeled source's ORIGINAL start_age/end_age, evaluated against the SURVIVOR's real age
    // via IncomeYearMath.resolveSourceAge, must activate/deactivate on the SURVIVOR's own age
    // timeline consistently in every post-transition year -- not just at the transition year itself.

    @Test
    void continuity_survivorIsSpouse_deceasedOwnedSourceTracksSpouseAgeEveryYearAfterTransition() {
        var household = HouseholdContext.of(PRIMARY_BIRTH, 85, SPOUSE_BIRTH, 95, 2070);
        int transitionYear = household.transitionYear().orElseThrow(); // 2043 (primary dies at 85)
        var deceasedOwned = pension(62, 85, "primary", "1.0"); // active while owner's age in [62, 85]

        var adjusted = SurvivorIncomeAdjuster.adjust(
                List.of(deceasedOwned), household, transitionYear, 2020, ZERO);
        var relabeled = find(adjusted, "spouse");

        // Spouse (born 1970) is 73 at the transition year (2043) -- inside [62, 85] -- active.
        assertThat(isActive(relabeled, household, transitionYear)).isTrue();
        // A decade later (2053, spouse age 83): still inside the window -- active.
        assertThat(isActive(relabeled, household, transitionYear + 10)).isTrue();
        // 2055 (spouse age 85): the LAST active year (inclusive end_age).
        assertThat(isActive(relabeled, household, 2055)).isTrue();
        // 2056 (spouse age 86): past the window -- inactive. This is the "survivor-attached" window
        // extending well beyond what the DECEASED primary's own age-85 boundary would have been
        // (primary would have turned 85 back in 2043) -- confirms the window re-anchors to the
        // survivor, not the deceased's frozen timeline (spec §4.1 / task 5's documented convention).
        assertThat(isActive(relabeled, household, 2056)).isFalse();
    }

    @Test
    void continuity_survivorIsPrimary_deceasedOwnedSourceTracksPrimaryAgeEveryYearAfterTransition() {
        var household = HouseholdContext.of(PRIMARY_BIRTH, 95, SPOUSE_BIRTH, 60, 2070);
        int transitionYear = household.transitionYear().orElseThrow(); // 2030 (spouse dies at 60)
        var deceasedOwned = pension(58, 82, "spouse", "1.0");

        var adjusted = SurvivorIncomeAdjuster.adjust(
                List.of(deceasedOwned), household, transitionYear, 2020, ZERO);
        var relabeled = find(adjusted, "primary");

        // Primary (born 1958) is 72 at the transition year (2030) -- inside [58, 82] -- active.
        assertThat(isActive(relabeled, household, transitionYear)).isTrue();
        // 2040 (primary age 82): the LAST active year (inclusive end_age).
        assertThat(isActive(relabeled, household, 2040)).isTrue();
        // 2041 (primary age 83): past the window -- inactive.
        assertThat(isActive(relabeled, household, 2041)).isFalse();
    }

    // === Sub-project B (stochastic mortality), task 6: explicit-survivor overload emits BOTH identities ===
    // The fixed-death overload picks household.survivor(); the explicit overload lets the caller force
    // EITHER identity so OptimizationContextBuilder can precompute both survivor regimes. These pin that
    // the explicit identity (not the household's fixed survivor) drives the relabel/keep-larger.

    @Test
    void adjustExplicit_forcesPrimarySurvivor_evenThoughHouseholdFixedSurvivorIsSpouse() {
        // Household's FIXED survivor is the SPOUSE (primary dies first at 85). Force survivor = PRIMARY.
        var household = HouseholdContext.of(PRIMARY_BIRTH, 85, SPOUSE_BIRTH, 95, 2070);
        assertThat(household.survivor()).isEqualTo(PersonId.SPOUSE);
        var spouseOwned = pension(60, null, "spouse", "0.5"); // now the DECEASED-owned source

        var adjusted = SurvivorIncomeAdjuster.adjust(
                List.of(spouseOwned), PersonId.PRIMARY, 2043, 2020, ZERO);

        // With PRIMARY forced as survivor, the spouse-owned source is deceased-owned -> relabeled to
        // primary at survivor_percent, and NO source is left owned by spouse.
        var result = find(adjusted, "primary");
        assertThat(result.annualAmount()).isEqualByComparingTo(new BigDecimal("12000")); // 24000 * 0.5
        assertThat(adjusted.stream().anyMatch(s -> "spouse".equals(s.owner()))).isFalse();
    }

    @Test
    void adjustExplicit_forcesSpouseSurvivor_evenThoughHouseholdFixedSurvivorIsPrimary() {
        // Mirror: household's FIXED survivor is the PRIMARY (spouse dies first at 60). Force SPOUSE.
        var household = HouseholdContext.of(PRIMARY_BIRTH, 95, SPOUSE_BIRTH, 60, 2070);
        assertThat(household.survivor()).isEqualTo(PersonId.PRIMARY);
        var primaryOwned = pension(60, null, "primary", "0.75"); // now the DECEASED-owned source

        var adjusted = SurvivorIncomeAdjuster.adjust(
                List.of(primaryOwned), PersonId.SPOUSE, 2030, 2020, ZERO);

        var result = find(adjusted, "spouse");
        assertThat(result.annualAmount()).isEqualByComparingTo(new BigDecimal("18000")); // 24000 * 0.75
        assertThat(adjusted.stream().anyMatch(s -> "primary".equals(s.owner()))).isFalse();
    }

    private static boolean isActive(ProjectionIncomeSourceInput source, HouseholdContext household, int year) {
        int age = IncomeYearMath.resolveSourceAge(source, household.primary().ageIn(year), household, year);
        return ProjectionIncomeSourceInput.isActiveForAge(source, age);
    }

    // === HP3 Part B-1 investigation: the deflation-clock "+1" in keepLargerSocialSecurityId ===
    // The household deferred register flagged `yearsFromBaseAtTransition = max(0, transitionYear -
    // baseYear) + 1` as a possible off-by-one that could flip the keep-larger decision between two
    // SS sources with DIFFERENT inflation rates. Investigated: the "+1" is CORRECT and must stay --
    // it is the exact 1-indexing IncomeYearMath#realAmount's documented contract requires (steps =
    // yearsFromBase - 1; "the base year itself is 1"), the SAME shape YearFinanceResolver uses for
    // every other source's real amount in calendar year `year` (yearsFromBase = max(0, year -
    // baseYear) + 1). Removing the "+1" would give SurvivorIncomeAdjuster ONE FEWER compounding step
    // than every other income computation in the codebase evaluates for the SAME calendar year -- an
    // off-by-one in the OPPOSITE direction from what the register described.
    //
    // Proof by independent elapsed-time counting, not a re-derivation of the production formula: one
    // calendar year after the base year, exactly ONE year of each source's own inflation has
    // elapsed, so $20,000 @ 2% must be worth $20,400 and $19,000 @ 8% must be worth $20,520 -- the
    // 8% source is the larger REAL benefit despite its smaller FACE amount. A "no +1" version would
    // instead compare the raw, ungrown face values ($20,000 vs $19,000) and pick the WRONG source.
    // This test pins the CURRENT (correct) behavior and fails if the "+1" is removed -- direction-
    // verified before any change; no production code was touched.
    @Test
    void keepLargerSocialSecurity_usesTheEstablishedElapsedYearsClock_notTheRawFaceAmount() {
        var household = HouseholdContext.of(PRIMARY_BIRTH, 85, SPOUSE_BIRTH, 95, 2070);
        var lowerRateHigherFace = ss(UUID.randomUUID(), "20000", "0.02", "primary");  // 20,000 @ 2%
        var higherRateLowerFace = ss(UUID.randomUUID(), "19000", "0.08", "spouse");   // 19,000 @ 8%

        // transitionYear = baseYear + 1: exactly one elapsed calendar year of compounding.
        var adjusted = SurvivorIncomeAdjuster.adjust(
                List.of(lowerRateHigherFace, higherRateLowerFace), household, 2043, 2042, ZERO);

        // The 8%-inflation source (real value 19,000*1.08=20,520) is correctly kept over the
        // 2%-inflation source (real value 20,000*1.02=20,400), even though its FACE amount is
        // smaller -- proving the comparison follows the grown real amount, not the raw face value.
        assertThat(adjusted).hasSize(1);
        assertThat(adjusted.getFirst().id()).isEqualTo(higherRateLowerFace.id());
    }
}
