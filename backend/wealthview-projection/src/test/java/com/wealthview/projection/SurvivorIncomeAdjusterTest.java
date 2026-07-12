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

    private static boolean isActive(ProjectionIncomeSourceInput source, HouseholdContext household, int year) {
        int age = IncomeYearMath.resolveSourceAge(source, household.primary().ageIn(year), household, year);
        return ProjectionIncomeSourceInput.isActiveForAge(source, age);
    }
}
