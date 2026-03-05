package com.wealthview.core.price.dto;

import com.wealthview.persistence.entity.PriceEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceResponse(
        String symbol,
        LocalDate date,
        BigDecimal closePrice,
        String source
) {
    public static PriceResponse from(PriceEntity entity) {
        return new PriceResponse(
                entity.getSymbol(),
                entity.getDate(),
                entity.getClosePrice(),
                entity.getSource()
        );
    }
}
