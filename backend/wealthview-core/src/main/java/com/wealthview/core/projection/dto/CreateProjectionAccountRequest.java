package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateProjectionAccountRequest(
        UUID linkedAccountId,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn,
        BigDecimal costBasis,
        String accountType) {

    /**
     * Back-compat convenience for the many existing call sites (mostly tests) that predate
     * the {@code cost_basis} field: defaults it to null, matching the "unset" wire shape —
     * {@code ProjectionInputBuilder} falls back to {@code initialBalance} downstream.
     */
    public CreateProjectionAccountRequest(UUID linkedAccountId, BigDecimal initialBalance,
                                          BigDecimal annualContribution, BigDecimal expectedReturn,
                                          String accountType) {
        this(linkedAccountId, initialBalance, annualContribution, expectedReturn, null, accountType);
    }
}
