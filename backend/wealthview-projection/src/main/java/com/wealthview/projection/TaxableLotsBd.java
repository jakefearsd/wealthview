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

    /**
     * Household task 5 (first-death basis step-up): raises each lot's BASIS toward its current value
     * by {@code factor} of the embedded gain — per-lot {@code basis += (value − basis) × factor}. A
     * {@code factor} of {@code 1.0} sets {@code basis := value} exactly (a full step-up to fair
     * market value, so a subsequent FIFO sale realizes no gain on the stepped portion); {@code 0.5}
     * (the common-law half step-up on jointly-held property at first death) moves the basis halfway
     * to value. Value is never touched, so {@link #totalValue()} — and therefore the pool balance —
     * is unchanged; only future realized-gain arithmetic shrinks. A lot carrying a loss
     * ({@code value < basis}) is stepped DOWN by the same formula, matching a true step-to-FMV.
     */
    void stepUp(BigDecimal factor) {
        for (BigDecimal[] lot : lots) {
            BigDecimal adjustment = lot[1].subtract(lot[0]).multiply(factor).setScale(SCALE, ROUNDING);
            lot[0] = lot[0].add(adjustment);
        }
    }

    /** Appreciates each lot's value (not its basis) by {@code appreciationRate}. */
    void grow(BigDecimal appreciationRate) {
        BigDecimal factor = BigDecimal.ONE.add(appreciationRate);
        for (BigDecimal[] lot : lots) {
            lot[1] = lot[1].multiply(factor).setScale(SCALE, ROUNDING);
        }
    }

    /**
     * Audit C8: appreciates every lot's VALUE (never basis) via {@link #grow}, then nudges the LAST
     * lot's value by any leftover cent so {@link #totalValue()} lands EXACTLY on {@code exactTotal}
     * -- absorbing the sub-cent drift independent per-lot rounding can otherwise introduce over many
     * lots/years. Unlike {@link #addLot(BigDecimal)}, the residual is folded into an EXISTING lot's
     * value: it creates NO new lot and touches NO lot's basis, so accumulation-year growth (which
     * must not book a phantom at-cost distribution lot -- see {@code PoolStrategy.MultiPool
     * #applyGrowth(boolean)}) still reproduces the exact same total-return trajectory the pre-C8
     * split path guaranteed by construction. No-op when there are no lots ({@code exactTotal} must
     * be zero too in that case).
     */
    void growToExactTotal(BigDecimal appreciationRate, BigDecimal exactTotal) {
        grow(appreciationRate);
        if (lots.isEmpty()) {
            return;
        }
        BigDecimal residual = exactTotal.subtract(totalValue());
        if (residual.signum() != 0) {
            BigDecimal[] last = lots.peekLast();
            last[1] = last[1].add(residual);
        }
    }

    /** Number of open lots -- audit C8 test hook confirming accumulation-year growth creates none. */
    int lotCount() {
        return lots.size();
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
