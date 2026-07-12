package com.wealthview.core.projection.household;

/**
 * SSA 2021 period-life-table sex-neutral planning defaults for a person's death age, bucketed by
 * birth decade. Source: Social Security Administration, "Period Life Table, 2021" (the most recent
 * published table as of this writing), averaged across sexes and rounded to a single planning age
 * per cohort. These are only DEFAULTS — every caller may override with a user-supplied death age.
 */
public final class LifeExpectancy {

    private static final int MIN_DEATH_AGE = 50;
    private static final int MAX_DEATH_AGE = 120;

    private LifeExpectancy() {
    }

    /**
     * Returns the SSA 2021 planning default death age for a person born in {@code birthYear},
     * clamped to {@code [50, 120]} as a defensive bound (the cohort table itself only ever produces
     * values in {@code [84, 90]}).
     */
    public static int defaultDeathAge(int birthYear) {
        return clamp(cohortDeathAge(birthYear));
    }

    private static int cohortDeathAge(int birthYear) {
        if (birthYear <= 1940) {
            return 84;
        } else if (birthYear <= 1950) {
            return 85;
        } else if (birthYear <= 1960) {
            return 86;
        } else if (birthYear <= 1970) {
            return 87;
        } else if (birthYear <= 1980) {
            return 88;
        } else if (birthYear <= 1990) {
            return 89;
        } else {
            return 90;
        }
    }

    private static int clamp(int deathAge) {
        return Math.max(MIN_DEATH_AGE, Math.min(MAX_DEATH_AGE, deathAge));
    }
}
