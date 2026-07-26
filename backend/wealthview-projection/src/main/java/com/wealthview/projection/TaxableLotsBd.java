package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;

import com.wealthview.core.projection.dto.LotOwner;

/**
 * BigDecimal FIFO cost-basis lots for the deterministic engine's taxable pool. Each lot is
 * {@code [basis, value]}, oldest first.
 *
 * <p>This mirrors {@link TaxableLots} (the {@code double} variant used by the Monte Carlo hot
 * loop) in exact BigDecimal arithmetic ({@code SCALE = 4}, {@code HALF_UP}) so the deterministic
 * engine stays golden-file reproducible. It is the same money-type duplication the codebase
 * already carries for {@link PoolTaxCascade} (a {@code double} cascade with a separate
 * BigDecimal counterpart in {@link PoolStrategy}).
 *
 * <p>HP3 Part C (asymmetry note): unlike {@link TaxableLots}, this class has NO
 * {@code consolidateIfNeeded} counterpart. That safety valve exists on the Monte Carlo side because
 * its per-year lot growth (dividends/interest/RMD-remainder/surplus reinvestments) is re-iterated
 * on every one of potentially thousands of trials in the hot loop -- a multiplicative cost, not an
 * absolute-lot-count one. A deterministic run walks the SAME per-year lot growth exactly ONCE, so
 * even a comparable lot count over one horizon is a one-time, negligible cost. Not an oversight.
 */
final class TaxableLotsBd {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    /** Extra precision for the proportional-basis division before final rounding to {@link #SCALE}. */
    private static final int DIV_SCALE = SCALE + 8;

    /**
     * Each lot is {@code [basis, value, ownerCode]}, oldest first. {@code ownerCode} is the
     * {@link LotOwner#ordinal()} of the lot's owner tag (HP1) — consumed only by
     * {@link #stepUpByOwner}; all other arithmetic touches indices 0/1 only.
     */
    private final Deque<BigDecimal[]> lots = new ArrayDeque<>();

    /** Adds a joint-tagged lot purchased at cost — basis equals value, so it carries no embedded
     * gain. The default JOINT tag is correct for every household-level reinvested flow (dividends,
     * interest, RMD after-tax remainder, surplus reinvestment) and for single-person lots. */
    void addLot(BigDecimal amount) {
        if (amount.signum() > 0) {
            lots.addLast(lot(amount, amount, LotOwner.JOINT));
        }
    }

    /** Seeds a joint-tagged lot with an explicit basis and value; {@code value} may carry an
     * embedded gain. */
    void addLot(BigDecimal basis, BigDecimal value) {
        addLot(basis, value, LotOwner.JOINT);
    }

    /** HP1: seeds a lot tagged with its source account's {@code owner}; {@code value} may carry an
     * embedded gain. The tag is metadata read only by {@link #stepUpByOwner}. */
    void addLot(BigDecimal basis, BigDecimal value, LotOwner owner) {
        if (value.signum() > 0) {
            lots.addLast(lot(basis.max(BigDecimal.ZERO), value, owner));
        }
    }

    private static BigDecimal[] lot(BigDecimal basis, BigDecimal value, LotOwner owner) {
        return new BigDecimal[]{basis, value, BigDecimal.valueOf(owner.ordinal())};
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
            lot[0] = steppedBasis(lot, factor);
        }
    }

    /**
     * HP1 (first-death basis step-up, EXACT per-owner): raises each lot's basis by the statutory
     * factor for ITS owner versus the decedent — a joint lot by {@code jointFactor}
     * ({@code communityProperty ? 1.0 : 0.5}), a lot owned by the {@code deceased} fully (1.0), a
     * lot owned by the survivor not at all (0.0) — via the same per-lot formula as {@link #stepUp}
     * ({@code basis += (value − basis) × factor}). This replaces the T5 single-blended-factor
     * approximation: because every lot carries its own owner tag, mixed-ownership taxable with
     * divergent embedded gains now steps up exactly rather than by an initial-balance-weighted
     * average rate applied uniformly.
     *
     * <p>After the step-up a decedent-owned lot is RETAGGED to the survivor (the survivor now owns
     * it), so a later same-year re-entry would treat it correctly and the pool's owner composition
     * reflects the transfer. Value is never touched, so {@link #totalValue()} — and the pool
     * balance — is unchanged. For an all-joint (or single-owner) taxable pool every lot takes
     * {@code jointFactor} (or the decedent/survivor rate), reducing exactly to the old blended
     * result for those cases — the byte-identical anchor.
     */
    void stepUpByOwner(LotOwner deceased, BigDecimal jointFactor) {
        LotOwner survivor = deceased == LotOwner.PRIMARY ? LotOwner.SPOUSE : LotOwner.PRIMARY;
        for (BigDecimal[] lot : lots) {
            LotOwner owner = LotOwner.fromCode(lot[2].intValue());
            BigDecimal factor;
            if (owner == LotOwner.JOINT) {
                factor = jointFactor;
            } else if (owner == deceased) {
                factor = BigDecimal.ONE;
            } else {
                factor = BigDecimal.ZERO;
            }
            lot[0] = steppedBasis(lot, factor);
            if (owner == deceased) {
                lot[2] = BigDecimal.valueOf(survivor.ordinal());
            }
        }
    }

    /** The lot's basis raised toward its value by {@code factor} of the embedded gain
     * ({@code basis + (value − basis) × factor}), rounded to {@link #SCALE}. */
    private static BigDecimal steppedBasis(BigDecimal[] lot, BigDecimal factor) {
        BigDecimal adjustment = lot[1].subtract(lot[0]).multiply(factor).setScale(SCALE, ROUNDING);
        return lot[0].add(adjustment);
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

    /** Deep-copies the lots (basis, value, owner slot) into a detached list for later
     * {@link #restore(java.util.List)}. */
    java.util.List<BigDecimal[]> snapshot() {
        var copy = new java.util.ArrayList<BigDecimal[]>(lots.size());
        for (BigDecimal[] lot : lots) {
            copy.add(new BigDecimal[]{lot[0], lot[1], lot[2]});
        }
        return copy;
    }

    /** Replaces the current lots with a deep copy of a prior {@link #snapshot()}. */
    void restore(java.util.List<BigDecimal[]> snapshot) {
        lots.clear();
        for (BigDecimal[] lot : snapshot) {
            lots.addLast(new BigDecimal[]{lot[0], lot[1], lot[2]});
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
