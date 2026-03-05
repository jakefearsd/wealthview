package com.wealthview.core.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

public record DashboardSummaryResponse(
        BigDecimal netWorth,
        BigDecimal totalInvestments,
        BigDecimal totalCash,
        BigDecimal totalPropertyEquity,
        List<AccountSummary> accounts,
        List<AllocationEntry> allocation
) {
    public record AccountSummary(
            String name,
            String type,
            BigDecimal balance
    ) {
    }

    public record AllocationEntry(
            String category,
            BigDecimal value,
            BigDecimal percentage
    ) {
    }
}
