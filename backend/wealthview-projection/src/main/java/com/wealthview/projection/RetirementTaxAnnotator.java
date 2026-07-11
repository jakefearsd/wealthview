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

    /**
     * Bundles the per-year inputs needed by {@code annotate}. {@code ltcgTax} is the year's
     * long-term capital-gains tax (zero when none was realized) -- LTCG is a federal tax, so it is
     * folded into the recomputed federal-tax breakdown below rather than left as an unattributed
     * gap between {@code federalTax + stateTax} and {@code taxLiability}. {@code realizedLtcgIncome}
     * and {@code federallyTaxedSocialSecurity} feed the STATE side of that same recompute (audit C3):
     * realized LTCG/dividend income is added to the state base for states that tax capital gains as
     * ordinary income, and the federally-taxed Social Security amount (already folded into {@code
     * effectiveOtherIncome} for federal purposes) is subtracted from the state base for states that
     * fully exempt Social Security.
     */
    record AnnotationContext(
            boolean retired,
            int age,
            int year,
            BigDecimal wdFromTraditional,
            BigDecimal conversionAmount,
            BigDecimal effectiveOtherIncome,
            BigDecimal taxLiability,
            PoolStrategy pool,
            @Nullable TaxCalculationStrategy taxStrategy,
            BigDecimal ltcgTax,
            BigDecimal realizedLtcgIncome,
            BigDecimal federallyTaxedSocialSecurity) {
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
        var ltcgTax = annCtx.ltcgTax();
        var realizedLtcgIncome = annCtx.realizedLtcgIncome();
        var federallyTaxedSocialSecurity = annCtx.federallyTaxedSocialSecurity();

        if (taxStrategy != null && taxLiability.compareTo(BigDecimal.ZERO) > 0 && retired) {
            BigDecimal totalTaxableIncome = wdFromTraditional.add(conversionAmount)
                    .add(effectiveOtherIncome);
            var filingStatus = pool.getFilingStatus();
            var breakdown = taxStrategy.computeDetailedTax(totalTaxableIncome, year, filingStatus,
                    realizedLtcgIncome, federallyTaxedSocialSecurity);
            // LTCG is a federal tax; fold it into the federal component so federalTax + stateTax
            // reconciles with taxLiability (which already includes it) instead of leaving a gap.
            BigDecimal fedTax = breakdown.federalTax().add(ltcgTax);
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
