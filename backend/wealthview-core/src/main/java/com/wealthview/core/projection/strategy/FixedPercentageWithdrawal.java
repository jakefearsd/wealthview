package com.wealthview.core.projection.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record FixedPercentageWithdrawal(BigDecimal rate) implements WithdrawalStrategy {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    @Override
    public BigDecimal computeWithdrawal(WithdrawalContext ctx) {
        if (ctx.yearsInRetirement() == 1) {
            return ctx.startOfYearBalance().multiply(rate).setScale(SCALE, ROUNDING);
        }
        return ctx.previousWithdrawal()
                .multiply(BigDecimal.ONE.add(ctx.inflationRate()))
                .setScale(SCALE, ROUNDING);
    }
}
