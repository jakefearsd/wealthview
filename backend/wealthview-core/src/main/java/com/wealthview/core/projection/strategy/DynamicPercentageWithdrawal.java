package com.wealthview.core.projection.strategy;

import java.math.BigDecimal;

public record DynamicPercentageWithdrawal(BigDecimal rate) implements WithdrawalStrategy {

    @Override
    public BigDecimal computeWithdrawal(WithdrawalContext ctx) {
        return ctx.currentBalance().multiply(rate).setScale(SCALE, ROUNDING);
    }
}
