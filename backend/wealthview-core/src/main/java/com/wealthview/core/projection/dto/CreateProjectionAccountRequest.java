package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateProjectionAccountRequest(
        UUID linkedAccountId,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn,
        BigDecimal costBasis,
        AllocationDto allocation,
        String accountType) {

    /** Back-compat for call sites predating cost_basis + allocation (defaults both to null). */
    public CreateProjectionAccountRequest(UUID linkedAccountId, BigDecimal initialBalance,
                                          BigDecimal annualContribution, BigDecimal expectedReturn,
                                          String accountType) {
        this(linkedAccountId, initialBalance, annualContribution, expectedReturn, null, null, accountType);
    }

    /** Back-compat for call sites that set cost_basis but predate allocation. */
    public CreateProjectionAccountRequest(UUID linkedAccountId, BigDecimal initialBalance,
                                          BigDecimal annualContribution, BigDecimal expectedReturn,
                                          BigDecimal costBasis, String accountType) {
        this(linkedAccountId, initialBalance, annualContribution, expectedReturn, costBasis, null, accountType);
    }
}
