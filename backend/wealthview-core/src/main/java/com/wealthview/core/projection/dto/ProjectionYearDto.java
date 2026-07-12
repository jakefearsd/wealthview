package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

// ExcessivePublicCount: the per-year projection result exposes a wide, flat read API (one
// accessor per output value) plus a builder facade. Internally the ~45 values are grouped
// into cohesive nested records; each group is @JsonUnwrapped so the JSON wire format stays
// flat (see ProjectionYearDtoSerializationTest and the projection golden files). The flat
// accessors below delegate to those groups, preserving every existing call site.
@SuppressWarnings("PMD.ExcessivePublicCount")
public record ProjectionYearDto(
        int year,
        int age,
        boolean retired,
        @JsonUnwrapped BalanceFlow flow,
        @JsonUnwrapped PoolBalances pools,
        @JsonUnwrapped PoolGrowth poolGrowth,
        @JsonUnwrapped PoolTaxPaid poolTaxPaid,
        @JsonUnwrapped PoolWithdrawals poolWithdrawals,
        @JsonUnwrapped Viability viability,
        @JsonUnwrapped IncomeDetail income,
        @JsonUnwrapped TaxBreakdown tax,
        @JsonUnwrapped NetWorth netWorth) {

    /**
     * Never-null-group invariant: an {@code @JsonUnwrapped} group that is {@code null} would
     * cause Jackson to omit all of its keys, silently breaking the flat wire contract. Groups
     * carry {@code null} <em>components</em> for absent values, but the group itself is always
     * present. This compact constructor enforces that for any construction path.
     */
    public ProjectionYearDto {
        flow = flow != null ? flow : BalanceFlow.empty();
        pools = pools != null ? pools : PoolBalances.empty();
        poolGrowth = poolGrowth != null ? poolGrowth : PoolGrowth.empty();
        poolTaxPaid = poolTaxPaid != null ? poolTaxPaid : PoolTaxPaid.empty();
        poolWithdrawals = poolWithdrawals != null ? poolWithdrawals : PoolWithdrawals.empty();
        viability = viability != null ? viability : Viability.empty();
        income = income != null ? income : IncomeDetail.empty();
        tax = tax != null ? tax : TaxBreakdown.empty();
        netWorth = netWorth != null ? netWorth : NetWorth.empty();
    }

    // --- Grouped value carriers. Component names match the flat wire field names so that
    //     @JsonUnwrapped + the global SNAKE_CASE strategy reproduce the exact JSON keys. ---

    /** Core per-year balance flow: opening balance, contributions, growth, withdrawals, close. */
    public record BalanceFlow(BigDecimal startBalance, BigDecimal contributions, BigDecimal growth,
                              BigDecimal withdrawals, BigDecimal endBalance) {
        static BalanceFlow empty() {
            return new BalanceFlow(null, null, null, null, null);
        }
    }

    /** Ending sub-pool balances by tax treatment. */
    public record PoolBalances(BigDecimal traditionalBalance, BigDecimal rothBalance,
                               BigDecimal taxableBalance) {
        static PoolBalances empty() {
            return new PoolBalances(null, null, null);
        }
    }

    /** Per-pool investment growth for the year. */
    public record PoolGrowth(BigDecimal taxableGrowth, BigDecimal traditionalGrowth,
                             BigDecimal rothGrowth) {
        static PoolGrowth empty() {
            return new PoolGrowth(null, null, null);
        }
    }

    /** Tax dollars sourced from each pool to pay the year's liability. */
    public record PoolTaxPaid(BigDecimal taxPaidFromTaxable, BigDecimal taxPaidFromTraditional,
                              BigDecimal taxPaidFromRoth) {
        static PoolTaxPaid empty() {
            return new PoolTaxPaid(null, null, null);
        }
    }

    /** Spending withdrawals drawn from each pool for the year. */
    public record PoolWithdrawals(BigDecimal withdrawalFromTaxable, BigDecimal withdrawalFromTraditional,
                                  BigDecimal withdrawalFromRoth) {
        static PoolWithdrawals empty() {
            return new PoolWithdrawals(null, null, null);
        }
    }

    /** Spending-feasibility view: needs, income offset, surplus, and post-cut discretionary. */
    public record Viability(BigDecimal essentialExpenses, BigDecimal discretionaryExpenses,
                            BigDecimal incomeStreamsTotal, BigDecimal netSpendingNeed,
                            BigDecimal spendingSurplus, BigDecimal discretionaryAfterCuts) {
        static Viability empty() {
            return new Viability(null, null, null, null, null, null);
        }
    }

    /** Income-source detail: rental cash/tax components, SS/SE tax, and per-source breakdown. */
    public record IncomeDetail(BigDecimal rentalIncomeGross, BigDecimal rentalExpensesTotal,
                               BigDecimal depreciationTotal, BigDecimal rentalLossApplied,
                               BigDecimal suspendedLossCarryforward, BigDecimal socialSecurityTaxable,
                               BigDecimal selfEmploymentTax, Map<String, BigDecimal> incomeBySource,
                               List<RentalPropertyYearDetail> rentalPropertyDetails) {
        static IncomeDetail empty() {
            return new IncomeDetail(null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Tax outputs for the year: conversion amount, total liability, and the itemized breakdown.
     * {@code rmdAmount} and {@code capitalGainsTax} are display breakouts of values already folded
     * into {@code taxLiability} / {@code federalTax} (the required-minimum-distribution amount and
     * the long-term capital-gains tax component, respectively) -- surfacing them here does NOT add
     * to {@code taxLiability}.
     *
     * <p>{@code irmaaSurcharge} is DIFFERENT: it is an ADDITIVE Medicare premium expense (not a
     * tax), funded through the pool cascade like other retirement expenses -- see
     * {@code RetirementWithdrawalProcessor}. It is NOT included in {@code taxLiability} /
     * {@code federalTax}. {@code irmaaWarning} is {@code true} exactly when {@code irmaaSurcharge}
     * is positive (derived from the real IRMAA tiers, audit Wave-4 IRMAA item -- previously a
     * warning-only bracket-ceiling proxy with no dollar figure).
     */
    public record TaxBreakdown(BigDecimal rothConversionAmount, BigDecimal taxLiability,
                               BigDecimal federalTax, BigDecimal stateTax, BigDecimal saltDeduction,
                               Boolean usedItemizedDeduction, Boolean irmaaWarning,
                               BigDecimal rmdAmount, BigDecimal capitalGainsTax, BigDecimal irmaaSurcharge) {
        static TaxBreakdown empty() {
            return new TaxBreakdown(null, null, null, null, null, null, null, null, null, null);
        }
    }

    /** Net-worth view: property equity, total net worth, and surplus reinvested this year. */
    public record NetWorth(BigDecimal propertyEquity, BigDecimal totalNetWorth,
                           BigDecimal surplusReinvested) {
        static NetWorth empty() {
            return new NetWorth(null, null, null);
        }
    }

    // --- Flat read API. Delegates to the grouped carriers; every historical accessor preserved. ---

    public BigDecimal startBalance() {
        return flow.startBalance();
    }

    public BigDecimal contributions() {
        return flow.contributions();
    }

    public BigDecimal growth() {
        return flow.growth();
    }

    public BigDecimal withdrawals() {
        return flow.withdrawals();
    }

    public BigDecimal endBalance() {
        return flow.endBalance();
    }

    public BigDecimal traditionalBalance() {
        return pools.traditionalBalance();
    }

    public BigDecimal rothBalance() {
        return pools.rothBalance();
    }

    public BigDecimal taxableBalance() {
        return pools.taxableBalance();
    }

    public BigDecimal taxableGrowth() {
        return poolGrowth.taxableGrowth();
    }

    public BigDecimal traditionalGrowth() {
        return poolGrowth.traditionalGrowth();
    }

    public BigDecimal rothGrowth() {
        return poolGrowth.rothGrowth();
    }

    public BigDecimal taxPaidFromTaxable() {
        return poolTaxPaid.taxPaidFromTaxable();
    }

    public BigDecimal taxPaidFromTraditional() {
        return poolTaxPaid.taxPaidFromTraditional();
    }

    public BigDecimal taxPaidFromRoth() {
        return poolTaxPaid.taxPaidFromRoth();
    }

    public BigDecimal withdrawalFromTaxable() {
        return poolWithdrawals.withdrawalFromTaxable();
    }

    public BigDecimal withdrawalFromTraditional() {
        return poolWithdrawals.withdrawalFromTraditional();
    }

    public BigDecimal withdrawalFromRoth() {
        return poolWithdrawals.withdrawalFromRoth();
    }

    public BigDecimal essentialExpenses() {
        return viability.essentialExpenses();
    }

    public BigDecimal discretionaryExpenses() {
        return viability.discretionaryExpenses();
    }

    public BigDecimal incomeStreamsTotal() {
        return viability.incomeStreamsTotal();
    }

    public BigDecimal netSpendingNeed() {
        return viability.netSpendingNeed();
    }

    public BigDecimal spendingSurplus() {
        return viability.spendingSurplus();
    }

    public BigDecimal discretionaryAfterCuts() {
        return viability.discretionaryAfterCuts();
    }

    public BigDecimal rentalIncomeGross() {
        return income.rentalIncomeGross();
    }

    public BigDecimal rentalExpensesTotal() {
        return income.rentalExpensesTotal();
    }

    public BigDecimal depreciationTotal() {
        return income.depreciationTotal();
    }

    public BigDecimal rentalLossApplied() {
        return income.rentalLossApplied();
    }

    public BigDecimal suspendedLossCarryforward() {
        return income.suspendedLossCarryforward();
    }

    public BigDecimal socialSecurityTaxable() {
        return income.socialSecurityTaxable();
    }

    public BigDecimal selfEmploymentTax() {
        return income.selfEmploymentTax();
    }

    public Map<String, BigDecimal> incomeBySource() {
        return income.incomeBySource();
    }

    public List<RentalPropertyYearDetail> rentalPropertyDetails() {
        return income.rentalPropertyDetails();
    }

    public BigDecimal rothConversionAmount() {
        return tax.rothConversionAmount();
    }

    public BigDecimal taxLiability() {
        return tax.taxLiability();
    }

    public BigDecimal federalTax() {
        return tax.federalTax();
    }

    public BigDecimal stateTax() {
        return tax.stateTax();
    }

    public BigDecimal saltDeduction() {
        return tax.saltDeduction();
    }

    public Boolean usedItemizedDeduction() {
        return tax.usedItemizedDeduction();
    }

    public Boolean irmaaWarning() {
        return tax.irmaaWarning();
    }

    public BigDecimal rmdAmount() {
        return tax.rmdAmount();
    }

    public BigDecimal capitalGainsTax() {
        return tax.capitalGainsTax();
    }

    public BigDecimal irmaaSurcharge() {
        return tax.irmaaSurcharge();
    }

    public BigDecimal propertyEquity() {
        return netWorth.propertyEquity();
    }

    public BigDecimal totalNetWorth() {
        return netWorth.totalNetWorth();
    }

    public BigDecimal surplusReinvested() {
        return netWorth.surplusReinvested();
    }

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
        if (surplusReinvested == null) {
            return this;
        }
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

    /**
     * Sets the year's IRMAA surcharge (null when zero/not applicable, matching the DTO's existing
     * "positive value or null" convention) and derives {@code irmaaWarning} from it directly.
     */
    public ProjectionYearDto withIrmaaSurcharge(BigDecimal irmaaSurcharge) {
        boolean positive = irmaaSurcharge != null && irmaaSurcharge.compareTo(BigDecimal.ZERO) > 0;
        return Builder.from(this)
                .irmaaSurcharge(positive ? irmaaSurcharge : null)
                .irmaaWarning(positive ? Boolean.TRUE : null)
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

    // TooManyFields: the builder is a flat construction facade over the grouped record above;
    // one mutable field per output value is intentional. build() re-groups them.
    @SuppressWarnings({"PMD.TooManyFields", "PMD.ExcessivePublicCount"})
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
        private BigDecimal rmdAmount;
        private BigDecimal capitalGainsTax;
        private BigDecimal irmaaSurcharge;

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
            b.rmdAmount = dto.rmdAmount();
            b.capitalGainsTax = dto.capitalGainsTax();
            b.irmaaSurcharge = dto.irmaaSurcharge();
            return b;
        }

        public Builder year(int year) {
            this.year = year;
            return this;
        }

        public Builder age(int age) {
            this.age = age;
            return this;
        }

        public Builder startBalance(BigDecimal startBalance) {
            this.startBalance = startBalance;
            return this;
        }

        public Builder contributions(BigDecimal contributions) {
            this.contributions = contributions;
            return this;
        }

        public Builder growth(BigDecimal growth) {
            this.growth = growth;
            return this;
        }

        public Builder withdrawals(BigDecimal withdrawals) {
            this.withdrawals = withdrawals;
            return this;
        }

        public Builder endBalance(BigDecimal endBalance) {
            this.endBalance = endBalance;
            return this;
        }

        public Builder retired(boolean retired) {
            this.retired = retired;
            return this;
        }

        public Builder traditionalBalance(BigDecimal traditionalBalance) {
            this.traditionalBalance = traditionalBalance;
            return this;
        }

        public Builder rothBalance(BigDecimal rothBalance) {
            this.rothBalance = rothBalance;
            return this;
        }

        public Builder taxableBalance(BigDecimal taxableBalance) {
            this.taxableBalance = taxableBalance;
            return this;
        }

        public Builder rothConversionAmount(BigDecimal rothConversionAmount) {
            this.rothConversionAmount = rothConversionAmount;
            return this;
        }

        public Builder taxLiability(BigDecimal taxLiability) {
            this.taxLiability = taxLiability;
            return this;
        }

        public Builder essentialExpenses(BigDecimal essentialExpenses) {
            this.essentialExpenses = essentialExpenses;
            return this;
        }

        public Builder discretionaryExpenses(BigDecimal discretionaryExpenses) {
            this.discretionaryExpenses = discretionaryExpenses;
            return this;
        }

        public Builder incomeStreamsTotal(BigDecimal incomeStreamsTotal) {
            this.incomeStreamsTotal = incomeStreamsTotal;
            return this;
        }

        public Builder netSpendingNeed(BigDecimal netSpendingNeed) {
            this.netSpendingNeed = netSpendingNeed;
            return this;
        }

        public Builder spendingSurplus(BigDecimal spendingSurplus) {
            this.spendingSurplus = spendingSurplus;
            return this;
        }

        public Builder discretionaryAfterCuts(BigDecimal discretionaryAfterCuts) {
            this.discretionaryAfterCuts = discretionaryAfterCuts;
            return this;
        }

        public Builder rentalIncomeGross(BigDecimal rentalIncomeGross) {
            this.rentalIncomeGross = rentalIncomeGross;
            return this;
        }

        public Builder rentalExpensesTotal(BigDecimal rentalExpensesTotal) {
            this.rentalExpensesTotal = rentalExpensesTotal;
            return this;
        }

        public Builder depreciationTotal(BigDecimal depreciationTotal) {
            this.depreciationTotal = depreciationTotal;
            return this;
        }

        public Builder rentalLossApplied(BigDecimal rentalLossApplied) {
            this.rentalLossApplied = rentalLossApplied;
            return this;
        }

        public Builder suspendedLossCarryforward(BigDecimal suspendedLossCarryforward) {
            this.suspendedLossCarryforward = suspendedLossCarryforward;
            return this;
        }

        public Builder socialSecurityTaxable(BigDecimal socialSecurityTaxable) {
            this.socialSecurityTaxable = socialSecurityTaxable;
            return this;
        }

        public Builder selfEmploymentTax(BigDecimal selfEmploymentTax) {
            this.selfEmploymentTax = selfEmploymentTax;
            return this;
        }

        public Builder incomeBySource(Map<String, BigDecimal> incomeBySource) {
            this.incomeBySource = incomeBySource;
            return this;
        }

        public Builder propertyEquity(BigDecimal propertyEquity) {
            this.propertyEquity = propertyEquity;
            return this;
        }

        public Builder totalNetWorth(BigDecimal totalNetWorth) {
            this.totalNetWorth = totalNetWorth;
            return this;
        }

        public Builder surplusReinvested(BigDecimal surplusReinvested) {
            this.surplusReinvested = surplusReinvested;
            return this;
        }

        public Builder taxableGrowth(BigDecimal taxableGrowth) {
            this.taxableGrowth = taxableGrowth;
            return this;
        }

        public Builder traditionalGrowth(BigDecimal traditionalGrowth) {
            this.traditionalGrowth = traditionalGrowth;
            return this;
        }

        public Builder rothGrowth(BigDecimal rothGrowth) {
            this.rothGrowth = rothGrowth;
            return this;
        }

        public Builder taxPaidFromTaxable(BigDecimal taxPaidFromTaxable) {
            this.taxPaidFromTaxable = taxPaidFromTaxable;
            return this;
        }

        public Builder taxPaidFromTraditional(BigDecimal taxPaidFromTraditional) {
            this.taxPaidFromTraditional = taxPaidFromTraditional;
            return this;
        }

        public Builder taxPaidFromRoth(BigDecimal taxPaidFromRoth) {
            this.taxPaidFromRoth = taxPaidFromRoth;
            return this;
        }

        public Builder withdrawalFromTaxable(BigDecimal withdrawalFromTaxable) {
            this.withdrawalFromTaxable = withdrawalFromTaxable;
            return this;
        }

        public Builder withdrawalFromTraditional(BigDecimal withdrawalFromTraditional) {
            this.withdrawalFromTraditional = withdrawalFromTraditional;
            return this;
        }

        public Builder withdrawalFromRoth(BigDecimal withdrawalFromRoth) {
            this.withdrawalFromRoth = withdrawalFromRoth;
            return this;
        }

        public Builder rentalPropertyDetails(List<RentalPropertyYearDetail> rentalPropertyDetails) {
            this.rentalPropertyDetails = rentalPropertyDetails;
            return this;
        }

        public Builder federalTax(BigDecimal federalTax) {
            this.federalTax = federalTax;
            return this;
        }

        public Builder stateTax(BigDecimal stateTax) {
            this.stateTax = stateTax;
            return this;
        }

        public Builder saltDeduction(BigDecimal saltDeduction) {
            this.saltDeduction = saltDeduction;
            return this;
        }

        public Builder usedItemizedDeduction(Boolean usedItemizedDeduction) {
            this.usedItemizedDeduction = usedItemizedDeduction;
            return this;
        }

        public Builder irmaaWarning(Boolean irmaaWarning) {
            this.irmaaWarning = irmaaWarning;
            return this;
        }

        public Builder rmdAmount(BigDecimal rmdAmount) {
            this.rmdAmount = rmdAmount;
            return this;
        }

        public Builder capitalGainsTax(BigDecimal capitalGainsTax) {
            this.capitalGainsTax = capitalGainsTax;
            return this;
        }

        public Builder irmaaSurcharge(BigDecimal irmaaSurcharge) {
            this.irmaaSurcharge = irmaaSurcharge;
            return this;
        }

        public ProjectionYearDto build() {
            return new ProjectionYearDto(
                    year, age, retired,
                    new BalanceFlow(startBalance, contributions, growth, withdrawals, endBalance),
                    new PoolBalances(traditionalBalance, rothBalance, taxableBalance),
                    new PoolGrowth(taxableGrowth, traditionalGrowth, rothGrowth),
                    new PoolTaxPaid(taxPaidFromTaxable, taxPaidFromTraditional, taxPaidFromRoth),
                    new PoolWithdrawals(withdrawalFromTaxable, withdrawalFromTraditional, withdrawalFromRoth),
                    new Viability(essentialExpenses, discretionaryExpenses, incomeStreamsTotal,
                            netSpendingNeed, spendingSurplus, discretionaryAfterCuts),
                    new IncomeDetail(rentalIncomeGross, rentalExpensesTotal, depreciationTotal,
                            rentalLossApplied, suspendedLossCarryforward, socialSecurityTaxable,
                            selfEmploymentTax, incomeBySource, rentalPropertyDetails),
                    new TaxBreakdown(rothConversionAmount, taxLiability, federalTax, stateTax,
                            saltDeduction, usedItemizedDeduction, irmaaWarning,
                            rmdAmount, capitalGainsTax, irmaaSurcharge),
                    new NetWorth(propertyEquity, totalNetWorth, surplusReinvested));
        }
    }
}
