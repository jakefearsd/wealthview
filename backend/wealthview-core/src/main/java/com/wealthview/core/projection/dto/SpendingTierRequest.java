package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record SpendingTierRequest(
        String name,
        int startAge,
        Integer endAge,
        BigDecimal essentialExpenses,
        BigDecimal discretionaryExpenses) {
}
