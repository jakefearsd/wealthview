package com.wealthview.projection;

import java.math.BigDecimal;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentSizingReason;

/**
 * Pure sizing math for a bond tent: the present value of the planned retirement withdrawals that the tent
 * must cover, and a breakdown of how that differs from the rule of thumb (4% of the portfolio per coverage year).
 *
 * <p>Withdrawal {@code k} (0-based) is discounted by {@code (1 + r)^(k+1)}, and a partial final year counts at
 * its fraction. Everything here is in real terms and works in {@code double}.
 */
final class BondTentSizer {

    private static final double SAFE_WITHDRAWAL_RATE = 0.04;
    private static final BondTentSizingReason[] REASON_ORDER = BondTentSizingReason.values();

    private BondTentSizer() {
    }

    /**
     * The mix's real yield: {@code sum(share * ((1 + nominal) / (1 + inflation) - 1))}. A missing value counts as zero.
     */
    static double mixRealYield(BondMaturityValues yields, BondMaturityValues shares, double inflationRate) {
        if (yields == null) {
            throw new IllegalArgumentException("Bond tent yields are required to size a mix");
        }
        if (shares == null) {
            throw new IllegalArgumentException("Bond tent mix is required to size it");
        }
        if (shares.sum().signum() == 0) {
            throw new IllegalArgumentException("Bond tent mix has no shares: at least one maturity needs a share");
        }
        if (isInvalidRate(inflationRate)) {
            throw new IllegalArgumentException("Inflation rate must be above -100%, got " + inflationRate);
        }
        double total = 0.0;
        for (var maturity : BondMaturity.values()) {
            double share = toDouble(shares.get(maturity));
            double nominal = toDouble(yields.get(maturity));
            if (isInvalidRate(nominal)) {
                throw new IllegalArgumentException(
                        "Bond tent " + maturity + " yield must be above -100%, got " + nominal);
            }
            total += share * ((1.0 + nominal) / (1.0 + inflationRate) - 1.0);
        }
        return total;
    }

    /**
     * The present value at the start of the first retired year of the first {@code min(ceil(coverage), n)} withdrawals,
     * each weighted by its fraction of a year.
     */
    static double target(double[] withdrawals, double coverageYears, double realYield) {
        requireCoverageAndYield(coverageYears, realYield);
        int years = coveredYears(coverageYears, withdrawals.length);
        double total = 0.0;
        for (int k = 0; k < years; k++) {
            total += fraction(coverageYears, k) * withdrawals[k] / Math.pow(1.0 + realYield, k + 1);
        }
        return total;
    }

    /**
     * Sizes the tent from per-year spending, income and withdrawal tax, and explains the difference from the rule of
     * thumb. The four components are additive: spending level + income + withdrawal tax + mix yields = target minus
     * the rule of thumb. The one with the largest absolute value is reported, ties going to the earlier component.
     */
    static Sizing size(double[] spending, double[] income, double[] tax, double coverageYears, double realYield,
            double portfolioAtRetirement) {
        if (spending.length != income.length || spending.length != tax.length) {
            throw new IllegalArgumentException("Spending, income and tax series must have the same length, got "
                    + spending.length + ", " + income.length + " and " + tax.length);
        }
        requireCoverageAndYield(coverageYears, realYield);
        int years = coveredYears(coverageYears, spending.length);
        var fractions = new double[years];
        var withdrawals = new double[years];
        double spendingPv = 0.0;
        double incomePv = 0.0;
        double taxPv = 0.0;
        double grossPv = 0.0;
        double withdrawalPv = 0.0;
        for (int k = 0; k < years; k++) {
            double f = fraction(coverageYears, k);
            fractions[k] = f;
            withdrawals[k] = Math.max(0.0, spending[k] - income[k] + tax[k]);
            spendingPv += f * spending[k];
            incomePv += f * income[k];
            taxPv += f * tax[k];
            grossPv += f * (spending[k] - income[k] + tax[k]);
            withdrawalPv += f * withdrawals[k];
        }
        double target = target(withdrawals, coverageYears, realYield);
        double ruleOfThumb = coverageYears * SAFE_WITHDRAWAL_RATE * portfolioAtRetirement;
        double clampResidual = withdrawalPv - grossPv;
        var components = new double[REASON_ORDER.length];
        components[BondTentSizingReason.SPENDING_LEVEL.ordinal()] = spendingPv - ruleOfThumb;
        components[BondTentSizingReason.INCOME.ordinal()] = -incomePv + clampResidual;
        components[BondTentSizingReason.WITHDRAWAL_TAX.ordinal()] = taxPv;
        components[BondTentSizingReason.MIX_YIELDS.ordinal()] = target - withdrawalPv;
        var reason = largestComponent(components);
        return new Sizing(target, withdrawals, fractions, ruleOfThumb, reason,
                components[reason.ordinal()]);
    }

    private static void requireCoverageAndYield(double coverageYears, double realYield) {
        if (Double.isNaN(coverageYears) || coverageYears < 0.0) {
            throw new IllegalArgumentException("Bond tent coverage years must not be negative, got " + coverageYears);
        }
        if (isInvalidRate(realYield)) {
            throw new IllegalArgumentException("Bond tent real yield must be above -100%, got " + realYield);
        }
    }

    /** A rate at or below -100% (or NaN) makes {@code 1 + rate} zero, negative or undefined. */
    private static boolean isInvalidRate(double rate) {
        return Double.isNaN(rate) || rate <= -1.0;
    }

    private static BondTentSizingReason largestComponent(double... components) {
        var best = REASON_ORDER[0];
        for (var candidate : REASON_ORDER) {
            if (Math.abs(components[candidate.ordinal()]) > Math.abs(components[best.ordinal()])) {
                best = candidate;
            }
        }
        return best;
    }

    private static int coveredYears(double coverageYears, int available) {
        return (int) Math.min(Math.ceil(coverageYears), available);
    }

    private static double fraction(double coverageYears, int yearIndex) {
        return Math.min(1.0, coverageYears - yearIndex);
    }

    private static double toDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    /**
     * The sizing result. {@code withdrawals} and {@code fractions} cover the years counted toward the target.
     */
    record Sizing(
            double target,
            double[] withdrawals,
            double[] fractions,
            double ruleOfThumb,
            BondTentSizingReason reason,
            double reasonAmount) {

        @Override
        public double[] withdrawals() {
            return withdrawals.clone();
        }

        @Override
        public double[] fractions() {
            return fractions.clone();
        }
    }
}
