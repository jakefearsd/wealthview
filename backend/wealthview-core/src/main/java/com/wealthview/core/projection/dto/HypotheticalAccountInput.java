package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Optional;

public record HypotheticalAccountInput(
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        AssetAllocation allocation,
        Optional<BigDecimal> expectedReturnOverride,
        BigDecimal costBasis,
        String accountType
) implements ProjectionAccountInput {

    /**
     * Back-compat convenience: callers that don't carry a cost basis (most existing call sites,
     * mostly tests) get {@code costBasis = initialBalance} — no embedded gain, matching the
     * documented default for hypothetical accounts with no explicit {@code cost_basis} input.
     */
    public HypotheticalAccountInput(BigDecimal initialBalance, BigDecimal annualContribution,
                                    AssetAllocation allocation, Optional<BigDecimal> expectedReturnOverride,
                                    String accountType) {
        this(initialBalance, annualContribution, allocation, expectedReturnOverride, initialBalance, accountType);
    }

    /**
     * Back-compat convenience: a hypothetical account defined solely by a fixed nominal
     * expected return maps to an all-US allocation carrying that return as an override.
     * With the engine's real-return conversion at zero inflation this reproduces the legacy
     * {@code expectedReturn}-driven growth exactly. Cost basis defaults to initialBalance
     * via the delegate constructor above.
     */
    public HypotheticalAccountInput(BigDecimal initialBalance, BigDecimal annualContribution,
                                    BigDecimal expectedReturn, String accountType) {
        this(initialBalance, annualContribution, AssetAllocation.ALL_US,
                Optional.ofNullable(expectedReturn), accountType);
    }
}
