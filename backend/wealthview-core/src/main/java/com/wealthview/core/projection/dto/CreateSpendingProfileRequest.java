package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

public record CreateSpendingProfileRequest(
        String name,
        BigDecimal essentialExpenses,
        BigDecimal discretionaryExpenses,
        List<IncomeStreamRequest> incomeStreams) {
}
