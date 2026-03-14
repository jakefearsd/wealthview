package com.wealthview.core.property.dto;

import java.math.BigDecimal;
import java.util.Map;

public record MonthlyCashFlowDetailEntry(
        String month,
        BigDecimal totalIncome,
        Map<String, BigDecimal> expensesByCategory,
        BigDecimal totalExpenses,
        BigDecimal netCashFlow
) {
}
