package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateProjectionAccountRequest(
        UUID linkedAccountId,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn,
        String accountType) {
}
