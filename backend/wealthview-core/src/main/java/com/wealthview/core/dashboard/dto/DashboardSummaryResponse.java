package com.wealthview.core.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Net-worth summary of a tenant's accounts and properties in USD.
 *
 * @param unconvertedAccounts names of accounts left out of every total because their currency has no
 *                            exchange rate; empty when all accounts could be converted to USD
 */
public record DashboardSummaryResponse(
        BigDecimal netWorth,
        BigDecimal totalInvestments,
        BigDecimal totalCash,
        BigDecimal totalPropertyEquity,
        List<AccountSummary> accounts,
        List<AllocationEntry> allocation,
        List<String> unconvertedAccounts
) {
    /**
     * Summary with every account convertible to USD.
     */
    public DashboardSummaryResponse(BigDecimal netWorth, BigDecimal totalInvestments, BigDecimal totalCash,
                                    BigDecimal totalPropertyEquity, List<AccountSummary> accounts,
                                    List<AllocationEntry> allocation) {
        this(netWorth, totalInvestments, totalCash, totalPropertyEquity, accounts, allocation, List.of());
    }

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
