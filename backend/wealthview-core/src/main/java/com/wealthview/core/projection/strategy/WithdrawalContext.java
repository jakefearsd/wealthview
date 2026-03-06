package com.wealthview.core.projection.strategy;

import java.math.BigDecimal;

public record WithdrawalContext(
        BigDecimal currentBalance,
        BigDecimal startOfYearBalance,
        BigDecimal previousWithdrawal,
        BigDecimal portfolioReturnRate,
        BigDecimal inflationRate,
        int yearsInRetirement) {
}
