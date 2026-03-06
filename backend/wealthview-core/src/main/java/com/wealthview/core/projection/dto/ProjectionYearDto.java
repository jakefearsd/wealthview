package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record ProjectionYearDto(
        int year,
        int age,
        BigDecimal startBalance,
        BigDecimal contributions,
        BigDecimal growth,
        BigDecimal withdrawals,
        BigDecimal endBalance,
        boolean retired,
        BigDecimal traditionalBalance,
        BigDecimal rothBalance,
        BigDecimal taxableBalance,
        BigDecimal rothConversionAmount,
        BigDecimal taxLiability,
        BigDecimal essentialExpenses,
        BigDecimal discretionaryExpenses,
        BigDecimal incomeStreamsTotal,
        BigDecimal netSpendingNeed,
        BigDecimal spendingSurplus,
        BigDecimal discretionaryAfterCuts) {

    public static ProjectionYearDto simple(int year, int age, BigDecimal startBalance,
                                            BigDecimal contributions, BigDecimal growth,
                                            BigDecimal withdrawals, BigDecimal endBalance,
                                            boolean retired) {
        return new ProjectionYearDto(year, age, startBalance, contributions, growth,
                withdrawals, endBalance, retired, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
