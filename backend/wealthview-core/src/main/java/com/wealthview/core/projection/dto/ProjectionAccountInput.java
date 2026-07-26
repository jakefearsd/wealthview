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

    /**
     * Household/survivor modeling (sub-project A): {@code "primary"}, {@code "spouse"}, or
     * {@code "joint"} ({@code "joint"} only valid for taxable accounts). Every existing
     * constructor defaults this to {@code "primary"}, reproducing pre-household behavior
     * byte-for-byte — the deterministic and Monte Carlo engines ignore it until the
     * owner-aware pool generalization (a later task) consumes it.
     */
    String owner();

    /**
     * Task 16: typed view of {@link #accountType()}. The record component stays a {@code String}
     * for wire compatibility (JSON, JPA columns); consumers that dispatch on account type should
     * use this accessor instead of comparing the raw string. Throws via {@link PoolType#fromString}
     * if {@link #accountType()} is outside the closed set — every production construction path
     * (persisted rows behind {@code chk_projection_accounts_account_type}) already guarantees this
     * never happens; see {@code ProjectionInputBuilder}.
     */
    default PoolType poolType() {
        return PoolType.fromString(accountType());
    }

    /**
     * Task 16: typed view of {@link #owner()}. The record component stays a {@code String} for wire
     * compatibility; consumers that dispatch on owner category should use this accessor instead of
     * comparing the raw string. Throws via {@link LotOwner#fromString} if {@link #owner()} is
     * outside the closed set — every production construction path (persisted rows behind {@code
     * chk_projection_accounts_owner}, plus {@code ScenarioCrudService#validateAccountOwner}) already
     * guarantees this never happens.
     */
    default LotOwner ownerType() {
        return LotOwner.fromString(owner());
    }
}
