package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record HypotheticalAccountInput(
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn,
        String accountType
) implements ProjectionAccountInput {}
