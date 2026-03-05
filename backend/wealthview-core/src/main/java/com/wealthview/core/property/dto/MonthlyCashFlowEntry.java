package com.wealthview.core.property.dto;

import java.math.BigDecimal;

public record MonthlyCashFlowEntry(
        String month,
        BigDecimal totalIncome,
        BigDecimal totalExpenses,
        BigDecimal netCashFlow
) {
}
