package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record GuardrailYearlySpending(
        int year,
        int age,
        BigDecimal recommended,
        BigDecimal corridorLow,
        BigDecimal corridorHigh,
        BigDecimal essentialFloor,
        BigDecimal discretionary,
        BigDecimal incomeOffset,
        BigDecimal portfolioWithdrawal,
        String phaseName,
        BigDecimal portfolioBalanceMedian,
        BigDecimal portfolioBalanceP10,
        BigDecimal portfolioBalanceP25
) {

    public GuardrailYearlySpending(int year, int age, BigDecimal recommended,
                                    BigDecimal corridorLow, BigDecimal corridorHigh,
                                    BigDecimal essentialFloor, BigDecimal discretionary,
                                    BigDecimal incomeOffset, BigDecimal portfolioWithdrawal,
                                    String phaseName) {
        this(year, age, recommended, corridorLow, corridorHigh, essentialFloor,
                discretionary, incomeOffset, portfolioWithdrawal, phaseName,
                null, null, null);
    }
}
