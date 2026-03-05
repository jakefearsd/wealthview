package com.wealthview.core.property.dto;

import com.wealthview.persistence.entity.PropertyEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PropertyResponse(
        UUID id,
        String address,
        BigDecimal purchasePrice,
        LocalDate purchaseDate,
        BigDecimal currentValue,
        BigDecimal mortgageBalance,
        BigDecimal equity
) {
    public static PropertyResponse from(PropertyEntity entity) {
        return new PropertyResponse(
                entity.getId(),
                entity.getAddress(),
                entity.getPurchasePrice(),
                entity.getPurchaseDate(),
                entity.getCurrentValue(),
                entity.getMortgageBalance(),
                entity.getEquity()
        );
    }
}
