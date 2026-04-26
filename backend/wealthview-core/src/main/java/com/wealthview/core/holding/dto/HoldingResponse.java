package com.wealthview.core.holding.dto;

import com.wealthview.persistence.entity.HoldingEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import com.wealthview.core.common.Money;

public record HoldingResponse(
        UUID id,
        UUID accountId,
        String symbol,
        BigDecimal quantity,
        BigDecimal costBasis,
        boolean isManualOverride,
        boolean isMoneyMarket,
        BigDecimal moneyMarketRate,
        LocalDate asOfDate,
        BigDecimal currentPrice,
        BigDecimal marketValue,
        BigDecimal gainLoss
) {
    public static HoldingResponse from(HoldingEntity entity) {
        return from(entity, null);
    }

    public static HoldingResponse from(HoldingEntity entity, BigDecimal latestPrice) {
        var qty = entity.getQuantity();
        var cost = entity.getCostBasis();
        BigDecimal mktValue = null;
        BigDecimal gl = null;

        if (latestPrice != null && qty != null) {
            mktValue = qty.multiply(latestPrice).setScale(Money.SCALE, Money.ROUNDING);
            if (cost != null) {
                gl = mktValue.subtract(cost);
            }
        }

        return new HoldingResponse(
                entity.getId(),
                entity.getAccountId(),
                entity.getSymbol(),
                qty,
                cost,
                entity.isManualOverride(),
                entity.isMoneyMarket(),
                entity.getMoneyMarketRate(),
                entity.getAsOfDate(),
                latestPrice,
                mktValue,
                gl
        );
    }
}
