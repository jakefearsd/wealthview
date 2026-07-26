package com.wealthview.projection;

import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.strategy.WithdrawalOrder;

/**
 * Value object representing how a single withdrawal need is split across the three pool types.
 *
 * <p>Replaces the {@code double[]} convention used in
 * {@link MonteCarloSpendingOptimizer#splitWithdrawal} where callers previously had to remember
 * that index 0 = taxable, index 1 = traditional, and index 2 = Roth. Named accessors make the
 * intent self-documenting and eliminate silent index-swap bugs.
 */
record PoolWithdrawal(double taxable, double traditional, double roth) {

    /**
     * Draws {@code need} greedily from the pools in the priority order
     * {@link WithdrawalOrder#drawSequence()} defines, capping each draw at that pool's available
     * balance (negative balances count as zero). Whatever the pools cannot cover is left undrawn.
     */
    // ExhaustiveSwitchHasDefault: the switch below is exhaustive over PoolType, but Checkstyle's
    // MissingSwitchDefault rule (also an enforced gate) requires the default anyway on a switch
    // STATEMENT -- this suppression resolves that conflict between the two gates.
    @SuppressWarnings("PMD.ExhaustiveSwitchHasDefault")
    static PoolWithdrawal greedy(WithdrawalOrder order, double taxable, double traditional,
                                 double roth, double need) {
        double fromTaxable = 0;
        double fromTraditional = 0;
        double fromRoth = 0;
        double remaining = need;

        for (PoolType pool : order.drawSequence()) {
            switch (pool) {
                case TAXABLE -> {
                    fromTaxable = Math.min(remaining, Math.max(0, taxable));
                    remaining -= fromTaxable;
                }
                case TRADITIONAL -> {
                    fromTraditional = Math.min(remaining, Math.max(0, traditional));
                    remaining -= fromTraditional;
                }
                case ROTH -> {
                    fromRoth = Math.min(remaining, Math.max(0, roth));
                    remaining -= fromRoth;
                }
                // Unreachable: PoolType is a closed 3-value enum and every value is handled above;
                // this branch exists only to satisfy Checkstyle's MissingSwitchDefault rule.
                default -> throw new IllegalStateException("Unexpected pool type: " + pool);
            }
        }

        return new PoolWithdrawal(fromTaxable, fromTraditional, fromRoth);
    }

    /** Returns the total amount drawn across all three pools. */
    double total() {
        return taxable + traditional + roth;
    }
}
