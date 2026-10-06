package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Request body for creating (or replacing, in a scenario update) a projection account.
 *
 * <p>{@code annualContribution} has no sign rule: a negative contribution models a planned
 * pre-retirement withdrawal, and stored scenarios hold such values.
 *
 * @param owner Household/survivor modeling (sub-project A): {@code primary}, {@code spouse}, or
 *              {@code joint} ({@code joint} only valid for {@code taxable} accounts). {@code null}
 *              resolves to {@code "primary"}, reproducing pre-household behavior byte-for-byte.
 */
public record CreateProjectionAccountRequest(
        UUID linkedAccountId,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        @DecimalMin("-1") @DecimalMax("1") BigDecimal expectedReturn,
        BigDecimal costBasis,
        AllocationDto allocation,
        String accountType,
        String owner) {

    /**
     * A hypothetical (unlinked) account cannot open with a negative balance or cost basis. A
     * linked row is exempt: the form echoes the linked account's live values, a bank balance is
     * negative whenever imported history has more debits than deposits, and the service ignores
     * both values for linked rows and reads the live account instead.
     */
    @JsonIgnore
    @AssertTrue(message = "initial_balance and cost_basis must not be negative")
    public boolean isHypotheticalAmountsNonNegative() {
        return linkedAccountId != null || isNotNegative(initialBalance) && isNotNegative(costBasis);
    }

    private static boolean isNotNegative(BigDecimal amount) {
        return amount == null || amount.signum() >= 0;
    }

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
