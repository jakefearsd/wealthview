package com.wealthview.core.price.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.wealthview.persistence.entity.PriceEntity;

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
