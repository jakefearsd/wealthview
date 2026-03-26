package com.wealthview.core.projection.strategy;

import java.math.BigDecimal;

public record VanguardDynamicSpendingWithdrawal(
        BigDecimal rate,
        BigDecimal ceiling,
        BigDecimal floor) implements WithdrawalStrategy {

    @Override
    public BigDecimal computeWithdrawal(WithdrawalContext ctx) {
        BigDecimal raw = ctx.currentBalance().multiply(rate).setScale(SCALE, ROUNDING);

        if (ctx.yearsInRetirement() == 1 || ctx.previousWithdrawal().compareTo(BigDecimal.ZERO) == 0) {
            return raw;
        }

        BigDecimal maxAllowed = ctx.previousWithdrawal()
                .multiply(BigDecimal.ONE.add(ceiling))
                .setScale(SCALE, ROUNDING);
        BigDecimal minAllowed = ctx.previousWithdrawal()
                .multiply(BigDecimal.ONE.add(floor))
                .setScale(SCALE, ROUNDING);

        if (raw.compareTo(maxAllowed) > 0) {
            return maxAllowed;
        }
        if (raw.compareTo(minAllowed) < 0) {
            return minAllowed;
        }
        return raw;
    }
}
