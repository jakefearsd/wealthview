package com.wealthview.projection;

/**
 * Value object representing how a single withdrawal need is split across the three pool types.
 *
 * <p>Replaces the {@code double[]} convention used in
 * {@link MonteCarloSpendingOptimizer#splitWithdrawal} where callers previously had to remember
 * that index 0 = taxable, index 1 = traditional, and index 2 = Roth. Named accessors make the
 * intent self-documenting and eliminate silent index-swap bugs.
 */
record PoolWithdrawal(double taxable, double traditional, double roth) {

    /** Returns the total amount drawn across all three pools. */
    double total() {
        return taxable + traditional + roth;
    }
}
