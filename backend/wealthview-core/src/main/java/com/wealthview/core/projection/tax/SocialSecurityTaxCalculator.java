package com.wealthview.core.projection.tax;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes the taxable portion of Social Security benefits using the IRS two-tier formula.
 * Provisional income = other income + 50% of SS benefits.
 * Single: $25k/$34k thresholds. MFJ: $32k/$44k thresholds.
 */
@Component
public class SocialSecurityTaxCalculator {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal EIGHTY_FIVE_PERCENT = new BigDecimal("0.85");

    public BigDecimal computeTaxableAmount(BigDecimal ssBenefit, BigDecimal otherIncome,
                                           String filingStatus) {
        if (ssBenefit.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        var provisionalIncome = otherIncome.add(ssBenefit.multiply(HALF));

        BigDecimal tier1Threshold;
        BigDecimal tier2Threshold;
        if ("married_filing_jointly".equals(filingStatus)) {
            tier1Threshold = new BigDecimal("32000");
            tier2Threshold = new BigDecimal("44000");
        } else {
            tier1Threshold = new BigDecimal("25000");
            tier2Threshold = new BigDecimal("34000");
        }

        if (provisionalIncome.compareTo(tier1Threshold) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal taxable;
        if (provisionalIncome.compareTo(tier2Threshold) <= 0) {
            // Only tier 1 applies: 50% of excess over first threshold
            var excess = provisionalIncome.subtract(tier1Threshold);
            taxable = excess.multiply(HALF).setScale(SCALE, ROUNDING);
            // Cap at 50% of benefits
            var cap = ssBenefit.multiply(HALF).setScale(SCALE, ROUNDING);
            taxable = taxable.min(cap);
        } else {
            // Both tiers apply
            var tier1Amount = tier2Threshold.subtract(tier1Threshold)
                    .multiply(HALF).setScale(SCALE, ROUNDING);
            var tier2Amount = provisionalIncome.subtract(tier2Threshold)
                    .multiply(EIGHTY_FIVE_PERCENT).setScale(SCALE, ROUNDING);
            taxable = tier1Amount.add(tier2Amount);
            // Cap at 85% of benefits
            var cap = ssBenefit.multiply(EIGHTY_FIVE_PERCENT).setScale(SCALE, ROUNDING);
            taxable = taxable.min(cap);
        }

        return taxable;
    }
}
