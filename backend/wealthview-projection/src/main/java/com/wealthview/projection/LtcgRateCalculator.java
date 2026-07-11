package com.wealthview.projection;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;

/**
 * Precomputes the per-year marginal long-term capital-gains rate (0/15/20% + any NIIT) by probing
 * {@link CapitalGainsTaxCalculator} with a small gain stacked on each year's expected ordinary
 * taxable income. Mirrors {@link MarginalRateCalculator}: returns all-zero rates when no
 * capital-gains calculator is configured, so the Monte Carlo taxable pool realizes no LTCG tax.
 *
 * <p>Real-terms projection: the LTCG brackets are constant real (IRS-indexed) and the NIIT threshold
 * is fixed-nominal, deflated by {@code (1+inflationRate)^yearsFromBase} inside the calculator — the
 * same treatment the deterministic {@code PoolStrategy.MultiPool} path uses.
 *
 * <p>The STACKING FLOOR passed to {@link CapitalGainsTaxCalculator#computeLtcgTax} is netted by the
 * year's standard deduction (via {@link FederalTaxCalculator#loadStandardDeduction}) before the probe
 * gain is stacked on it, mirroring {@code PoolStrategy.MultiPool}'s {@code computeLtcgTax} /
 * {@code resolveOrdinaryDeduction} fix -- {@code ordinaryIncomeByYear} here is gross (pre-deduction)
 * taxable income, and stacking the probe on the gross figure overstates the marginal LTCG rate by one
 * full standard deduction at bracket boundaries. MAGI stays GROSS (MAGI is not deduction-reduced).
 */
final class LtcgRateCalculator {

    /** A small probe gain; the marginal LTCG rate is the tax on it divided by its size. */
    private static final double PROBE_GAIN = 1_000;

    private LtcgRateCalculator() {
    }

    static double[] compute(@Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                            @Nullable FederalTaxCalculator federalTaxCalculator,
                            double[] ordinaryIncomeByYear, int retirementYear, int years,
                            FilingStatus filingStatus, double inflationRate) {
        double[] rates = new double[years];
        if (capitalGainsTaxCalculator == null) {
            return rates;
        }
        BigDecimal probe = BigDecimal.valueOf(PROBE_GAIN);
        BigDecimal inflation = BigDecimal.valueOf(inflationRate);
        for (int y = 0; y < years; y++) {
            int taxYear = retirementYear + y;
            double ordinary = Math.max(0, ordinaryIncomeByYear[y]);
            // MAGI ≈ ordinary + the probe gain, for the NIIT threshold comparison (NOT deduction-netted).
            BigDecimal magi = BigDecimal.valueOf(ordinary + PROBE_GAIN);
            double deduction = federalTaxCalculator != null
                    ? federalTaxCalculator.loadStandardDeduction(taxYear, filingStatus).doubleValue() : 0.0;
            BigDecimal ordinaryForLtcg = BigDecimal.valueOf(Math.max(0, ordinary - deduction));
            double tax = capitalGainsTaxCalculator.computeLtcgTax(
                    ordinaryForLtcg, probe, taxYear, filingStatus, y, inflation, magi).doubleValue();
            rates[y] = tax / PROBE_GAIN;
        }
        return rates;
    }
}
