package com.wealthview.projection;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
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
 */
final class LtcgRateCalculator {

    /** A small probe gain; the marginal LTCG rate is the tax on it divided by its size. */
    private static final double PROBE_GAIN = 1_000;

    private LtcgRateCalculator() {
    }

    static double[] compute(@Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
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
            BigDecimal ordinaryBd = BigDecimal.valueOf(ordinary);
            // MAGI ≈ ordinary + the probe gain, for the NIIT threshold comparison.
            BigDecimal magi = BigDecimal.valueOf(ordinary + PROBE_GAIN);
            double tax = capitalGainsTaxCalculator.computeLtcgTax(
                    ordinaryBd, probe, taxYear, filingStatus, y, inflation, magi).doubleValue();
            rates[y] = tax / PROBE_GAIN;
        }
        return rates;
    }
}
