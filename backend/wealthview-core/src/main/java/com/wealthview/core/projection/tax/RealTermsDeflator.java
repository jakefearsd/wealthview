package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * The single real-terms deflator for statutorily FIXED-NOMINAL thresholds (SS provisional-income
 * thresholds, the NIIT threshold): {@code 1/(1+inflationRate)^yearsFromBase}. Replaces the copies
 * that previously lived in {@link SocialSecurityTaxCalculator} and {@link CapitalGainsTaxCalculator}
 * (behavior-identical) so every engine deflates on one clock.
 */
public final class RealTermsDeflator {

    private RealTermsDeflator() {
    }

    public static BigDecimal factor(int yearsFromBase, @Nullable BigDecimal inflationRate) {
        if (yearsFromBase <= 0 || inflationRate == null || inflationRate.signum() == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal growth = BigDecimal.ONE.add(inflationRate).pow(yearsFromBase);
        return BigDecimal.ONE.divide(growth, SCALE + 6, ROUNDING);
    }
}
