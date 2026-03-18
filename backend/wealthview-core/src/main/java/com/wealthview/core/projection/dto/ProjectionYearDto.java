package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Map;

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
        BigDecimal discretionaryAfterCuts,
        BigDecimal rentalIncomeGross,
        BigDecimal rentalExpensesTotal,
        BigDecimal depreciationTotal,
        BigDecimal rentalLossApplied,
        BigDecimal suspendedLossCarryforward,
        BigDecimal socialSecurityTaxable,
        BigDecimal selfEmploymentTax,
        Map<String, BigDecimal> incomeBySource,
        BigDecimal propertyEquity,
        BigDecimal totalNetWorth,
        BigDecimal surplusReinvested) {

    public static ProjectionYearDto simple(int year, int age, BigDecimal startBalance,
                                            BigDecimal contributions, BigDecimal growth,
                                            BigDecimal withdrawals, BigDecimal endBalance,
                                            boolean retired) {
        return new ProjectionYearDto(year, age, startBalance, contributions, growth,
                withdrawals, endBalance, retired, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null);
    }
}
