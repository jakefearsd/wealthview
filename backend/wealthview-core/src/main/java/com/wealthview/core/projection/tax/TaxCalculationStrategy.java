package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

public interface TaxCalculationStrategy {

    BigDecimal computeTotalTax(BigDecimal grossIncome, int taxYear, FilingStatus status);

    BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int taxYear, FilingStatus status);

    default CombinedTaxResult computeDetailedTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        BigDecimal total = computeTotalTax(grossIncome, taxYear, status);
        return new CombinedTaxResult(total, BigDecimal.ZERO, total,
                BigDecimal.ZERO, BigDecimal.ZERO, false);
    }

    /**
     * Like {@link #computeDetailedTax(BigDecimal, int, FilingStatus)} but additionally supplies the
     * year's realized long-term-capital-gains + qualified-dividend income and the federally-taxed
     * Social Security amount, so a state-aware implementation can add or exempt them from its own
     * base per state (audit C3: {@code StateTaxCalculator#taxesCapitalGainsAsOrdinaryIncome} /
     * {@code #exemptsSocialSecurity}). The default ignores both figures and delegates to the 3-arg
     * overload, so implementations with no state-tax concept (e.g. {@code FederalOnlyTaxStrategy})
     * are unaffected.
     */
    default CombinedTaxResult computeDetailedTax(BigDecimal grossIncome, int taxYear, FilingStatus status,
                                                  BigDecimal ltcgIncome, BigDecimal federallyTaxedSocialSecurity) {
        return computeDetailedTax(grossIncome, taxYear, status);
    }
}
