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
        // For combined rate: find income where federal_marginal + state_marginal first exceeds target.
        // Simple approach: if state calculator is null/zero, delegate to federal.
        // Otherwise, find the income level where combined marginal rate exceeds target.

        if (state instanceof NullStateTaxCalculator) {
            return federal.computeMaxIncomeForBracket(targetRate, taxYear, status);
        }

        // Walk through federal brackets and find where combined marginal rate exceeds target.
        // The deduction used affects the gross income ceiling, so we need to account for it.
        // We use a binary-search approach: find the largest gross income where the combined
        // marginal rate (at that point) is <= targetRate.

        // Start with federal behavior and adjust for state marginal rate.
        // At any given income level, the state marginal rate is determined by which state bracket
        // the income falls in. We approximate by computing the state marginal rate at a reference
        // income, then finding the federal bracket whose rate alone brings the combined rate
        // above target.

        // Simplified approach: iterate incomes from the federal bracket ceilings and check
        // combined marginal rate at each boundary.
        BigDecimal federalStandardDeduction = federal.loadStandardDeduction(taxYear, status);

        // Determine itemized deduction at a mid-range estimate
        BigDecimal estStateTax = state.computeTax(bd("200000"), taxYear, status);
        BigDecimal estSalt = estStateTax.add(primaryResidencePropertyTax).min(SALT_CAP);
        BigDecimal estItemized = estSalt.add(primaryResidenceMortgageInterest);
        BigDecimal chosenDeduction = estItemized.compareTo(federalStandardDeduction) > 0
                ? estItemized : federalStandardDeduction;

        // Use a stepping approach: increase income in increments and check marginal rate
        // This is approximate but sufficient for Roth conversion bracket-filling
        BigDecimal step = bd("1000");
        BigDecimal testIncome = chosenDeduction; // start at deduction level (zero tax)
        BigDecimal maxIncome = bd("5000000");
        BigDecimal lastBelowTarget = testIncome;

        while (testIncome.compareTo(maxIncome) < 0) {
            BigDecimal taxAtIncome = computeTotalTax(testIncome, taxYear, status);
            BigDecimal taxAtIncomeMinusStep = computeTotalTax(testIncome.subtract(step), taxYear, status);
            BigDecimal marginalTax = taxAtIncome.subtract(taxAtIncomeMinusStep);
            BigDecimal marginalRate = marginalTax.divide(step, SCALE, ROUNDING);

            if (marginalRate.compareTo(targetRate) > 0) {
                // Found the boundary — refine with smaller steps
                if (step.compareTo(bd("1")) <= 0) {
                    return lastBelowTarget;
                }
                testIncome = lastBelowTarget;
                step = step.divide(bd("10"), 0, ROUNDING).max(bd("1"));
                continue;
            }

            lastBelowTarget = testIncome;
            testIncome = testIncome.add(step);
        }

        return lastBelowTarget;
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
