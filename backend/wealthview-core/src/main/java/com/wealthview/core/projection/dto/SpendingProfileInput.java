package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record SpendingProfileInput(
        BigDecimal essentialExpenses,
        BigDecimal discretionaryExpenses,
        String incomeStreams,
        String spendingTiers
) {
    public SpendingProfileInput(BigDecimal essentialExpenses, BigDecimal discretionaryExpenses, String incomeStreams) {
        this(essentialExpenses, discretionaryExpenses, incomeStreams, null);
    }
}
