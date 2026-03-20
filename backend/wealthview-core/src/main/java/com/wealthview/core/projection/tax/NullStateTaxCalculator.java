package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

public class NullStateTaxCalculator implements StateTaxCalculator {

    @Override
    public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        return BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getStandardDeduction(int taxYear, FilingStatus status) {
        return BigDecimal.ZERO;
    }

    @Override
    public String stateCode() {
        return "";
    }

    @Override
    public boolean taxesCapitalGainsAsOrdinaryIncome() {
        return false;
    }
}
