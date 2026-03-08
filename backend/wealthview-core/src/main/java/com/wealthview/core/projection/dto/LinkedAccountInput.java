package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record LinkedAccountInput(
        UUID linkedAccountId,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn,
        String accountType
) implements ProjectionAccountInput {}
