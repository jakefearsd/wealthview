package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record SpendingFeasibilitySummary(
        boolean spendingFeasible,
        Integer firstShortfallYear,
        Integer firstShortfallAge,
        BigDecimal sustainableAnnualSpending,
        BigDecimal requiredAnnualSpending) {
}
