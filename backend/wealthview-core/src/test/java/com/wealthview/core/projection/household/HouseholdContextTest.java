package com.wealthview.core.projection.household;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HouseholdContextTest {

    @Test
    void of_primaryDiesFirstWithinHorizon_resolvesTransitionSurvivorAndSecondDeath() {
        var context = HouseholdContext.of(1958, 85, 1966, 90, 2065);

        assertThat(context.transitionYear()).contains(2043);
        assertThat(context.survivor()).isEqualTo(PersonId.SPOUSE);
        assertThat(context.secondDeathYear()).contains(2056);
    }

    @Test
    void bothAliveIn_beforeAndAtTransitionYear_trueThenFalse() {
        var context = HouseholdContext.of(1958, 85, 1966, 90, 2065);

        assertThat(context.bothAliveIn(2042)).isTrue();
        assertThat(context.bothAliveIn(2043)).isFalse();
    }

    @Test
    void of_bothDeathsBeyondHorizon_emptyOptionalsButSurvivorStillResolved() {
        var context = HouseholdContext.of(1958, 120, 1966, 120, 2050);

        assertThat(context.transitionYear()).isEmpty();
        assertThat(context.secondDeathYear()).isEmpty();
        assertThat(context.isHousehold()).isTrue();
        assertThat(context.survivor()).isEqualTo(PersonId.SPOUSE);
    }

    @Test
    void of_sameYearDeath_survivorIsYoungerPersonAndTransitionFiresOnce() {
        // Primary born 1958 dies at 90 (deathYear 2048); spouse born 1966 dies at 82 (deathYear 2048).
        var context = HouseholdContext.of(1958, 90, 1966, 82, 2065);

        assertThat(context.transitionYear()).contains(2048);
        assertThat(context.secondDeathYear()).contains(2048);
        assertThat(context.survivor()).isEqualTo(PersonId.SPOUSE);
    }

    @Test
    void single_birthYearOnly_degeneratesToNoHouseholdWithEmptyOptionals() {
        var context = HouseholdContext.single(1970);

        assertThat(context.isHousehold()).isFalse();
        assertThat(context.spouse()).isNull();
        assertThat(context.survivor()).isNull();
        assertThat(context.transitionYear()).isEmpty();
        assertThat(context.secondDeathYear()).isEmpty();
        assertThat(context.primary().birthYear()).isEqualTo(1970);
    }

    @Test
    void bothAliveIn_singlePersonContext_alwaysFalse() {
        var context = HouseholdContext.single(1970);

        assertThat(context.bothAliveIn(2000)).isFalse();
    }

    @Test
    void isAliveIn_beforeAndAtOwnDeathYear_trueThenFalse() {
        var context = HouseholdContext.of(1958, 85, 1966, 90, 2065);

        assertThat(context.isAliveIn(PersonId.PRIMARY, 2042)).isTrue();
        assertThat(context.isAliveIn(PersonId.PRIMARY, 2043)).isFalse();
    }

    @Test
    void isAliveIn_survivorAfterFirstDeath_stillTrueUntilOwnDeathYear() {
        var context = HouseholdContext.of(1958, 85, 1966, 90, 2065);

        assertThat(context.isAliveIn(PersonId.SPOUSE, 2043)).isTrue();
        assertThat(context.isAliveIn(PersonId.SPOUSE, 2055)).isTrue();
        assertThat(context.isAliveIn(PersonId.SPOUSE, 2056)).isFalse();
    }

    @Test
    void person_deathYearAndAgeIn_computeFromBirthYear() {
        var person = new HouseholdContext.Person(PersonId.PRIMARY, 1958, 85);

        assertThat(person.deathYear()).isEqualTo(2043);
        assertThat(person.ageIn(2010)).isEqualTo(52);
    }

    // === Household task 7: filerAgeIn / secondFilerAgeIn / age65QualifyingCount ===

    @Test
    void filerAgeIn_singlePersonContext_alwaysPrimaryAgeRegardlessOfLifeExpectancy() {
        var context = HouseholdContext.single(1958);

        // Year 2060 is far beyond the SSA default death age used to build this context -- a
        // single-person context must never consult aliveness (spec: household-only mechanic).
        assertThat(context.filerAgeIn(2060)).isEqualTo(102);
    }

    @Test
    void filerAgeIn_beforeTransition_primaryAge() {
        var context = HouseholdContext.of(1958, 85, 1966, 90, 2065);

        assertThat(context.filerAgeIn(2042)).isEqualTo(84);
    }

    @Test
    void filerAgeIn_afterPrimaryDeath_survivorSpouseAge() {
        var context = HouseholdContext.of(1958, 85, 1966, 90, 2065);

        // Primary dies 2043 (age 85); the survivor (spouse, born 1966) is 77 that year.
        assertThat(context.filerAgeIn(2043)).isEqualTo(77);
        assertThat(context.filerAgeIn(2050)).isEqualTo(84);
    }

    @Test
    void filerAgeIn_afterSpouseDeath_survivingPrimaryAgeUnaffectedBySpouseDeath() {
        // Spouse (born 1966) dies first at 60 (2026); primary (born 1958) survives.
        var context = HouseholdContext.of(1958, 90, 1966, 60, 2065);
        assertThat(context.survivor()).isEqualTo(PersonId.PRIMARY);

        assertThat(context.filerAgeIn(2030)).isEqualTo(2030 - 1958);
    }

    @Test
    void secondFilerAgeIn_bothAlive_spouseAge() {
        var context = HouseholdContext.of(1958, 85, 1966, 90, 2065);

        assertThat(context.secondFilerAgeIn(2042)).isEqualTo(76);
    }

    @Test
    void secondFilerAgeIn_afterTransition_null() {
        var context = HouseholdContext.of(1958, 85, 1966, 90, 2065);

        assertThat(context.secondFilerAgeIn(2043)).isNull();
    }

    @Test
    void secondFilerAgeIn_singlePersonContext_null() {
        var context = HouseholdContext.single(1958);

        assertThat(context.secondFilerAgeIn(2042)).isNull();
    }

    @Test
    void age65QualifyingCount_bothAliveBothOver65_two() {
        var context = HouseholdContext.of(1955, 90, 1958, 90, 2065);

        // 2025: primary (1955) is 70, spouse (1958) is 67 -- both qualify.
        assertThat(context.age65QualifyingCount(2025)).isEqualTo(2);
    }

    @Test
    void age65QualifyingCount_bothAliveOnlyPrimaryOver65_one() {
        var context = HouseholdContext.of(1955, 90, 1966, 90, 2065);

        // 2025: primary (1955) is 70; spouse (1966) is 59 -- only primary qualifies.
        assertThat(context.age65QualifyingCount(2025)).isEqualTo(1);
    }

    @Test
    void age65QualifyingCount_afterTransition_atMostOneEvenIfDeceasedWouldQualify() {
        var context = HouseholdContext.of(1955, 70, 1966, 90, 2065);
        assertThat(context.transitionYear()).contains(2025); // primary dies at 70 in 2025

        // Post-transition: survivor (spouse, born 1966) is only 60 in 2026 -- does not qualify, and
        // the deceased primary (who WOULD be 71) must not be counted.
        assertThat(context.age65QualifyingCount(2026)).isZero();
    }

    @Test
    void age65QualifyingCount_singlePersonUnder65_zero() {
        var context = HouseholdContext.single(1970);

        assertThat(context.age65QualifyingCount(2025)).isZero();
    }
}
