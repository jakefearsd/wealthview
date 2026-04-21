package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public sealed interface SpendingPlan
        permits TierBasedSpendingPlan, GuardrailSpendingInput {

    ResolvedYearSpending resolveYear(int year, int age, int yearsInRetirement,
                                      BigDecimal inflationRate, BigDecimal activeIncome);

    /**
     * Pre-computed Roth conversion schedule produced by the Monte Carlo optimizer.
     * Tier-based plans never carry a schedule; guardrail plans may or may not.
     */
    default Optional<Map<Integer, BigDecimal>> conversionSchedule() {
        return Optional.empty();
    }
}
