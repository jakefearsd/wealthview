package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ProjectionAccountResponse(
        UUID id,
        UUID linkedAccountId,
        String name,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn,
        String accountType) {
}
