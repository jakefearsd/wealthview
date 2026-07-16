package com.wealthview.projection;

/**
 * Sub-project B (stochastic mortality), task 6: the raw per-trial output of the stochastic evaluation
 * pass ({@link StochasticMortalityEvaluator}) — one entry per Monte Carlo trial, index-aligned. This is
 * the OUTPUT CONTRACT consumed by task 7, which aggregates it into the user-facing summary
 * ({@code StochasticMortalitySummary.from(...)}); the aggregation is deliberately NOT done here.
 *
 * @param success        per-trial essential-floor success under that trial's OWN sampled deaths (the
 *                       longevity-aware success number, computed over the fixed-death-optimized schedule)
 * @param firstDeathAge  per-trial RAW sampled death age of whoever dies FIRST (straight from
 *                       {@link MortalityDraws#firstDeathAge()}, un-horizon-clamped)
 * @param secondDeathAge per-trial RAW sampled death age of the SURVIVOR (straight from
 *                       {@link MortalityDraws#secondDeathAge()}, un-horizon-clamped)
 */
record StochasticMortalityEvaluation(boolean[] success, int[] firstDeathAge, int[] secondDeathAge) {}
