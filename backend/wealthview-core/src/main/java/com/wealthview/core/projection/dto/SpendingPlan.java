package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public sealed interface SpendingPlan
        permits TierBasedSpendingPlan, GuardrailSpendingInput {

    ResolvedYearSpending resolveYear(int year, int age, int yearsInRetirement,
                                      BigDecimal inflationRate, BigDecimal activeIncome);
}
