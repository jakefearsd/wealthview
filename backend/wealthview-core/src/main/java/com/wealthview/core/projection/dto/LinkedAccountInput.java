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
        BigDecimal costBasis,
        String accountType
) implements ProjectionAccountInput {

    /**
     * Back-compat convenience: callers that don't carry a cost basis (most existing call sites,
     * mostly tests) get {@code costBasis = initialBalance} — no embedded gain — mirroring how
     * {@link HypotheticalAccountInput}'s legacy-shape constructor defaults it.
     */
    public LinkedAccountInput(UUID linkedAccountId, BigDecimal initialBalance, BigDecimal annualContribution,
                              AssetAllocation allocation, Optional<BigDecimal> expectedReturnOverride,
                              String accountType) {
        this(linkedAccountId, initialBalance, annualContribution, allocation, expectedReturnOverride,
                initialBalance, accountType);
    }
}
