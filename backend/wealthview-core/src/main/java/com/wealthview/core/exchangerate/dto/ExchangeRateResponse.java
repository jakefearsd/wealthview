package com.wealthview.core.exchangerate.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.wealthview.persistence.entity.ExchangeRateEntity;

public record ExchangeRateResponse(
        String currencyCode,
        BigDecimal rateToUsd,
        OffsetDateTime updatedAt
) {
    public static ExchangeRateResponse from(ExchangeRateEntity entity) {
        return new ExchangeRateResponse(
                entity.getCurrencyCode(),
                entity.getRateToUsd(),
                entity.getUpdatedAt());
    }
}
