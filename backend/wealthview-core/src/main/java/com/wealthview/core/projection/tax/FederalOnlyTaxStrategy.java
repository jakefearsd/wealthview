package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

public class FederalOnlyTaxStrategy implements TaxCalculationStrategy {

    private final FederalTaxCalculator federalTaxCalculator;
    /**
     * The primary filer's birth year, for the age-65+ additional standard deduction (audit D).
     * {@code null} when the caller doesn't track it -- falls back to the age-less
     * {@link FederalTaxCalculator} overload, identical to pre-age65-feature behavior.
     */
    @Nullable
    private final Integer birthYear;

    public FederalOnlyTaxStrategy(FederalTaxCalculator federalTaxCalculator) {
        this(federalTaxCalculator, null);
    }

    public FederalOnlyTaxStrategy(FederalTaxCalculator federalTaxCalculator, @Nullable Integer birthYear) {
        this.federalTaxCalculator = federalTaxCalculator;
        this.birthYear = birthYear;
    }

    @Override
    public BigDecimal computeTotalTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        return birthYear != null
                ? federalTaxCalculator.computeTax(grossIncome, taxYear, status, taxYear - birthYear)
                : federalTaxCalculator.computeTax(grossIncome, taxYear, status);
    }

    @Override
    public BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int taxYear, FilingStatus status) {
        return federalTaxCalculator.computeMaxIncomeForBracket(targetRate, taxYear, status);
    }
}
