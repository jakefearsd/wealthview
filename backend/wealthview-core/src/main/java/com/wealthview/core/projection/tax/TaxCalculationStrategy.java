package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.util.Optional;

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

    /**
     * Phase 1a: like {@link #computeMaxIncomeForTargetRate} but using the AGE-AWARE standard deduction
     * (and, where the implementation itemizes, the deduction that would apply at the ceiling). The
     * default delegates to the age-less method for implementations with no age concept.
     */
    default BigDecimal computeGrossCeilingForRate(BigDecimal targetRate, int taxYear, FilingStatus status) {
        return computeMaxIncomeForTargetRate(targetRate, taxYear, status);
    }

    /**
     * Phase 1a: the AGE-AWARE federal standard deduction this strategy applies for (taxYear, status) --
     * the same amount its ordinary-tax computation subtracts when it does not itemize. Empty for
     * implementations with no deduction/age concept; callers then fall back to their own lookup.
     */
    default Optional<BigDecimal> standardDeduction(int taxYear, FilingStatus status) {
        return Optional.empty();
    }
}
