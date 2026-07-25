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
) {

    /**
     * The degenerate setup for a scenario that leaves no years to simulate (retirement age at or
     * past {@code endAge}). Carries through only the horizon figures the caller already derived, so
     * a reader can still tell which (retirementYear, retirementAge, endAge) produced the empty run.
     *
     * <p>Named factory rather than three inline positional argument lists of zeros and nulls: those
     * lists ran to 10, 21, and 13 components, where a transposed null or a 0/1 default in the wrong
     * slot would have compiled silently.
     */
    static OptimizationSetup emptyHorizon(int retirementYear, int retirementAge, int endAge,
                                          int years, int rmdStartAge) {
        return new OptimizationSetup(
                PortfolioSetup.empty(),
                SimulationParameters.emptyHorizon(retirementYear, retirementAge, endAge, years,
                        rmdStartAge),
                TaxIncomeContext.empty());
    }
}
