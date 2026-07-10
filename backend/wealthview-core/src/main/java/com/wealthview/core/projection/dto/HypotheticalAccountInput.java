package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Optional;

public record HypotheticalAccountInput(
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        AssetAllocation allocation,
        Optional<BigDecimal> expectedReturnOverride,
        String accountType
) implements ProjectionAccountInput {

    /**
     * Back-compat convenience: a hypothetical account defined solely by a fixed nominal
     * expected return maps to an all-US allocation carrying that return as an override.
     * With the engine's real-return conversion at zero inflation this reproduces the legacy
     * {@code expectedReturn}-driven growth exactly.
     */
    public HypotheticalAccountInput(BigDecimal initialBalance, BigDecimal annualContribution,
                                    BigDecimal expectedReturn, String accountType) {
        this(initialBalance, annualContribution, AssetAllocation.ALL_US,
                Optional.ofNullable(expectedReturn), accountType);
    }
}
