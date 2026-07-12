package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.tax.CombinedTaxResult;

/**
 * Assembles the per-year {@link ProjectionYearDto} for a {@link PoolStrategy.MultiPool}.
 *
 * <p>Extracted from {@code PoolStrategy.MultiPool.buildYearDto} during the Phase 3
 * decomposition. The DTO assembly (including the "positive value or null" conventions used by
 * the front end) is unchanged; only the long argument list is collapsed into the
 * {@link YearDtoInputs} parameter object.
 */
final class MultiPoolYearDtoBuilder {

    /**
     * Per-year inputs for {@link #build}. Collapses what would otherwise be a long
     * parameter list. {@code endingTaxable / endingTraditional / endingRoth} are the
     * post-floor sub-pool balances; {@code endBalance} is their aggregate.
     */
    record YearDtoInputs(int year, int age, BigDecimal startBalance, BigDecimal contributions,
                         BigDecimal totalGrowth, BigDecimal withdrawals, boolean retired,
                         BigDecimal conversionAmount, BigDecimal taxLiability,
                         PoolStrategy.GrowthResult growthResult,
                         BigDecimal withdrawalFromTaxable, BigDecimal withdrawalFromTraditional,
                         BigDecimal withdrawalFromRoth,
                         PoolStrategy.TaxSourceResult combinedTaxSource,
                         BigDecimal endBalance, BigDecimal endingTaxable,
                         BigDecimal endingTraditional, BigDecimal endingRoth,
                         BigDecimal rmdAmount, BigDecimal ltcgTax,
                         BigDecimal earlyWithdrawalPenalty) {}

    private MultiPoolYearDtoBuilder() {
    }

    /**
     * Builds the year DTO. {@code yearTaxBreakdown} is the tax breakdown accumulated during the
     * year's withdrawal + conversion cycle, or {@code null} if no tax was computed this year.
     */
    static ProjectionYearDto build(YearDtoInputs in, @Nullable CombinedTaxResult yearTaxBreakdown) {
        Optional<CombinedTaxResult> taxBreakdown = Optional.ofNullable(yearTaxBreakdown);
        BigDecimal fedTax = taxBreakdown.map(CombinedTaxResult::federalTax).orElse(null);
        BigDecimal stTax = taxBreakdown
                .filter(b -> b.stateTax().compareTo(BigDecimal.ZERO) > 0)
                .map(CombinedTaxResult::stateTax)
                .orElse(null);
        BigDecimal saltDed = taxBreakdown
                .filter(b -> b.saltDeduction().compareTo(BigDecimal.ZERO) > 0)
                .map(CombinedTaxResult::saltDeduction)
                .orElse(null);
        Boolean usedItemized = taxBreakdown.map(CombinedTaxResult::usedItemized).orElse(null);

        return ProjectionYearDto.builder()
                .year(in.year()).age(in.age()).startBalance(in.startBalance())
                .contributions(in.contributions()).growth(in.totalGrowth())
                .withdrawals(in.withdrawals()).endBalance(in.endBalance()).retired(in.retired())
                .traditionalBalance(in.endingTraditional()).rothBalance(in.endingRoth())
                .taxableBalance(in.endingTaxable())
                .rothConversionAmount(positiveOrNull(in.conversionAmount()))
                .taxLiability(positiveOrNull(in.taxLiability()))
                .taxableGrowth(in.growthResult().taxable())
                .traditionalGrowth(in.growthResult().traditional())
                .rothGrowth(in.growthResult().roth())
                .taxPaidFromTaxable(positiveOrNull(in.combinedTaxSource().fromTaxable()))
                .taxPaidFromTraditional(positiveOrNull(in.combinedTaxSource().fromTraditional()))
                .taxPaidFromRoth(positiveOrNull(in.combinedTaxSource().fromRoth()))
                .withdrawalFromTaxable(positiveOrNull(in.withdrawalFromTaxable()))
                .withdrawalFromTraditional(positiveOrNull(in.withdrawalFromTraditional()))
                .withdrawalFromRoth(positiveOrNull(in.withdrawalFromRoth()))
                .federalTax(fedTax).stateTax(stTax).saltDeduction(saltDed)
                .usedItemizedDeduction(usedItemized)
                .rmdAmount(positiveOrNull(in.rmdAmount()))
                .capitalGainsTax(positiveOrNull(in.ltcgTax()))
                .earlyWithdrawalPenalty(positiveOrNull(in.earlyWithdrawalPenalty()))
                .build();
    }

    /** Returns {@code value} when strictly positive, otherwise {@code null} (the DTO convention). */
    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : null;
    }
}
