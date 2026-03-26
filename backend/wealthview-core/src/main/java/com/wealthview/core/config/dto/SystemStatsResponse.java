package com.wealthview.core.config.dto;

public record SystemStatsResponse(
        long totalUsers,
        long activeUsers,
        long totalTenants,
        long totalAccounts,
        long totalHoldings,
        long totalTransactions,
        String databaseSize,
        long symbolsTracked,
        long staleSymbols
) {
}
