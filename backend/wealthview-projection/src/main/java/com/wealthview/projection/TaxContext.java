package com.wealthview.projection;

/**
 * Pool-aware withdrawal tax context for the Monte Carlo spending optimizer.
 *
 * <p>Carries the starting balances of the three account pools, the withdrawal order, and the
 * per-year exact ordinary tax table (audit C5) plus the year's base ordinary income used to
 * stack draws on it -- together these replace the old flat marginal-rate chord and let the hot
 * trial loop price a draw's EXACT incremental tax ({@code taxAt(base + draw) - taxAt(base)})
 * instead of a single average rate.
 *
 * <p>Extracted from {@code MonteCarloSpendingOptimizer} during the Phase 3
 * decomposition so the optimizer and {@link SustainabilitySearch} can share it.
 */
record TaxContext(
        double initTaxable, double initTraditional, double initRoth,
        String withdrawalOrder, OrdinaryTaxTable[] ordinaryTaxTableByYear, double[] ordinaryBaseIncomeByYear) {}
