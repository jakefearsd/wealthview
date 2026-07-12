package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for creating (or replacing, in a scenario update) a projection account.
 *
 * @param owner Household/survivor modeling (sub-project A): {@code primary}, {@code spouse}, or
 *              {@code joint} ({@code joint} only valid for {@code taxable} accounts). {@code null}
 *              resolves to {@code "primary"}, reproducing pre-household behavior byte-for-byte.
 */
public record CreateProjectionAccountRequest(
        UUID linkedAccountId,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn,
        BigDecimal costBasis,
        AllocationDto allocation,
        String accountType,
        String owner) {

    /** Back-compat for call sites predating cost_basis + allocation (defaults both to null). */
    public CreateProjectionAccountRequest(UUID linkedAccountId, BigDecimal initialBalance,
                                          BigDecimal annualContribution, BigDecimal expectedReturn,
                                          String accountType) {
        this(linkedAccountId, initialBalance, annualContribution, expectedReturn, null, null, accountType, null);
    }

    /** Back-compat for call sites that set cost_basis but predate allocation. */
    public CreateProjectionAccountRequest(UUID linkedAccountId, BigDecimal initialBalance,
                                          BigDecimal annualContribution, BigDecimal expectedReturn,
                                          BigDecimal costBasis, String accountType) {
        this(linkedAccountId, initialBalance, annualContribution, expectedReturn, costBasis, null, accountType,
                null);
    }

    /** Back-compat for call sites predating owner (household modeling). */
    public CreateProjectionAccountRequest(UUID linkedAccountId, BigDecimal initialBalance,
                                          BigDecimal annualContribution, BigDecimal expectedReturn,
                                          BigDecimal costBasis, AllocationDto allocation, String accountType) {
        this(linkedAccountId, initialBalance, annualContribution, expectedReturn, costBasis, allocation,
                accountType, null);
    }
}
