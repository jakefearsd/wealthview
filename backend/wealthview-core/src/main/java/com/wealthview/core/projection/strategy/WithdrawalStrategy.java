package com.wealthview.core.projection.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;

public sealed interface WithdrawalStrategy
        permits FixedPercentageWithdrawal, DynamicPercentageWithdrawal, VanguardDynamicSpendingWithdrawal {

    int SCALE = 4;
    RoundingMode ROUNDING = RoundingMode.HALF_UP;

    BigDecimal computeWithdrawal(WithdrawalContext ctx);
}
