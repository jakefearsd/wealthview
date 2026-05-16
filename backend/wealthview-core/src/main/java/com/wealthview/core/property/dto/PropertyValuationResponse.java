package com.wealthview.core.property.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.wealthview.persistence.entity.PropertyValuationEntity;

public record PropertyValuationResponse(
        UUID id,
        LocalDate valuationDate,
        BigDecimal value,
        String source
) {
    public static PropertyValuationResponse from(PropertyValuationEntity entity) {
        return new PropertyValuationResponse(
                entity.getId(),
                entity.getValuationDate(),
                entity.getValue(),
                entity.getSource()
        );
    }
}
