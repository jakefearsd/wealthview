package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public sealed interface ProjectionAccountInput
        permits LinkedAccountInput, HypotheticalAccountInput {
    BigDecimal initialBalance();
    BigDecimal annualContribution();
    BigDecimal expectedReturn();
    String accountType();
}
