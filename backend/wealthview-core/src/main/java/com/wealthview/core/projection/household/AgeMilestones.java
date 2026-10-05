package com.wealthview.core.projection.household;

import org.springframework.lang.Nullable;

/**
 * Date-of-birth milestones that gate account access and Medicare, resolved from a birth year and an
 * optional birth month (Phase 1a). A {@code null} month reproduces the legacy whole-year behavior
 * exactly, so every scenario saved before birth month existed is unchanged.
 *
 * <p><b>Early access (IRC 72(t), age 59½).</b> The calendar year in which the person reaches 59½
 * counts as penalty-free. This is the tactical assumption that withdrawals are timed after that
 * date. 59½ falls in {@code birthYear + 59} when the person was born January-June, and in
 * {@code birthYear + 60} otherwise. With no month known, the legacy proxy {@code birthYear + 60}
 * applies.
 *
 * <p>RMD start age and the age-65 standard-deduction adder depend on the calendar year only, so
 * they do not live here.
 */
public final class AgeMilestones {

    /** Legacy whole-year 59½ proxy: the first penalty-free age when the birth month is unknown. */
    public static final int LEGACY_EARLY_ACCESS_AGE = 60;

    private static final int FIRST_HALF_EARLY_ACCESS_AGE = 59;
    private static final int LAST_FIRST_HALF_MONTH = 6;
    private static final int FIRST_MONTH = 1;
    private static final int LAST_MONTH = 12;

    private AgeMilestones() {
    }

    /** The age (calendar year minus birth year) of the first penalty-free year. */
    public static int earlyAccessAge(@Nullable Integer birthMonth) {
        if (birthMonth == null) {
            return LEGACY_EARLY_ACCESS_AGE;
        }
        requireValidMonth(birthMonth);
        return birthMonth <= LAST_FIRST_HALF_MONTH ? FIRST_HALF_EARLY_ACCESS_AGE : LEGACY_EARLY_ACCESS_AGE;
    }

    /** Calendar year the person reaches 59½; that year counts as penalty-free. */
    public static int earlyAccessYear(int birthYear, @Nullable Integer birthMonth) {
        return birthYear + earlyAccessAge(birthMonth);
    }

    /**
     * Month (1-based) of the 65th birthday, when Medicare starts. A {@code null} month returns 1:
     * the legacy whole-year treatment, where the person counts as on Medicare for the full year
     * they turn 65. Phase 1b consumes this for the pre-65/65+ premium split.
     */
    public static int medicareStartMonth(@Nullable Integer birthMonth) {
        if (birthMonth == null) {
            return FIRST_MONTH;
        }
        requireValidMonth(birthMonth);
        return birthMonth;
    }

    private static void requireValidMonth(int birthMonth) {
        if (birthMonth < FIRST_MONTH || birthMonth > LAST_MONTH) {
            throw new IllegalArgumentException("birth month must be between 1 and 12, was " + birthMonth);
        }
    }
}
