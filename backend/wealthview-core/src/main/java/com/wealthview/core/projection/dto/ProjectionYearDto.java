package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;
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
        BigDecimal surplusReinvested,
        BigDecimal taxableGrowth,
        BigDecimal traditionalGrowth,
        BigDecimal rothGrowth,
        BigDecimal taxPaidFromTaxable,
        BigDecimal taxPaidFromTraditional,
        BigDecimal taxPaidFromRoth,
        BigDecimal withdrawalFromTaxable,
        BigDecimal withdrawalFromTraditional,
        BigDecimal withdrawalFromRoth,
        List<RentalPropertyYearDetail> rentalPropertyDetails,
        BigDecimal federalTax,
        BigDecimal stateTax,
        BigDecimal saltDeduction,
        Boolean usedItemizedDeduction) {

    public ProjectionYearDto withSurplusReinvested(BigDecimal surplusReinvested) {
        return new ProjectionYearDto(
                year(), age(), startBalance(), contributions(),
                growth(), withdrawals(), endBalance(), retired(),
                traditionalBalance(), rothBalance(), taxableBalance(),
                rothConversionAmount(), taxLiability(),
                essentialExpenses(), discretionaryExpenses(),
                incomeStreamsTotal(), netSpendingNeed(), spendingSurplus(),
                discretionaryAfterCuts(),
                rentalIncomeGross(), rentalExpensesTotal(), depreciationTotal(),
                rentalLossApplied(), suspendedLossCarryforward(),
                socialSecurityTaxable(), selfEmploymentTax(),
                incomeBySource(),
                propertyEquity(), totalNetWorth(), surplusReinvested,
                taxableGrowth(), traditionalGrowth(), rothGrowth(),
                taxPaidFromTaxable(), taxPaidFromTraditional(), taxPaidFromRoth(),
                withdrawalFromTaxable(), withdrawalFromTraditional(), withdrawalFromRoth(),
                rentalPropertyDetails(),
                federalTax(), stateTax(), saltDeduction(), usedItemizedDeduction());
    }

    public ProjectionYearDto withPropertyEquity(BigDecimal propertyEquity, BigDecimal totalNetWorth) {
        return new ProjectionYearDto(
                year(), age(), startBalance(), contributions(),
                growth(), withdrawals(), endBalance(), retired(),
                traditionalBalance(), rothBalance(), taxableBalance(),
                rothConversionAmount(), taxLiability(),
                essentialExpenses(), discretionaryExpenses(),
                incomeStreamsTotal(), netSpendingNeed(), spendingSurplus(),
                discretionaryAfterCuts(),
                rentalIncomeGross(), rentalExpensesTotal(), depreciationTotal(),
                rentalLossApplied(), suspendedLossCarryforward(),
                socialSecurityTaxable(), selfEmploymentTax(),
                incomeBySource(),
                propertyEquity, totalNetWorth, surplusReinvested(),
                taxableGrowth(), traditionalGrowth(), rothGrowth(),
                taxPaidFromTaxable(), taxPaidFromTraditional(), taxPaidFromRoth(),
                withdrawalFromTaxable(), withdrawalFromTraditional(), withdrawalFromRoth(),
                rentalPropertyDetails(),
                federalTax(), stateTax(), saltDeduction(), usedItemizedDeduction());
    }

    public ProjectionYearDto withTaxBreakdown(BigDecimal federalTax, BigDecimal stateTax,
                                                BigDecimal saltDeduction, Boolean usedItemizedDeduction) {
        return new ProjectionYearDto(
                year(), age(), startBalance(), contributions(),
                growth(), withdrawals(), endBalance(), retired(),
                traditionalBalance(), rothBalance(), taxableBalance(),
                rothConversionAmount(), taxLiability(),
                essentialExpenses(), discretionaryExpenses(),
                incomeStreamsTotal(), netSpendingNeed(), spendingSurplus(),
                discretionaryAfterCuts(),
                rentalIncomeGross(), rentalExpensesTotal(), depreciationTotal(),
                rentalLossApplied(), suspendedLossCarryforward(),
                socialSecurityTaxable(), selfEmploymentTax(),
                incomeBySource(),
                propertyEquity(), totalNetWorth(), surplusReinvested(),
                taxableGrowth(), traditionalGrowth(), rothGrowth(),
                taxPaidFromTaxable(), taxPaidFromTraditional(), taxPaidFromRoth(),
                withdrawalFromTaxable(), withdrawalFromTraditional(), withdrawalFromRoth(),
                rentalPropertyDetails(),
                federalTax, stateTax, saltDeduction, usedItemizedDeduction);
    }

    public static ProjectionYearDto simple(int year, int age, BigDecimal startBalance,
                                            BigDecimal contributions, BigDecimal growth,
                                            BigDecimal withdrawals, BigDecimal endBalance,
                                            boolean retired) {
        return new ProjectionYearDto(year, age, startBalance, contributions, growth,
                withdrawals, endBalance, retired, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
