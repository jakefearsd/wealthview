package com.wealthview.core.projection.strategy;

import java.math.BigDecimal;

public record FixedPercentageWithdrawal(BigDecimal rate) implements WithdrawalStrategy {

    @Override
    public BigDecimal computeWithdrawal(WithdrawalContext ctx) {
        if (ctx.yearsInRetirement() == 1) {
            return ctx.startOfYearBalance().multiply(rate).setScale(SCALE, ROUNDING);
        }
        // Real-terms projection: the first-year withdrawal is expressed in today's dollars and held
        // constant real thereafter (no nominal inflation escalation — that would double-count against
        // the real-return portfolio growth).
        return ctx.previousWithdrawal().setScale(SCALE, ROUNDING);
    }
}
