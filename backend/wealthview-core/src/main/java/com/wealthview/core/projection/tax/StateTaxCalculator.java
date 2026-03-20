package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

public interface StateTaxCalculator {

    BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status);

    BigDecimal getStandardDeduction(int taxYear, FilingStatus status);

    String stateCode();

    boolean taxesCapitalGainsAsOrdinaryIncome();
}
