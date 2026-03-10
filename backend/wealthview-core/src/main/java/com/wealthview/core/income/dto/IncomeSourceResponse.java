package com.wealthview.core.income.dto;

import com.wealthview.persistence.entity.IncomeSourceEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record IncomeSourceResponse(
        UUID id,
        String name,
        String incomeType,
        BigDecimal annualAmount,
        int startAge,
        Integer endAge,
        BigDecimal inflationRate,
        boolean oneTime,
        String taxTreatment,
        UUID propertyId,
        String propertyAddress,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static IncomeSourceResponse from(IncomeSourceEntity entity) {
        var property = entity.getProperty();
        return new IncomeSourceResponse(
                entity.getId(),
                entity.getName(),
                entity.getIncomeType(),
                entity.getAnnualAmount(),
                entity.getStartAge(),
                entity.getEndAge(),
                entity.getInflationRate(),
                entity.isOneTime(),
                entity.getTaxTreatment(),
                property != null ? property.getId() : null,
                property != null ? property.getAddress() : null,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
