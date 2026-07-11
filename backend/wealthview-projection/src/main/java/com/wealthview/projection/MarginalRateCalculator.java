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
        return compute(taxCalculator, taxableIncomeByYear, retirementYear, years, filingStatus, null);
    }

    /**
     * Like {@link #compute(FederalTaxCalculator, double[], int, int, FilingStatus)} but, when
     * {@code birthYear} is known, applies the IRS age-65+ additional standard deduction (audit D)
     * for every year the primary filer is 65+ -- keeping the MC's marginal-rate precompute
     * consistent with the deterministic engine's per-year federal tax, which threads the same
     * birth year through {@link com.wealthview.core.projection.tax.CombinedTaxCalculator} /
     * {@link com.wealthview.core.projection.tax.FederalOnlyTaxStrategy}. {@code null} reproduces
     * the age-unaware behavior above exactly.
     */
    static double[] compute(@Nullable FederalTaxCalculator taxCalculator,
                            double[] taxableIncomeByYear, int retirementYear, int years,
                            FilingStatus filingStatus, @Nullable Integer birthYear) {
        double[] rates = new double[years];
        if (taxCalculator == null) {
            return rates;
        }
        for (int y = 0; y < years; y++) {
            int taxYear = retirementYear + y;
            double baseIncome = taxableIncomeByYear[y];
            double baseTax = baseIncome > 0
                    ? computeTax(taxCalculator, baseIncome, taxYear, filingStatus, birthYear)
                    : 0;
            double totalTax = computeTax(taxCalculator, baseIncome + PROBE_AMOUNT, taxYear, filingStatus, birthYear);
            rates[y] = (totalTax - baseTax) / PROBE_AMOUNT;
        }
        return rates;
    }

    private static double computeTax(FederalTaxCalculator taxCalculator, double income, int taxYear,
                                      FilingStatus filingStatus, @Nullable Integer birthYear) {
        BigDecimal grossIncome = BigDecimal.valueOf(income);
        return (birthYear != null
                ? taxCalculator.computeTax(grossIncome, taxYear, filingStatus, taxYear - birthYear)
                : taxCalculator.computeTax(grossIncome, taxYear, filingStatus)).doubleValue();
    }
}
