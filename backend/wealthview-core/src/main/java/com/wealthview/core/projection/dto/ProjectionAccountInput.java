package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Optional;

public sealed interface ProjectionAccountInput
        permits LinkedAccountInput, HypotheticalAccountInput {

    BigDecimal initialBalance();

    BigDecimal annualContribution();

    /** The account's asset-class mix, used to derive an allocation-blended real return. */
    AssetAllocation allocation();

    /**
     * An optional user-supplied nominal expected return that overrides the allocation-derived
     * return. When present the engine converts it to a real return; when empty the allocation
     * blend of capital-market geometric means is used.
     */
    Optional<BigDecimal> expectedReturnOverride();

    /**
     * The taxable pool's initial cost basis, used to seed per-lot FIFO capital-gains tracking.
     * Linked accounts derive this from the sum of their holdings' cost basis; hypothetical
     * accounts default it to {@link #initialBalance()} (no embedded gain) unless explicitly set.
     */
    BigDecimal costBasis();

    String accountType();
}
