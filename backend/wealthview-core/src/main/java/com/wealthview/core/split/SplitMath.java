package com.wealthview.core.split;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact split-ratio arithmetic. Multiplies then divides in a single step so
 * reverse and odd ratios (1:3, 1:6, 1:7) do not accumulate the rounding error
 * a pre-divided ratio bakes in.
 */
public final class SplitMath {

    private static final int QUANTITY_SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private SplitMath() {
    }

    /** New share quantity after a {@code numerator:denominator} split. */
    public static BigDecimal adjustShares(BigDecimal quantity, int numerator, int denominator) {
        return quantity.multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator), QUANTITY_SCALE, ROUNDING);
    }

    /** New per-share close price after a {@code numerator:denominator} split. */
    public static BigDecimal adjustPrice(BigDecimal closePrice, int numerator, int denominator) {
        return closePrice.multiply(BigDecimal.valueOf(denominator))
                .divide(BigDecimal.valueOf(numerator), QUANTITY_SCALE, ROUNDING);
    }
}
