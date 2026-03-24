package com.wealthview.projection;

/**
 * Computes Required Minimum Distributions (RMDs) using the IRS Uniform Lifetime Table
 * (Publication 590-B, Table III). Stateless utility — no Spring injection.
 *
 * <p><strong>Legislative basis:</strong> RMD start ages are governed by the SECURE Act 2.0
 * (Pub. L. 117-328, enacted December 2022). The current rules are:
 * <ul>
 *   <li>Born before 1960 → RMDs begin at age 73</li>
 *   <li>Born 1960 or later → RMDs begin at age 75</li>
 * </ul>
 * If SECURE Act 3.0 or later legislation changes these thresholds, update
 * {@link #SECURE_2_BIRTH_YEAR_THRESHOLD}, {@link #RMD_START_AGE_BORN_BEFORE_1960}, and
 * {@link #RMD_START_AGE_BORN_1960_OR_LATER} accordingly.
 *
 * <p><strong>Distribution table:</strong> The Uniform Lifetime Table III covers ages 72–120.
 * Age 72 is the minimum table entry regardless of the legislative RMD start age; the table
 * is used for computing the RMD amount once distributions begin.
 */
final class RmdCalculator {

    private RmdCalculator() {}

    // ── Legislative constants (SECURE Act 2.0 / Pub. L. 117-328) ──────────────

    /** Birth year threshold distinguishing the two RMD start ages under SECURE Act 2.0. */
    private static final int SECURE_2_BIRTH_YEAR_THRESHOLD = 1960;

    /** RMD start age for individuals born before {@link #SECURE_2_BIRTH_YEAR_THRESHOLD}. */
    private static final int RMD_START_AGE_BORN_BEFORE_1960 = 73;

    /** RMD start age for individuals born in {@link #SECURE_2_BIRTH_YEAR_THRESHOLD} or later. */
    private static final int RMD_START_AGE_BORN_1960_OR_LATER = 75;

    // ── IRS Uniform Lifetime Table III ────────────────────────────────────────

    /** Minimum age covered by IRS Publication 590-B, Table III. */
    private static final int ULT_TABLE_MIN_AGE = 72;

    /** Maximum age covered by IRS Publication 590-B, Table III. */
    private static final int ULT_TABLE_MAX_AGE = 120;

    // IRS Uniform Lifetime Table III — distribution periods for ages 72–120.
    // Index 0 = age 72 (ULT_TABLE_MIN_AGE), index 48 = age 120.
    private static final double[] DISTRIBUTION_PERIODS = {
        27.4, // 72
        26.5, // 73
        25.5, // 74
        24.6, // 75
        23.7, // 76
        22.9, // 77
        22.0, // 78
        21.1, // 79
        20.2, // 80
        19.4, // 81
        18.5, // 82
        17.7, // 83
        16.8, // 84
        16.0, // 85
        15.2, // 86
        14.4, // 87
        13.7, // 88
        12.9, // 89
        12.2, // 90
        11.5, // 91
        10.8, // 92
        10.1, // 93
         9.5, // 94
         8.9, // 95
         8.4, // 96
         7.8, // 97
         7.3, // 98
         6.8, // 99
         6.4, // 100
         6.0, // 101
         5.6, // 102
         5.2, // 103
         4.9, // 104
         4.6, // 105
         4.3, // 106
         4.1, // 107
         3.9, // 108
         3.7, // 109
         3.2, // 110
         3.4, // 111
         3.3, // 112
         3.1, // 113
         3.0, // 114
         2.1, // 115
         2.8, // 116
         2.7, // 117
         2.5, // 118
         2.3, // 119
         2.0  // 120
    };

    /**
     * Returns the IRS Uniform Lifetime Table distribution period for the given age.
     *
     * @param age the account owner's age
     * @return the distribution period, or {@code 0} if the age is outside the table range
     *         (below {@value #ULT_TABLE_MIN_AGE} or above {@value #ULT_TABLE_MAX_AGE})
     */
    static double distributionPeriod(int age) {
        if (age < ULT_TABLE_MIN_AGE || age > ULT_TABLE_MAX_AGE) {
            return 0;
        }
        return DISTRIBUTION_PERIODS[age - ULT_TABLE_MIN_AGE];
    }

    static double computeRmd(double priorYearEndBalance, int age) {
        if (priorYearEndBalance <= 0 || age < ULT_TABLE_MIN_AGE) {
            return 0;
        }
        double period = distributionPeriod(age);
        if (period <= 0) {
            return 0;
        }
        return priorYearEndBalance / period;
    }

    /**
     * Returns the age at which RMDs must begin for an account owner with the given birth year,
     * per SECURE Act 2.0 (Pub. L. 117-328).
     */
    static int rmdStartAge(int birthYear) {
        return birthYear < SECURE_2_BIRTH_YEAR_THRESHOLD
                ? RMD_START_AGE_BORN_BEFORE_1960
                : RMD_START_AGE_BORN_1960_OR_LATER;
    }
}
