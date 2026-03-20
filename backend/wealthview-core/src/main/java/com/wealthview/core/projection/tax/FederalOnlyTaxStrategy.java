package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

public class FederalOnlyTaxStrategy implements TaxCalculationStrategy {

    private final FederalTaxCalculator federalTaxCalculator;

    public FederalOnlyTaxStrategy(FederalTaxCalculator federalTaxCalculator) {
        this.federalTaxCalculator = federalTaxCalculator;
    }

    @Override
    public BigDecimal computeTotalTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        return federalTaxCalculator.computeTax(grossIncome, taxYear, status);
    }

    @Override
    public BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int taxYear, FilingStatus status) {
        return federalTaxCalculator.computeMaxIncomeForBracket(targetRate, taxYear, status);
    }
}
