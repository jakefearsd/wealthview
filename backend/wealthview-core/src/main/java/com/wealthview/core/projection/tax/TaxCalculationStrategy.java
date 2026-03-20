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
}
