package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.household.HouseholdContext;

public class FederalOnlyTaxStrategy implements TaxCalculationStrategy {

    private final FederalTaxCalculator federalTaxCalculator;
    /**
     * The primary filer's birth year, for the age-65+ additional standard deduction (audit D).
     * {@code null} when the caller doesn't track it -- falls back to the age-less
     * {@link FederalTaxCalculator} overload, identical to pre-age65-feature behavior. Superseded by
     * {@link #household} when that field is set (household task 7); see its Javadoc.
     */
    @Nullable
    private final Integer birthYear;

    /**
     * Household task 7 (spec §4 step 6): the household context, when known -- see
     * {@code CombinedTaxCalculator#household}'s Javadoc for the identical convention (primary's age
     * while alive or the survivor's thereafter, plus a second qualifying age for the spouse only
     * while both are alive and filing jointly). {@code null} for every pre-household call site.
     */
    @Nullable
    private final HouseholdContext household;

    public FederalOnlyTaxStrategy(FederalTaxCalculator federalTaxCalculator) {
        this(federalTaxCalculator, null, null);
    }

    public FederalOnlyTaxStrategy(FederalTaxCalculator federalTaxCalculator, @Nullable Integer birthYear) {
        this(federalTaxCalculator, birthYear, null);
    }

    /**
     * Household task 7: threads the household context for the per-person age-65 deduction. {@code
     * household} {@code null} reproduces the {@code birthYear}-only 2-arg constructor exactly.
     */
    public FederalOnlyTaxStrategy(FederalTaxCalculator federalTaxCalculator, @Nullable Integer birthYear,
                                   @Nullable HouseholdContext household) {
        this.federalTaxCalculator = federalTaxCalculator;
        this.birthYear = birthYear;
        this.household = household;
    }

    @Override
    public BigDecimal computeTotalTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        if (household != null) {
            Integer secondAge = status == FilingStatus.MARRIED_FILING_JOINTLY
                    ? household.secondFilerAgeIn(taxYear) : null;
            return federalTaxCalculator.computeTax(
                    grossIncome, taxYear, status, household.filerAgeIn(taxYear), secondAge);
        }
        return birthYear != null
                ? federalTaxCalculator.computeTax(grossIncome, taxYear, status, taxYear - birthYear)
                : federalTaxCalculator.computeTax(grossIncome, taxYear, status);
    }

    @Override
    public BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int taxYear, FilingStatus status) {
        return federalTaxCalculator.computeMaxIncomeForBracket(targetRate, taxYear, status);
    }
}
