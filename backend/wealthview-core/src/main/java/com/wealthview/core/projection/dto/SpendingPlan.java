package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Map;

public sealed interface SpendingPlan
        permits TierBasedSpendingPlan, GuardrailSpendingInput {

    ResolvedYearSpending resolveYear(int year, int age, int yearsInRetirement,
                                      BigDecimal inflationRate, BigDecimal activeIncome);

    /**
     * Optional pre-computed Roth conversion schedule produced by the Monte Carlo optimizer.
     * Returns null when the plan has no conversion schedule (e.g., tier-based plans, or
     * guardrail plans constructed without one). The GuardrailSpendingInput record component
     * accessor satisfies this automatically.
     */
    default Map<Integer, BigDecimal> conversionByYear() {
        return null;
    }
}
