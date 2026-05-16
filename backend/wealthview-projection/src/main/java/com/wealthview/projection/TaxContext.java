package com.wealthview.projection;

/**
 * Pool-aware withdrawal tax context for the Monte Carlo spending optimizer.
 *
 * <p>Carries the starting balances of the three account pools, the withdrawal
 * order, and the pre-computed per-year marginal tax rate used to estimate
 * traditional-withdrawal tax inside the hot trial loop.
 *
 * <p>Extracted from {@code MonteCarloSpendingOptimizer} during the Phase 3
 * decomposition so the optimizer and {@link SustainabilitySearch} can share it.
 */
record TaxContext(
        double initTaxable, double initTraditional, double initRoth,
        String withdrawalOrder, double[] marginalRateByYear) {}
