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
}
