package com.wealthview.core.holding.dto;

import com.wealthview.persistence.entity.HoldingEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record HoldingResponse(
        UUID id,
        UUID accountId,
        String symbol,
        BigDecimal quantity,
        BigDecimal costBasis,
        boolean isManualOverride,
        LocalDate asOfDate
) {
    public static HoldingResponse from(HoldingEntity entity) {
        return new HoldingResponse(
                entity.getId(),
                entity.getAccountId(),
                entity.getSymbol(),
                entity.getQuantity(),
                entity.getCostBasis(),
                entity.isManualOverride(),
                entity.getAsOfDate()
        );
    }
}
