package com.wealthview.projection;

import com.wealthview.core.projection.tax.FilingStatus;

/** Pre-computed per-year tax and income data, derived from deterministic income projections. */
record TaxIncomeContext(
        FilingStatus filingStatus, double essentialFloor,
        double[] incomeByYear, double[] taxableIncomeByYear, double[] surplusTaxByYear,
        IncomeYearData[] incomeData, double[] rentalAwareTaxableIncome,
        double[] adjustedFloors, double[] marginalRates, TaxContext taxCtx,
        double[] dsBracketCeilingByYear
) {}
