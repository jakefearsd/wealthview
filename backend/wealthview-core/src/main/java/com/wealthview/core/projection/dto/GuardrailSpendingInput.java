package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public record GuardrailSpendingInput(
        List<GuardrailYearlySpending> yearlySpending,
        Map<Integer, BigDecimal> conversionByYear
) implements SpendingPlan {

    public GuardrailSpendingInput(List<GuardrailYearlySpending> yearlySpending) {
        this(yearlySpending, null);
    }

    public Map<Integer, GuardrailYearlySpending> byYear() {
        return yearlySpending.stream()
                .collect(Collectors.toMap(GuardrailYearlySpending::year, Function.identity()));
    }

    @Override
    public Optional<Map<Integer, BigDecimal>> conversionSchedule() {
        return Optional.ofNullable(conversionByYear);
    }

    @Override
    public ResolvedYearSpending resolveYear(int year, int age, int yearsInRetirement,
                                             BigDecimal inflationRate, BigDecimal activeIncome) {
        var gy = byYear().get(year);
        if (gy == null) {
            return new ResolvedYearSpending(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return new ResolvedYearSpending(gy.portfolioWithdrawal(), gy.recommended(),
                gy.essentialFloor(), gy.discretionary());
    }
}
