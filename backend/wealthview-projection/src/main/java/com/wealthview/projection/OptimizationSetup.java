package com.wealthview.projection;

/**
 * All pre-computed inputs to a single guardrail optimization run, grouped by concern.
 * Package-private: shared between {@link MonteCarloSpendingOptimizer} and its extracted
 * collaborators (e.g. {@link GuardrailResponseBuilder}) without leaking out of the module.
 */
record OptimizationSetup(
        PortfolioSetup portfolio,
        SimulationParameters sim,
        TaxIncomeContext taxIncome
) {}
