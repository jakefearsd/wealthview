package com.wealthview.core.projection.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record DynamicPercentageWithdrawal(BigDecimal rate) implements WithdrawalStrategy {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    @Override
    public BigDecimal computeWithdrawal(WithdrawalContext ctx) {
        return ctx.currentBalance().multiply(rate).setScale(SCALE, ROUNDING);
    }
}
