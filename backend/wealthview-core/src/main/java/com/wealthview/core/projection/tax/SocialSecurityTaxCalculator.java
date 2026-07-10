package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * Computes the taxable portion of Social Security benefits using the IRS two-tier formula.
 * Provisional income = other income + 50% of SS benefits.
 * Single: $25k/$34k thresholds. MFJ: $32k/$44k thresholds.
 *
 * <p>The projection runs in real (today's-dollars) terms. The four thresholds are statutorily
 * FIXED NOMINAL (unindexed since the 1980s/90s), so in real terms they erode over time: each is
 * deflated by {@code 1/(1+inflation)^yearsFromBase}, making progressively more of the benefit
 * taxable in later projection years for the same real income.
 */
@Component
public class SocialSecurityTaxCalculator {

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal EIGHTY_FIVE_PERCENT = new BigDecimal("0.85");

    /**
     * Legacy overload for non-projection callers: no threshold deflation
     * ({@code yearsFromBase = 0}, {@code inflationRate = 0}).
     */
    public BigDecimal computeTaxableAmount(BigDecimal ssBenefit, BigDecimal otherIncome,
                                           String filingStatus) {
        return computeTaxableAmount(ssBenefit, otherIncome, filingStatus, 0, BigDecimal.ZERO);
    }

    public BigDecimal computeTaxableAmount(BigDecimal ssBenefit, BigDecimal otherIncome,
                                           String filingStatus, int yearsFromBase,
                                           BigDecimal inflationRate) {
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

        // Real-terms deflation of the fixed-nominal thresholds.
        BigDecimal deflator = thresholdDeflator(yearsFromBase, inflationRate);
        if (deflator.compareTo(BigDecimal.ONE) != 0) {
            tier1Threshold = tier1Threshold.multiply(deflator).setScale(SCALE, ROUNDING);
            tier2Threshold = tier2Threshold.multiply(deflator).setScale(SCALE, ROUNDING);
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
            // Both tiers apply. Tier-1 component is the LESSER of 50% of benefits and 50% of the
            // tier gap — for a small benefit the half-benefit bound binds, so the min is required.
            var halfBenefits = ssBenefit.multiply(HALF).setScale(SCALE, ROUNDING);
            var halfTierGap = tier2Threshold.subtract(tier1Threshold)
                    .multiply(HALF).setScale(SCALE, ROUNDING);
            var tier1Amount = halfBenefits.min(halfTierGap);
            var tier2Amount = provisionalIncome.subtract(tier2Threshold)
                    .multiply(EIGHTY_FIVE_PERCENT).setScale(SCALE, ROUNDING);
            taxable = tier1Amount.add(tier2Amount);
            // Cap at 85% of benefits
            var cap = ssBenefit.multiply(EIGHTY_FIVE_PERCENT).setScale(SCALE, ROUNDING);
            taxable = taxable.min(cap);
        }

        return taxable;
    }

    private static BigDecimal thresholdDeflator(int yearsFromBase, BigDecimal inflationRate) {
        if (yearsFromBase <= 0 || inflationRate == null || inflationRate.signum() == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal growth = BigDecimal.ONE.add(inflationRate).pow(yearsFromBase);
        return BigDecimal.ONE.divide(growth, SCALE + 6, ROUNDING);
    }
}
