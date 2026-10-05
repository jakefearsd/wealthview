package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.TaxSpaceYear;
import com.wealthview.core.projection.dto.YearTaxPicture;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;
import com.wealthview.core.projection.tax.TaxSpaceCalculator;

/**
 * Phase 1a (spec §1.1): captures a retired year's realized income/tax figures as a
 * {@link YearTaxPicture} for the tax-space calculator and its reconciliation guard. Reuses the values
 * the engine already resolved for the year, so nothing is recomputed.
 *
 * <p>{@code chargedOrdinaryAndStateTax} is the ordinary + state slice of taxLiability -- the same
 * split {@code RetirementTaxAnnotator} reconciles (LTCG, SE tax and the early-withdrawal penalty are
 * the three additive federal components outside it). Ages follow the tax strategies' convention: the
 * filer's age (primary while alive, else the survivor's) plus the spouse's only while both are alive
 * AND filing jointly.
 */
final class YearTaxPictureBuilder {

    /** IRMAA statutory MAGI lookback: year Y's MAGI sets premiums in Y+2 (spec §1.2 IRMAA group). */
    private static final int IRMAA_LOOKBACK_YEARS = 2;

    private YearTaxPictureBuilder() {
    }

    /** The year's per-person context needed to resolve ages and the IRMAA premium-year Medicare count. */
    record PictureContext(@Nullable HouseholdContext household, FilingStatus filingStatus, int year, int age,
                       int birthYear, int baseYear, BigDecimal inflationRate) {
    }

    /** Empty for a pre-retirement year; otherwise the year's picture. */
    static Optional<YearTaxPicture> buildIfRetired(boolean retired, PictureContext yc,
                                                   YearFinanceResolver.YearComputation comp,
                                                   BigDecimal magi, BigDecimal selfEmploymentTax) {
        return retired ? Optional.of(build(yc, comp, magi, selfEmploymentTax)) : Optional.empty();
    }

    /**
     * One {@link TaxSpaceYear} per picture, priced with the run's own tax strategy; empty when no
     * calculator or strategy is wired (pictures are still emitted by the engine).
     */
    static List<TaxSpaceYear> computeTaxSpace(@Nullable TaxSpaceCalculator calculator,
                                              List<YearTaxPicture> pictures,
                                              @Nullable TaxCalculationStrategy taxStrategy) {
        if (calculator == null || taxStrategy == null) {
            return List.of();
        }
        return pictures.stream().map(p -> calculator.compute(p, taxStrategy)).toList();
    }

    private static YearTaxPicture build(PictureContext yc, YearFinanceResolver.YearComputation comp,
                                        BigDecimal magi, BigDecimal selfEmploymentTax) {
        var household = yc.household();
        int year = yc.year();
        int filerAge = household != null ? household.filerAgeIn(year) : yc.age();
        Integer spouseAge = household != null && yc.filingStatus() == FilingStatus.MARRIED_FILING_JOINTLY
                ? household.secondFilerAgeIn(year) : null;
        int premiumYear = year + IRMAA_LOOKBACK_YEARS;
        int medicareCountInPremiumYear = household != null
                ? household.age65QualifyingCount(premiumYear)
                : premiumYear - yc.birthYear() >= DeterministicProjectionEngine.MEDICARE_AGE ? 1 : 0;
        var isResult = comp.isResult();
        BigDecimal ssBenefit = isResult != null ? isResult.socialSecurityBenefit() : BigDecimal.ZERO;
        BigDecimal netRental = isResult != null ? isResult.netRentalTaxableIncome() : BigDecimal.ZERO;
        BigDecimal ssTaxable = comp.socialSecurityTaxable();
        BigDecimal chargedOrdinaryAndState = comp.taxLiability().subtract(comp.ltcgTax())
                .subtract(selfEmploymentTax).subtract(comp.earlyWithdrawalPenalty());
        return new YearTaxPicture(year, yc.filingStatus(), filerAge, spouseAge,
                comp.effectiveOtherIncome().subtract(ssTaxable), ssBenefit, ssTaxable,
                comp.wdFromTraditional(), comp.conversionAmount(), comp.ordinaryInterestIncome(),
                comp.realizedLtcgIncome(), magi, netRental, chargedOrdinaryAndState, comp.ltcgTax(),
                Math.max(0, year - yc.baseYear()), yc.inflationRate(), medicareCountInPremiumYear);
    }
}
