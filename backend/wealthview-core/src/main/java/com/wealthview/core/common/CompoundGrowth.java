package com.wealthview.core.common;

import java.math.BigDecimal;

public final class CompoundGrowth {

    private CompoundGrowth() {
    }

    public static BigDecimal factor(BigDecimal rate, int years) {
        return BigDecimal.ONE.add(rate).pow(years);
    }

    public static double factor(double rate, int years) {
        return Math.pow(1.0 + rate, years);
    }

    public static BigDecimal inflate(BigDecimal principal, BigDecimal rate, int years) {
        return principal.multiply(factor(rate, years));
    }

    public static double inflate(double principal, double rate, int years) {
        return principal * factor(rate, years);
    }
}
