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
        Boolean usedItemizedDeduction,
        Boolean irmaaWarning) {

    public static Builder builder() {
        return new Builder();
    }

    public ProjectionYearDto withViability(BigDecimal essentialExpenses, BigDecimal discretionaryExpenses,
                                            BigDecimal incomeStreamsTotal, BigDecimal netSpendingNeed,
                                            BigDecimal spendingSurplus, BigDecimal discretionaryAfterCuts) {
        return Builder.from(this)
                .essentialExpenses(essentialExpenses)
                .discretionaryExpenses(discretionaryExpenses)
                .incomeStreamsTotal(incomeStreamsTotal)
                .netSpendingNeed(netSpendingNeed)
                .spendingSurplus(spendingSurplus)
                .discretionaryAfterCuts(discretionaryAfterCuts)
                .build();
    }

    public ProjectionYearDto withIncomeSourceFields(BigDecimal incomeStreamsTotal,
                                                      BigDecimal rentalIncomeGross,
                                                      BigDecimal rentalExpensesTotal,
                                                      BigDecimal depreciationTotal,
                                                      BigDecimal rentalLossApplied,
                                                      BigDecimal suspendedLossCarryforward,
                                                      BigDecimal socialSecurityTaxable,
                                                      BigDecimal selfEmploymentTax,
                                                      Map<String, BigDecimal> incomeBySource,
                                                      List<RentalPropertyYearDetail> rentalPropertyDetails) {
        return Builder.from(this)
                .incomeStreamsTotal(incomeStreamsTotal)
                .rentalIncomeGross(rentalIncomeGross)
                .rentalExpensesTotal(rentalExpensesTotal)
                .depreciationTotal(depreciationTotal)
                .rentalLossApplied(rentalLossApplied)
                .suspendedLossCarryforward(suspendedLossCarryforward)
                .socialSecurityTaxable(socialSecurityTaxable)
                .selfEmploymentTax(selfEmploymentTax)
                .incomeBySource(incomeBySource)
                .rentalPropertyDetails(rentalPropertyDetails)
                .build();
    }

    public ProjectionYearDto withSurplusReinvested(BigDecimal surplusReinvested) {
        return Builder.from(this)
                .surplusReinvested(surplusReinvested)
                .build();
    }

    public ProjectionYearDto withPropertyEquity(BigDecimal propertyEquity, BigDecimal totalNetWorth) {
        return Builder.from(this)
                .propertyEquity(propertyEquity)
                .totalNetWorth(totalNetWorth)
                .build();
    }

    public ProjectionYearDto withTaxBreakdown(BigDecimal federalTax, BigDecimal stateTax,
                                                BigDecimal saltDeduction, Boolean usedItemizedDeduction) {
        return Builder.from(this)
                .federalTax(federalTax)
                .stateTax(stateTax)
                .saltDeduction(saltDeduction)
                .usedItemizedDeduction(usedItemizedDeduction)
                .build();
    }

    public ProjectionYearDto withIrmaaWarning(Boolean irmaaWarning) {
        return Builder.from(this)
                .irmaaWarning(irmaaWarning)
                .build();
    }

    public static ProjectionYearDto simple(int year, int age, BigDecimal startBalance,
                                            BigDecimal contributions, BigDecimal growth,
                                            BigDecimal withdrawals, BigDecimal endBalance,
                                            boolean retired) {
        return builder()
                .year(year)
                .age(age)
                .startBalance(startBalance)
                .contributions(contributions)
                .growth(growth)
                .withdrawals(withdrawals)
                .endBalance(endBalance)
                .retired(retired)
                .build();
    }

    public static final class Builder {
        private int year;
        private int age;
        private BigDecimal startBalance;
        private BigDecimal contributions;
        private BigDecimal growth;
        private BigDecimal withdrawals;
        private BigDecimal endBalance;
        private boolean retired;
        private BigDecimal traditionalBalance;
        private BigDecimal rothBalance;
        private BigDecimal taxableBalance;
        private BigDecimal rothConversionAmount;
        private BigDecimal taxLiability;
        private BigDecimal essentialExpenses;
        private BigDecimal discretionaryExpenses;
        private BigDecimal incomeStreamsTotal;
        private BigDecimal netSpendingNeed;
        private BigDecimal spendingSurplus;
        private BigDecimal discretionaryAfterCuts;
        private BigDecimal rentalIncomeGross;
        private BigDecimal rentalExpensesTotal;
        private BigDecimal depreciationTotal;
        private BigDecimal rentalLossApplied;
        private BigDecimal suspendedLossCarryforward;
        private BigDecimal socialSecurityTaxable;
        private BigDecimal selfEmploymentTax;
        private Map<String, BigDecimal> incomeBySource;
        private BigDecimal propertyEquity;
        private BigDecimal totalNetWorth;
        private BigDecimal surplusReinvested;
        private BigDecimal taxableGrowth;
        private BigDecimal traditionalGrowth;
        private BigDecimal rothGrowth;
        private BigDecimal taxPaidFromTaxable;
        private BigDecimal taxPaidFromTraditional;
        private BigDecimal taxPaidFromRoth;
        private BigDecimal withdrawalFromTaxable;
        private BigDecimal withdrawalFromTraditional;
        private BigDecimal withdrawalFromRoth;
        private List<RentalPropertyYearDetail> rentalPropertyDetails;
        private BigDecimal federalTax;
        private BigDecimal stateTax;
        private BigDecimal saltDeduction;
        private Boolean usedItemizedDeduction;
        private Boolean irmaaWarning;

        private Builder() {}

        public static Builder from(ProjectionYearDto dto) {
            var b = new Builder();
            b.year = dto.year();
            b.age = dto.age();
            b.startBalance = dto.startBalance();
            b.contributions = dto.contributions();
            b.growth = dto.growth();
            b.withdrawals = dto.withdrawals();
            b.endBalance = dto.endBalance();
            b.retired = dto.retired();
            b.traditionalBalance = dto.traditionalBalance();
            b.rothBalance = dto.rothBalance();
            b.taxableBalance = dto.taxableBalance();
            b.rothConversionAmount = dto.rothConversionAmount();
            b.taxLiability = dto.taxLiability();
            b.essentialExpenses = dto.essentialExpenses();
            b.discretionaryExpenses = dto.discretionaryExpenses();
            b.incomeStreamsTotal = dto.incomeStreamsTotal();
            b.netSpendingNeed = dto.netSpendingNeed();
            b.spendingSurplus = dto.spendingSurplus();
            b.discretionaryAfterCuts = dto.discretionaryAfterCuts();
            b.rentalIncomeGross = dto.rentalIncomeGross();
            b.rentalExpensesTotal = dto.rentalExpensesTotal();
            b.depreciationTotal = dto.depreciationTotal();
            b.rentalLossApplied = dto.rentalLossApplied();
            b.suspendedLossCarryforward = dto.suspendedLossCarryforward();
            b.socialSecurityTaxable = dto.socialSecurityTaxable();
            b.selfEmploymentTax = dto.selfEmploymentTax();
            b.incomeBySource = dto.incomeBySource();
            b.propertyEquity = dto.propertyEquity();
            b.totalNetWorth = dto.totalNetWorth();
            b.surplusReinvested = dto.surplusReinvested();
            b.taxableGrowth = dto.taxableGrowth();
            b.traditionalGrowth = dto.traditionalGrowth();
            b.rothGrowth = dto.rothGrowth();
            b.taxPaidFromTaxable = dto.taxPaidFromTaxable();
            b.taxPaidFromTraditional = dto.taxPaidFromTraditional();
            b.taxPaidFromRoth = dto.taxPaidFromRoth();
            b.withdrawalFromTaxable = dto.withdrawalFromTaxable();
            b.withdrawalFromTraditional = dto.withdrawalFromTraditional();
            b.withdrawalFromRoth = dto.withdrawalFromRoth();
            b.rentalPropertyDetails = dto.rentalPropertyDetails();
            b.federalTax = dto.federalTax();
            b.stateTax = dto.stateTax();
            b.saltDeduction = dto.saltDeduction();
            b.usedItemizedDeduction = dto.usedItemizedDeduction();
            b.irmaaWarning = dto.irmaaWarning();
            return b;
        }

        public Builder year(int year) { this.year = year; return this; }
        public Builder age(int age) { this.age = age; return this; }
        public Builder startBalance(BigDecimal startBalance) { this.startBalance = startBalance; return this; }
        public Builder contributions(BigDecimal contributions) { this.contributions = contributions; return this; }
        public Builder growth(BigDecimal growth) { this.growth = growth; return this; }
        public Builder withdrawals(BigDecimal withdrawals) { this.withdrawals = withdrawals; return this; }
        public Builder endBalance(BigDecimal endBalance) { this.endBalance = endBalance; return this; }
        public Builder retired(boolean retired) { this.retired = retired; return this; }
        public Builder traditionalBalance(BigDecimal traditionalBalance) { this.traditionalBalance = traditionalBalance; return this; }
        public Builder rothBalance(BigDecimal rothBalance) { this.rothBalance = rothBalance; return this; }
        public Builder taxableBalance(BigDecimal taxableBalance) { this.taxableBalance = taxableBalance; return this; }
        public Builder rothConversionAmount(BigDecimal rothConversionAmount) { this.rothConversionAmount = rothConversionAmount; return this; }
        public Builder taxLiability(BigDecimal taxLiability) { this.taxLiability = taxLiability; return this; }
        public Builder essentialExpenses(BigDecimal essentialExpenses) { this.essentialExpenses = essentialExpenses; return this; }
        public Builder discretionaryExpenses(BigDecimal discretionaryExpenses) { this.discretionaryExpenses = discretionaryExpenses; return this; }
        public Builder incomeStreamsTotal(BigDecimal incomeStreamsTotal) { this.incomeStreamsTotal = incomeStreamsTotal; return this; }
        public Builder netSpendingNeed(BigDecimal netSpendingNeed) { this.netSpendingNeed = netSpendingNeed; return this; }
        public Builder spendingSurplus(BigDecimal spendingSurplus) { this.spendingSurplus = spendingSurplus; return this; }
        public Builder discretionaryAfterCuts(BigDecimal discretionaryAfterCuts) { this.discretionaryAfterCuts = discretionaryAfterCuts; return this; }
        public Builder rentalIncomeGross(BigDecimal rentalIncomeGross) { this.rentalIncomeGross = rentalIncomeGross; return this; }
        public Builder rentalExpensesTotal(BigDecimal rentalExpensesTotal) { this.rentalExpensesTotal = rentalExpensesTotal; return this; }
        public Builder depreciationTotal(BigDecimal depreciationTotal) { this.depreciationTotal = depreciationTotal; return this; }
        public Builder rentalLossApplied(BigDecimal rentalLossApplied) { this.rentalLossApplied = rentalLossApplied; return this; }
        public Builder suspendedLossCarryforward(BigDecimal suspendedLossCarryforward) { this.suspendedLossCarryforward = suspendedLossCarryforward; return this; }
        public Builder socialSecurityTaxable(BigDecimal socialSecurityTaxable) { this.socialSecurityTaxable = socialSecurityTaxable; return this; }
        public Builder selfEmploymentTax(BigDecimal selfEmploymentTax) { this.selfEmploymentTax = selfEmploymentTax; return this; }
        public Builder incomeBySource(Map<String, BigDecimal> incomeBySource) { this.incomeBySource = incomeBySource; return this; }
        public Builder propertyEquity(BigDecimal propertyEquity) { this.propertyEquity = propertyEquity; return this; }
        public Builder totalNetWorth(BigDecimal totalNetWorth) { this.totalNetWorth = totalNetWorth; return this; }
        public Builder surplusReinvested(BigDecimal surplusReinvested) { this.surplusReinvested = surplusReinvested; return this; }
        public Builder taxableGrowth(BigDecimal taxableGrowth) { this.taxableGrowth = taxableGrowth; return this; }
        public Builder traditionalGrowth(BigDecimal traditionalGrowth) { this.traditionalGrowth = traditionalGrowth; return this; }
        public Builder rothGrowth(BigDecimal rothGrowth) { this.rothGrowth = rothGrowth; return this; }
        public Builder taxPaidFromTaxable(BigDecimal taxPaidFromTaxable) { this.taxPaidFromTaxable = taxPaidFromTaxable; return this; }
        public Builder taxPaidFromTraditional(BigDecimal taxPaidFromTraditional) { this.taxPaidFromTraditional = taxPaidFromTraditional; return this; }
        public Builder taxPaidFromRoth(BigDecimal taxPaidFromRoth) { this.taxPaidFromRoth = taxPaidFromRoth; return this; }
        public Builder withdrawalFromTaxable(BigDecimal withdrawalFromTaxable) { this.withdrawalFromTaxable = withdrawalFromTaxable; return this; }
        public Builder withdrawalFromTraditional(BigDecimal withdrawalFromTraditional) { this.withdrawalFromTraditional = withdrawalFromTraditional; return this; }
        public Builder withdrawalFromRoth(BigDecimal withdrawalFromRoth) { this.withdrawalFromRoth = withdrawalFromRoth; return this; }
        public Builder rentalPropertyDetails(List<RentalPropertyYearDetail> rentalPropertyDetails) { this.rentalPropertyDetails = rentalPropertyDetails; return this; }
        public Builder federalTax(BigDecimal federalTax) { this.federalTax = federalTax; return this; }
        public Builder stateTax(BigDecimal stateTax) { this.stateTax = stateTax; return this; }
        public Builder saltDeduction(BigDecimal saltDeduction) { this.saltDeduction = saltDeduction; return this; }
        public Builder usedItemizedDeduction(Boolean usedItemizedDeduction) { this.usedItemizedDeduction = usedItemizedDeduction; return this; }
        public Builder irmaaWarning(Boolean irmaaWarning) { this.irmaaWarning = irmaaWarning; return this; }

        public ProjectionYearDto build() {
            return new ProjectionYearDto(
                    year, age, startBalance, contributions, growth, withdrawals, endBalance, retired,
                    traditionalBalance, rothBalance, taxableBalance,
                    rothConversionAmount, taxLiability,
                    essentialExpenses, discretionaryExpenses,
                    incomeStreamsTotal, netSpendingNeed, spendingSurplus, discretionaryAfterCuts,
                    rentalIncomeGross, rentalExpensesTotal, depreciationTotal,
                    rentalLossApplied, suspendedLossCarryforward,
                    socialSecurityTaxable, selfEmploymentTax, incomeBySource,
                    propertyEquity, totalNetWorth, surplusReinvested,
                    taxableGrowth, traditionalGrowth, rothGrowth,
                    taxPaidFromTaxable, taxPaidFromTraditional, taxPaidFromRoth,
                    withdrawalFromTaxable, withdrawalFromTraditional, withdrawalFromRoth,
                    rentalPropertyDetails,
                    federalTax, stateTax, saltDeduction, usedItemizedDeduction, irmaaWarning);
        }
    }
}
