package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * BigDecimal FIFO cost-basis lots for the deterministic engine's taxable pool. Each lot is
 * {@code [basis, value]}, oldest first.
 *
 * <p>This mirrors {@link TaxableLots} (the {@code double} variant used by the Monte Carlo hot
 * loop) in exact BigDecimal arithmetic ({@code SCALE = 4}, {@code HALF_UP}) so the deterministic
 * engine stays golden-file reproducible. It is the same money-type duplication the codebase
 * already carries for {@link PoolTaxCascade} (a {@code double} cascade with a separate
 * BigDecimal counterpart in {@link PoolStrategy}).
 */
final class TaxableLotsBd {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    /** Extra precision for the proportional-basis division before final rounding to {@link #SCALE}. */
    private static final int DIV_SCALE = SCALE + 8;

    private final Deque<BigDecimal[]> lots = new ArrayDeque<>();

    /** Adds a lot purchased at cost — basis equals value, so it carries no embedded gain. */
    void addLot(BigDecimal amount) {
        if (amount.signum() > 0) {
            lots.addLast(new BigDecimal[]{amount, amount});
        }
    }

    /** Seeds a lot with an explicit basis and value; {@code value} may carry an embedded gain. */
    void addLot(BigDecimal basis, BigDecimal value) {
        if (value.signum() > 0) {
            lots.addLast(new BigDecimal[]{basis.max(BigDecimal.ZERO), value});
        }
    }

    /** Appreciates each lot's value (not its basis) by {@code appreciationRate}. */
    void grow(BigDecimal appreciationRate) {
        BigDecimal factor = BigDecimal.ONE.add(appreciationRate);
        for (BigDecimal[] lot : lots) {
            lot[1] = lot[1].multiply(factor).setScale(SCALE, ROUNDING);
        }
    }

    BigDecimal totalValue() {
        BigDecimal v = BigDecimal.ZERO;
        for (BigDecimal[] lot : lots) {
            v = v.add(lot[1]);
        }
        return v;
    }

    BigDecimal totalBasis() {
        BigDecimal b = BigDecimal.ZERO;
        for (BigDecimal[] lot : lots) {
            b = b.add(lot[0]);
        }
        return b;
    }

    /** Deep-copies the lots into a detached list for later {@link #restore(java.util.List)}. */
    java.util.List<BigDecimal[]> snapshot() {
        var copy = new java.util.ArrayList<BigDecimal[]>(lots.size());
        for (BigDecimal[] lot : lots) {
            copy.add(new BigDecimal[]{lot[0], lot[1]});
        }
        return copy;
    }

    /** Replaces the current lots with a deep copy of a prior {@link #snapshot()}. */
    void restore(java.util.List<BigDecimal[]> snapshot) {
        lots.clear();
        for (BigDecimal[] lot : snapshot) {
            lots.addLast(new BigDecimal[]{lot[0], lot[1]});
        }
    }

    /** Sells {@code amount} of value oldest-first (capped at the total); returns the realized gain. */
    BigDecimal sellFifo(BigDecimal amount) {
        BigDecimal remaining = amount.min(totalValue()).max(BigDecimal.ZERO);
        BigDecimal gain = BigDecimal.ZERO;
        while (remaining.signum() > 0 && !lots.isEmpty()) {
            BigDecimal[] lot = lots.peekFirst();
            BigDecimal basis = lot[0];
            BigDecimal value = lot[1];
            if (value.compareTo(remaining) <= 0) {
                gain = gain.add(value.subtract(basis));   // whole lot sold
                remaining = remaining.subtract(value);
                lots.removeFirst();
            } else {
                BigDecimal sold = remaining;
                BigDecimal soldBasis = basis.multiply(sold)
                        .divide(value, DIV_SCALE, ROUNDING).setScale(SCALE, ROUNDING);
                gain = gain.add(sold.subtract(soldBasis));
                lot[0] = basis.subtract(soldBasis);
                lot[1] = value.subtract(sold);
                remaining = BigDecimal.ZERO;
            }
        }
        return gain.setScale(SCALE, ROUNDING);
    }
}
