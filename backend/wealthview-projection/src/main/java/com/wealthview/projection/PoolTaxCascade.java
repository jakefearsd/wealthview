package com.wealthview.projection;

/**
 * Deducts a dollar amount (a tax bill) from the three account pools in the fixed
 * taxable → traditional → roth priority order.
 *
 * <p>Shared by the {@code double}-precision Monte Carlo paths — {@link TrialSimulator}
 * (withdrawal + conversion tax) and {@link ConversionSimulator} (conversion tax). Both
 * previously carried their own copy of this cascade; the semantics are identical: each
 * pool contributes up to its available balance, and any shortfall (a tax bill exceeding
 * the pooled total) simply drains the pools to zero — no pool is driven negative.
 *
 * <p>The deterministic engine's pool tax sourcing lives separately in {@code PoolStrategy}
 * and is intentionally {@link java.math.BigDecimal}-based (it feeds the golden-file wire
 * contract); this helper is the {@code double} counterpart for the MC hot paths.
 */
final class PoolTaxCascade {

    private PoolTaxCascade() {
    }

    /**
     * Returns {@code [taxable, traditional, roth]} after deducting {@code amount} in
     * priority order. Non-positive pools are skipped; the input balances are not mutated.
     */
    static double[] deduct(double amount, double taxable, double traditional, double roth) {
        double remaining = amount;
        double t = taxable;
        double tr = traditional;
        double r = roth;
        if (remaining > 0 && t > 0) {
            double draw = Math.min(remaining, t);
            t -= draw;
            remaining -= draw;
        }
        if (remaining > 0 && tr > 0) {
            double draw = Math.min(remaining, tr);
            tr -= draw;
            remaining -= draw;
        }
        if (remaining > 0 && r > 0) {
            double draw = Math.min(remaining, r);
            r -= draw;
        }
        return new double[]{t, tr, r};
    }
}
