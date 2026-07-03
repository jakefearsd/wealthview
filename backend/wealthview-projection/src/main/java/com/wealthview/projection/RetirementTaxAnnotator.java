package com.wealthview.projection;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

/**
 * Annotates a retired year's DTO with the detailed federal/state tax breakdown and an
 * IRMAA warning. Extracted from {@link DeterministicProjectionEngine} to isolate the
 * retirement tax-annotation logic.
 */
final class RetirementTaxAnnotator {

    private static final BigDecimal IRMAA_BRACKET_RATE = new BigDecimal("0.22");

    /** Bundles the per-year inputs needed by {@code annotate}. */
    record AnnotationContext(
            boolean retired,
            int age,
            int year,
            BigDecimal wdFromTraditional,
            BigDecimal conversionAmount,
            BigDecimal effectiveOtherIncome,
            BigDecimal taxLiability,
            PoolStrategy pool,
            @Nullable TaxCalculationStrategy taxStrategy) {
    }

    /**
     * Applies the detailed federal/state tax breakdown and IRMAA warning to the year DTO.
     * Only meaningful for retired years with a non-null tax strategy.
     */
    ProjectionYearDto annotate(ProjectionYearDto yearDto, AnnotationContext annCtx) {
        boolean retired = annCtx.retired();
        int age = annCtx.age();
        int year = annCtx.year();
        var wdFromTraditional = annCtx.wdFromTraditional();
        var conversionAmount = annCtx.conversionAmount();
        var effectiveOtherIncome = annCtx.effectiveOtherIncome();
        var taxLiability = annCtx.taxLiability();
        var pool = annCtx.pool();
        var taxStrategy = annCtx.taxStrategy();

        if (taxStrategy != null && taxLiability.compareTo(BigDecimal.ZERO) > 0 && retired) {
            BigDecimal totalTaxableIncome = wdFromTraditional.add(conversionAmount)
                    .add(effectiveOtherIncome);
            var filingStatus = pool.getFilingStatus();
            var breakdown = taxStrategy.computeDetailedTax(totalTaxableIncome, year, filingStatus);
            BigDecimal fedTax = breakdown.federalTax();
            BigDecimal stTax = breakdown.stateTax().compareTo(BigDecimal.ZERO) > 0
                    ? breakdown.stateTax() : null;
            BigDecimal saltDed = breakdown.saltDeduction().compareTo(BigDecimal.ZERO) > 0
                    ? breakdown.saltDeduction() : null;
            yearDto = yearDto.withTaxBreakdown(fedTax, stTax, saltDed, breakdown.usedItemized());
        }
        if (retired && age >= 63 && taxStrategy != null) {
            BigDecimal totalIncome = effectiveOtherIncome.add(conversionAmount).add(wdFromTraditional);
            var filingStatus = pool.getFilingStatus();
            BigDecimal irmaaCeiling = taxStrategy.computeMaxIncomeForTargetRate(
                    IRMAA_BRACKET_RATE, year, filingStatus);
            if (irmaaCeiling.compareTo(BigDecimal.ZERO) > 0
                    && totalIncome.compareTo(irmaaCeiling) > 0) {
                yearDto = yearDto.withIrmaaWarning(true);
            }
        }
        return yearDto;
    }
}
