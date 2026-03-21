package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class CombinedTaxCalculator implements TaxCalculationStrategy {

    private static final BigDecimal SALT_CAP = new BigDecimal("10000");
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final FederalTaxCalculator federal;
    private final StateTaxCalculator state;
    private final BigDecimal primaryResidencePropertyTax;
    private final BigDecimal primaryResidenceMortgageInterest;

    public CombinedTaxCalculator(FederalTaxCalculator federal,
                                  StateTaxCalculator state,
                                  BigDecimal primaryResidencePropertyTax,
                                  BigDecimal primaryResidenceMortgageInterest) {
        this.federal = federal;
        this.state = state;
        this.primaryResidencePropertyTax = primaryResidencePropertyTax != null
                ? primaryResidencePropertyTax : BigDecimal.ZERO;
        this.primaryResidenceMortgageInterest = primaryResidenceMortgageInterest != null
                ? primaryResidenceMortgageInterest : BigDecimal.ZERO;
    }

    public CombinedTaxResult computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        if (grossIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return new CombinedTaxResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, false);
        }

        // 1. Compute state tax on gross income (state applies its own deduction internally)
        BigDecimal stateTax = state.computeTax(grossIncome, taxYear, status);

        // 2. SALT = min(state_tax + property_tax, $10,000)
        BigDecimal saltUncapped = stateTax.add(primaryResidencePropertyTax);
        BigDecimal salt = saltUncapped.min(SALT_CAP);

        // 3. Itemized deductions = SALT + mortgage interest
        BigDecimal itemized = salt.add(primaryResidenceMortgageInterest);

        // 4. Compare itemized vs federal standard deduction
        BigDecimal federalStandardDeduction = federal.loadStandardDeduction(taxYear, status);
        boolean useItemized = itemized.compareTo(federalStandardDeduction) > 0;
        BigDecimal chosenDeduction = useItemized ? itemized : federalStandardDeduction;

        // 5. Compute federal tax using chosen deduction
        BigDecimal federalTax = federal.computeTaxWithDeduction(grossIncome, chosenDeduction, taxYear, status);

        BigDecimal totalTax = federalTax.add(stateTax);

        return new CombinedTaxResult(federalTax, stateTax, totalTax, salt, itemized, useItemized);
    }

    @Override
    public BigDecimal computeTotalTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        return computeTax(grossIncome, taxYear, status).totalTax();
    }

    @Override
    public CombinedTaxResult computeDetailedTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        return computeTax(grossIncome, taxYear, status);
    }

    @Override
    public BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int taxYear, FilingStatus status) {
        // The target rate represents a federal bracket rate (from the UI dropdown).
        // Find the federal bracket ceiling and add the appropriate deduction
        // (standard or itemized, whichever the filer would use at that income level).
        // State tax is a cost of the conversion, not a gating factor for how much to convert.

        if (state instanceof NullStateTaxCalculator) {
            return federal.computeMaxIncomeForBracket(targetRate, taxYear, status);
        }

        BigDecimal bracketCeiling = federal.findBracketCeiling(targetRate, taxYear, status);
        if (bracketCeiling.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        // Determine whether itemized or standard deduction applies at the bracket ceiling
        BigDecimal standardDeduction = federal.loadStandardDeduction(taxYear, status);
        BigDecimal grossEstimate = bracketCeiling.add(standardDeduction);

        BigDecimal estStateTax = state.computeTax(grossEstimate, taxYear, status);
        BigDecimal estSalt = estStateTax.add(primaryResidencePropertyTax).min(SALT_CAP);
        BigDecimal estItemized = estSalt.add(primaryResidenceMortgageInterest);
        BigDecimal chosenDeduction = estItemized.compareTo(standardDeduction) > 0
                ? estItemized : standardDeduction;

        return bracketCeiling.add(chosenDeduction);
    }
}
