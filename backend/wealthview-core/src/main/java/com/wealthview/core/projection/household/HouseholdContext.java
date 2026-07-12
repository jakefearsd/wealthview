package com.wealthview.core.projection.household;

import java.util.Objects;
import java.util.Optional;

import org.springframework.lang.Nullable;

/**
 * A household's mortality shape for one projection run, resolved once (near
 * {@code ProjectionInputBuilder}) and threaded through the engines. Single-person households
 * degenerate: {@code spouse} is {@code null} and {@code transitionYear} / {@code secondDeathYear}
 * are always empty — every existing single-person code path is unaffected.
 *
 * <p>For a two-person household, {@code transitionYear} is the calendar year of the FIRST death
 * and {@code secondDeathYear} the calendar year of the SECOND (survivor's) death. Both are reported
 * as empty when the corresponding death falls beyond the projection horizon (deaths beyond the
 * horizon never transition — the projection simply never reaches that year). {@code survivor} is
 * resolved from birth year + death age alone, independent of the horizon: it is the person who
 * would outlive the other, and is {@code null} only for a single-person household.
 *
 * <p><b>Tie-break:</b> if both people die in the same calendar year, the survivor is the YOUNGER
 * person (later birth year) and the transition still fires exactly once, in that shared year — it
 * is not modeled as two separate transitions.
 */
public record HouseholdContext(
        Person primary,
        @Nullable Person spouse,
        Optional<Integer> transitionYear,
        Optional<Integer> secondDeathYear,
        @Nullable PersonId survivor) {

    /**
     * One household member's fixed-death-age mortality assumption.
     *
     * @param deathAge the age at which this person is assumed to die (SSA default or user override)
     */
    public record Person(PersonId id, int birthYear, int deathAge) {

        public int deathYear() {
            return birthYear + deathAge;
        }

        public int ageIn(int calendarYear) {
            return calendarYear - birthYear;
        }
    }

    public boolean isHousehold() {
        return spouse != null;
    }

    /**
     * True when this is a household and both people are still alive in {@code year} — i.e. the
     * first-death transition has not yet occurred by {@code year}. Always false for a single-person
     * context.
     */
    public boolean bothAliveIn(int year) {
        return isHousehold() && isAliveIn(PersonId.PRIMARY, year) && isAliveIn(PersonId.SPOUSE, year);
    }

    /**
     * True when the person identified by {@code personId} is still alive in {@code year}, i.e.
     * {@code year} is strictly before their death year. Throws if {@code personId} is
     * {@link PersonId#SPOUSE} but this context has no spouse.
     */
    public boolean isAliveIn(PersonId personId, int year) {
        return year < personFor(personId).deathYear();
    }

    private Person personFor(PersonId personId) {
        return switch (personId) {
            case PRIMARY -> primary;
            case SPOUSE -> Objects.requireNonNull(spouse, "No spouse in this household context");
        };
    }

    /** Degenerate single-person context: no spouse, no transition, ever. */
    public static HouseholdContext single(int birthYear) {
        var primaryPerson = new Person(PersonId.PRIMARY, birthYear, LifeExpectancy.defaultDeathAge(birthYear));
        return new HouseholdContext(primaryPerson, null, Optional.empty(), Optional.empty(), null);
    }

    /**
     * Builds a two-person household context, clamping {@code transitionYear} and
     * {@code secondDeathYear} to empty when the corresponding death falls beyond
     * {@code horizonEndYear}.
     */
    // ShortMethodName: 'of' is the conventional JDK-style static factory method name.
    @SuppressWarnings("PMD.ShortMethodName")
    public static HouseholdContext of(int primaryBirthYear, int primaryDeathAge,
                                       int spouseBirthYear, int spouseDeathAge,
                                       int horizonEndYear) {
        var primaryPerson = new Person(PersonId.PRIMARY, primaryBirthYear, primaryDeathAge);
        var spousePerson = new Person(PersonId.SPOUSE, spouseBirthYear, spouseDeathAge);

        int firstDeathYear = Math.min(primaryPerson.deathYear(), spousePerson.deathYear());
        int lastDeathYear = Math.max(primaryPerson.deathYear(), spousePerson.deathYear());

        Optional<Integer> transitionYear =
                firstDeathYear <= horizonEndYear ? Optional.of(firstDeathYear) : Optional.empty();
        Optional<Integer> secondDeathYear =
                lastDeathYear <= horizonEndYear ? Optional.of(lastDeathYear) : Optional.empty();

        return new HouseholdContext(primaryPerson, spousePerson, transitionYear, secondDeathYear,
                survivorOf(primaryPerson, spousePerson));
    }

    private static PersonId survivorOf(Person primaryPerson, Person spousePerson) {
        if (primaryPerson.deathYear() != spousePerson.deathYear()) {
            return primaryPerson.deathYear() > spousePerson.deathYear() ? PersonId.PRIMARY : PersonId.SPOUSE;
        }
        // Tie-break: same-year death -> the younger (later-born) person is the survivor.
        return primaryPerson.birthYear() >= spousePerson.birthYear() ? PersonId.PRIMARY : PersonId.SPOUSE;
    }
}
