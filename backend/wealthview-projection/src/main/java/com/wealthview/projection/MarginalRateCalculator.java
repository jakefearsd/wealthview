package com.wealthview.projection;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;

/**
 * Computes per-year marginal tax rates by probing the federal tax calculator with a fixed
 * income increment. Shared between the optimizer's context preparation and the joint conversion
 * search; returns all-zero rates when no tax calculator is configured.
 */
final class MarginalRateCalculator {

    private static final double PROBE_AMOUNT = 50_000;

    private MarginalRateCalculator() {
    }

    static double[] compute(@Nullable FederalTaxCalculator taxCalculator,
                            double[] taxableIncomeByYear, int retirementYear, int years,
                            FilingStatus filingStatus) {
        double[] rates = new double[years];
        if (taxCalculator == null) {
            return rates;
        }
        for (int y = 0; y < years; y++) {
            int taxYear = retirementYear + y;
            double baseIncome = taxableIncomeByYear[y];
            double baseTax = baseIncome > 0
                    ? taxCalculator.computeTax(BigDecimal.valueOf(baseIncome), taxYear, filingStatus).doubleValue()
                    : 0;
            double totalTax = taxCalculator.computeTax(
                    BigDecimal.valueOf(baseIncome + PROBE_AMOUNT), taxYear, filingStatus).doubleValue();
            rates[y] = (totalTax - baseTax) / PROBE_AMOUNT;
        }
        return rates;
    }
}
