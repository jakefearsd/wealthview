package com.wealthview.core.projection.dto;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record GuardrailSpendingInput(
        List<GuardrailYearlySpending> yearlySpending
) {

    public Map<Integer, GuardrailYearlySpending> byYear() {
        return yearlySpending.stream()
                .collect(Collectors.toMap(GuardrailYearlySpending::year, Function.identity()));
    }
}
