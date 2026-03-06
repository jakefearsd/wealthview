package com.wealthview.core.projection.dto;

import com.wealthview.persistence.entity.ProjectionAccountEntity;

import java.math.BigDecimal;
import java.util.UUID;

public record ProjectionAccountResponse(
        UUID id,
        UUID linkedAccountId,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn) {

    public static ProjectionAccountResponse from(ProjectionAccountEntity entity) {
        return new ProjectionAccountResponse(
                entity.getId(),
                entity.getLinkedAccount() != null ? entity.getLinkedAccount().getId() : null,
                entity.getInitialBalance(),
                entity.getAnnualContribution(),
                entity.getExpectedReturn());
    }
}
