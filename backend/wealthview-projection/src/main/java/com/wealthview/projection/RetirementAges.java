package com.wealthview.projection;

/**
 * Shared retirement-age thresholds used across the withdrawal and conversion
 * simulators, so the same policy number is not re-declared per class.
 */
final class RetirementAges {

    /**
     * Age below which retirement-account withdrawals are treated as "early".
     * A whole-year proxy for the 59.5 IRS threshold — see the callers' penalty
     * and sequencing logic.
     */
    static final int EARLY_WITHDRAWAL_AGE = 60;

    private RetirementAges() {
    }
}
