package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.wealthview.persistence.entity.ProjectionAccountEntity;

public record ProjectionAccountResponse(
        UUID id,
        UUID linkedAccountId,
        String name,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn,
        BigDecimal costBasis,
        AllocationDto allocation,
        boolean allocationIsOverride,
        String accountType,
        String owner) {

    /**
     * Builds a response for a projection account. Balance, effective allocation, and cost basis
     * are computed by the service layer (they depend on per-tenant account/holdings data outside
     * this record's visibility): balance is the live linked-account balance or the hypothetical
     * initial balance; allocation is the user override, else holdings-derived, else
     * {@link AssetAllocation#ALL_US}; cost basis is the live holdings sum or the hypothetical
     * stored value. {@code allocationIsOverride} tells the client whether {@code allocation} is a
     * user-stored override (true) or an auto-derived / default mix (false), so an edit round-trip
     * can preserve or clear it correctly.
     */
    public static ProjectionAccountResponse from(ProjectionAccountEntity entity, BigDecimal balance,
                                                 AllocationDto allocation, boolean allocationIsOverride,
                                                 BigDecimal costBasis) {
        var linked = entity.getLinkedAccount();
        return new ProjectionAccountResponse(
                entity.getId(),
                linked != null ? linked.getId() : null,
                linked != null ? linked.getName() : entity.getAccountType(),
                balance,
                entity.getAnnualContribution(),
                entity.getExpectedReturn(),
                costBasis,
                allocation,
                allocationIsOverride,
                entity.getAccountType(),
                entity.getOwner());
    }
}
