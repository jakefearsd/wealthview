package com.wealthview.core.projection.strategy;

import java.math.BigDecimal;

public record FixedPercentageWithdrawal(BigDecimal rate) implements WithdrawalStrategy {

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
