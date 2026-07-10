package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public record LinkedAccountInput(
        UUID linkedAccountId,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        AssetAllocation allocation,
        Optional<BigDecimal> expectedReturnOverride,
        String accountType
) implements ProjectionAccountInput {}
