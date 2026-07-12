package com.wealthview.projection;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

/**
 * Annotates a retired year's DTO with the detailed federal/state tax breakdown and the year's
 * IRMAA surcharge/warning. Extracted from {@link DeterministicProjectionEngine} to isolate the
 * retirement tax-annotation logic.
 */
final class RetirementTaxAnnotator {

    /**
     * Bundles the per-year inputs needed by {@code annotate}. {@code ltcgTax} is the year's
     * long-term capital-gains tax (zero when none was realized) -- LTCG is a federal tax, so it is
     * folded into the recomputed federal-tax breakdown below rather than left as an unattributed
     * gap between {@code federalTax + stateTax} and {@code taxLiability}. {@code realizedLtcgIncome}
     * and {@code federallyTaxedSocialSecurity} feed the STATE side of that same recompute (audit C3):
     * realized LTCG/dividend income is added to the state base for states that tax capital gains as
     * ordinary income, and the federally-taxed Social Security amount (already folded into {@code
     * effectiveOtherIncome} for federal purposes) is subtracted from the state base for states that
     * fully exempt Social Security. {@code irmaaSurcharge} is the year's ALREADY-COMPUTED Medicare
     * IRMAA premium surcharge (Wave-4 IRMAA item; zero when not applicable) -- this class does not
     * compute it, only surfaces it onto the DTO and derives {@code irmaaWarning} from it.
     *
     * <p>{@code selfEmploymentTax} and {@code earlyWithdrawalPenalty} (T18a-5b) are two further
     * federal-level additive components ALREADY folded into {@code taxLiability} that the ordinary
     * {@code computeDetailedTax} recompute below has no way to reproduce (neither is bracket-based
     * ordinary income tax) -- both must be added into the federal component here too, or
     * {@code federalTax + stateTax} silently falls short of {@code taxLiability} in any year either
     * one is present. Zero when not applicable.
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
            BigDecimal federallyTaxedSocialSecurity,
            BigDecimal irmaaSurcharge,
            BigDecimal selfEmploymentTax,
            BigDecimal earlyWithdrawalPenalty) {
    }

    /**
     * Applies the detailed federal/state tax breakdown and the IRMAA surcharge/warning to the year
     * DTO. The tax breakdown is only meaningful for retired years with a non-null tax strategy and
     * positive tax liability; the IRMAA fields are set whenever the caller passed a positive
     * surcharge (which is already gated on retired/Medicare-age by the caller -- see
     * {@code DeterministicProjectionEngine#processYear}).
     */
    ProjectionYearDto annotate(ProjectionYearDto yearDto, AnnotationContext annCtx) {
        boolean retired = annCtx.retired();
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
            // LTCG, self-employment tax, and the early-withdrawal penalty (T18a-5b) are all federal
            // obligations already folded into taxLiability that this ordinary-bracket recompute has
            // no way to reproduce; fold all three into the federal component so federalTax +
            // stateTax reconciles with taxLiability exactly instead of leaving a gap.
            BigDecimal fedTax = breakdown.federalTax().add(ltcgTax)
                    .add(annCtx.selfEmploymentTax()).add(annCtx.earlyWithdrawalPenalty());
            BigDecimal stTax = breakdown.stateTax().compareTo(BigDecimal.ZERO) > 0
                    ? breakdown.stateTax() : null;
            BigDecimal saltDed = breakdown.saltDeduction().compareTo(BigDecimal.ZERO) > 0
                    ? breakdown.saltDeduction() : null;
            yearDto = yearDto.withTaxBreakdown(fedTax, stTax, saltDed, breakdown.usedItemized());
        }
        yearDto = yearDto.withIrmaaSurcharge(annCtx.irmaaSurcharge());
        return yearDto;
    }
}
