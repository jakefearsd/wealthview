package com.wealthview.core.projection.dto;

import com.wealthview.persistence.entity.ProjectionAccountEntity;

import java.math.BigDecimal;
import java.util.UUID;

public record ProjectionAccountResponse(
        UUID id,
        UUID linkedAccountId,
        String name,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn,
        String accountType) {

    /**
     * Builds a response for a projection account. Balance is computed by the service layer
     * (either the live linked-account balance or the hypothetical initial balance) since it
     * depends on per-tenant account data outside this record's visibility.
     */
    public static ProjectionAccountResponse from(ProjectionAccountEntity entity, BigDecimal balance) {
        var linked = entity.getLinkedAccount();
        return new ProjectionAccountResponse(
                entity.getId(),
                linked != null ? linked.getId() : null,
                linked != null ? linked.getName() : entity.getAccountType(),
                balance,
                entity.getAnnualContribution(),
                entity.getExpectedReturn(),
                entity.getAccountType());
    }
}
