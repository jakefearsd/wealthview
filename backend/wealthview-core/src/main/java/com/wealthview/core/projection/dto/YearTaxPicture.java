package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.tax.FilingStatus;

/**
 * One retired year's realized tax inputs, captured by the deterministic engine once the year's taxes
 * are final (Phase 1a, spec §1.1) so {@code TaxSpaceCalculator} can re-price the year with probes.
 *
 * <p>{@code primaryAge} is the FILER's age ({@code HouseholdContext#filerAgeIn}: the primary while
 * alive, the survivor afterwards); {@code spouseAge} is {@code HouseholdContext#secondFilerAgeIn}
 * (non-null only while both spouses are alive). {@code ordinaryIncomeExSocialSecurity} is the
 * engine's effective other income minus the taxable Social Security portion.
 * {@code chargedOrdinaryAndStateTax} = taxLiability - ltcgTax - selfEmploymentTax - earlyWithdrawalPenalty.
 * {@code medicareCountInPremiumYear} is the number of alive household members aged 65+ in
 * {@code year + 2} (the IRMAA premium year this year's MAGI sets).
 */
public record YearTaxPicture(
        int year, FilingStatus filingStatus, int primaryAge, @Nullable Integer spouseAge,
        BigDecimal ordinaryIncomeExSocialSecurity,
        BigDecimal socialSecurityBenefit,
        BigDecimal socialSecurityTaxable,
        BigDecimal traditionalDistributions,
        BigDecimal rothConversion,
        BigDecimal ordinaryInterest,
        BigDecimal qualifiedDividendsAndLtcg,
        BigDecimal magi,
        BigDecimal netRentalIncome,
        BigDecimal chargedOrdinaryAndStateTax,
        BigDecimal chargedLtcgTax,
        int yearsFromBase, BigDecimal inflationRate,
        int medicareCountInPremiumYear) {
}
