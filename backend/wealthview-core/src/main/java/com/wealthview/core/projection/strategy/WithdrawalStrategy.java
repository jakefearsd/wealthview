package com.wealthview.core.projection.strategy;

import java.math.BigDecimal;

public sealed interface WithdrawalStrategy
        permits FixedPercentageWithdrawal, DynamicPercentageWithdrawal, VanguardDynamicSpendingWithdrawal {

    BigDecimal computeWithdrawal(WithdrawalContext ctx);
}
