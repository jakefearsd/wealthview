package com.wealthview.core.property.dto;

import com.wealthview.persistence.entity.PropertyExpenseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PropertyExpenseResponse(
        UUID id,
        LocalDate date,
        BigDecimal amount,
        String category,
        String description,
        String frequency
) {
    public static PropertyExpenseResponse from(PropertyExpenseEntity entity) {
        return new PropertyExpenseResponse(
                entity.getId(),
                entity.getDate(),
                entity.getAmount(),
                entity.getCategory(),
                entity.getDescription(),
                entity.getFrequency()
        );
    }
}
