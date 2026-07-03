package com.wealthview.core.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.lang.Nullable;

public final class Money {

    public static final int SCALE = 4;

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Money() {
    }

    public static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    /**
     * Sums the given values, treating {@code null} as zero. Never returns null —
     * an empty or all-null argument list sums to {@link BigDecimal#ZERO}. Callers
     * that need "absent" semantics for a zero result should wrap with
     * {@link #nullIfZero(BigDecimal)}.
     */
    public static BigDecimal sum(@Nullable BigDecimal... values) {
        var total = BigDecimal.ZERO;
        for (var value : values) {
            if (value != null) {
                total = total.add(value);
            }
        }
        return total;
    }

    /**
     * Maps a zero (or null) amount to {@code null}, for DTO fields whose contract
     * is "null when not applicable" rather than zero.
     */
    @Nullable
    public static BigDecimal nullIfZero(@Nullable BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) == 0 ? null : value;
    }
}
