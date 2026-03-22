package com.wealthview.projection;

/**
 * Computes Required Minimum Distributions using the IRS Uniform Lifetime Table
 * (Publication 590-B, Table III). Stateless utility — no Spring injection.
 */
final class RmdCalculator {

    private RmdCalculator() {}

    // IRS Uniform Lifetime Table III — distribution periods for ages 72–120.
    // Index 0 = age 72, index 48 = age 120.
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
         2.1, // 115  (IRS Publication 590-B Table III)
         2.8, // 116
         2.7, // 117
         2.5, // 118
         2.3, // 119
         2.0  // 120
    };

    static double distributionPeriod(int age) {
        if (age < 72 || age > 120) {
            return 0;
        }
        return DISTRIBUTION_PERIODS[age - 72];
    }

    static double computeRmd(double priorYearEndBalance, int age) {
        if (priorYearEndBalance <= 0 || age < 72) {
            return 0;
        }
        double period = distributionPeriod(age);
        if (period <= 0) {
            return 0;
        }
        return priorYearEndBalance / period;
    }

    static int rmdStartAge(int birthYear) {
        return birthYear < 1960 ? 73 : 75;
    }
}
