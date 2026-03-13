package com.wealthview.core.projection.tax;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes self-employment tax: 15.3% on 92.35% of net earnings.
 * Social Security portion (12.4%) is capped at the wage base.
 * Medicare portion (2.9%) is uncapped.
 */
@Component
public class SelfEmploymentTaxCalculator {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private static final BigDecimal NET_EARNINGS_FACTOR = new BigDecimal("0.9235");
    private static final BigDecimal SS_RATE = new BigDecimal("0.124");
    private static final BigDecimal MEDICARE_RATE = new BigDecimal("0.029");

    // Wage base by year (add new years as needed)
    private static final BigDecimal WAGE_BASE_2025 = new BigDecimal("176100");

    public BigDecimal computeSETax(BigDecimal netEarnings, int taxYear) {
        if (netEarnings.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        var taxableBase = netEarnings.multiply(NET_EARNINGS_FACTOR).setScale(SCALE, ROUNDING);
        var wageBase = getWageBase(taxYear);

        // Social Security portion: capped at wage base
        var ssTaxable = taxableBase.min(wageBase);
        var ssTax = ssTaxable.multiply(SS_RATE).setScale(SCALE, ROUNDING);

        // Medicare portion: uncapped
        var medicareTax = taxableBase.multiply(MEDICARE_RATE).setScale(SCALE, ROUNDING);

        return ssTax.add(medicareTax);
    }

    /**
     * 50% of SE tax is deductible from adjusted gross income.
     */
    public BigDecimal deductibleAmount(BigDecimal seTax) {
        return seTax.multiply(new BigDecimal("0.5")).setScale(SCALE, ROUNDING);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private BigDecimal getWageBase(int taxYear) {
        // Use 2025 wage base as default; could be extended with a lookup table
        return WAGE_BASE_2025;
    }
}
