package com.wealthview.projection;

import com.wealthview.core.projection.tax.FilingStatus;

/**
 * Pre-computed per-year tax and income data, derived from deterministic income projections.
 *
 * <p>{@code ordinaryTaxTableByYear} (audit C5) is the per-year exact ordinary tax bracket table
 * -- the SAME array reference threaded into {@code taxCtx} when pools exist, and also consumed
 * directly by {@code GuardrailResponseBuilder}'s terminal simulation, so both the optimizer's
 * search and its final reported response price withdrawal tax identically. Paired with
 * {@code rentalAwareTaxableIncome} as the "base" income each year's draws stack on.
 */
record TaxIncomeContext(
        FilingStatus filingStatus, double essentialFloor,
        double[] incomeByYear, double[] taxableIncomeByYear, double[] surplusTaxByYear,
        IncomeYearData[] incomeData, double[] rentalAwareTaxableIncome,
        double[] adjustedFloors, OrdinaryTaxTable[] ordinaryTaxTableByYear, TaxContext taxCtx,
        double[] dsBracketCeilingByYear,
        LtcgTaxTable[] ltcgTaxTableByYear
) {}
