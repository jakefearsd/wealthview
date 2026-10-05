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
    private static final BigDecimal SINGLE_BASE_THRESHOLD = new BigDecimal("25000");
    private static final BigDecimal SINGLE_UPPER_THRESHOLD = new BigDecimal("34000");
    private static final BigDecimal MFJ_BASE_THRESHOLD = new BigDecimal("32000");
    private static final BigDecimal MFJ_UPPER_THRESHOLD = new BigDecimal("44000");

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

        var thresholds = thresholds(FilingStatus.fromString(filingStatus), yearsFromBase, inflationRate);
        BigDecimal tier1Threshold = thresholds.base();
        BigDecimal tier2Threshold = thresholds.upper();

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

    /**
     * The year's provisional-income thresholds, deflated onto the real-terms clock exactly as
     * {@link #computeTaxableAmount(BigDecimal, BigDecimal, String, int, BigDecimal)} applies them.
     */
    public SsThresholds thresholds(FilingStatus status, int yearsFromBase, BigDecimal inflationRate) {
        boolean mfj = status == FilingStatus.MARRIED_FILING_JOINTLY;
        BigDecimal base = mfj ? MFJ_BASE_THRESHOLD : SINGLE_BASE_THRESHOLD;
        BigDecimal upper = mfj ? MFJ_UPPER_THRESHOLD : SINGLE_UPPER_THRESHOLD;
        BigDecimal deflator = RealTermsDeflator.factor(yearsFromBase, inflationRate);
        if (deflator.compareTo(BigDecimal.ONE) != 0) {
            base = base.multiply(deflator).setScale(SCALE, ROUNDING);
            upper = upper.multiply(deflator).setScale(SCALE, ROUNDING);
        }
        return new SsThresholds(base, upper);
    }
}
