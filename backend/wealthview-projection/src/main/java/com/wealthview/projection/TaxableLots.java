package com.wealthview.projection;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * FIFO cost-basis lots for a taxable pool. Each lot is {@code [basis, value, ownerCode]}, oldest
 * first — {@code ownerCode} is the {@link LotOwner#ordinal()} of the lot's owner tag (HP1),
 * consumed only by {@link #stepUpByOwner}; every other method touches indices 0/1 only.
 */
final class TaxableLots {

    private final Deque<double[]> lots = new ArrayDeque<>();

    /** Adds a joint-tagged lot purchased at cost — basis equals value, so it carries no embedded
     * gain. The default JOINT tag is correct for every household-level reinvested flow (dividends,
     * interest, RMD after-tax remainder, surplus reinvestment) and for single-person lots. */
    void addLot(double amount) {
        if (amount > 0) {
            lots.addLast(lot(amount, amount, LotOwner.JOINT));
        }
    }

    /** Seeds a joint-tagged lot with an explicit basis and value; {@code value} may carry an
     * embedded gain. */
    void addLot(double basis, double value) {
        addLot(basis, value, LotOwner.JOINT);
    }

    /** HP1: seeds a lot tagged with its source account's {@code owner}; {@code value} may carry an
     * embedded gain. The tag is metadata read only by {@link #stepUpByOwner}. */
    void addLot(double basis, double value, LotOwner owner) {
        if (value > 0) {
            lots.addLast(lot(Math.max(0, basis), value, owner));
        }
    }

    private static double[] lot(double basis, double value, LotOwner owner) {
        return new double[]{basis, value, owner.ordinal()};
    }

    void grow(double appreciationRate) {
        double factor = 1 + appreciationRate;
        for (double[] lot : lots) {
            lot[1] *= factor;
        }
    }

    /**
     * Household task 6: steps up each lot's cost basis toward its current value by {@code factor} of
     * the embedded gain ({@code basis += (value − basis) × factor}); value is untouched. This is the
     * {@code double} counterpart of {@link TaxableLotsBd#stepUp} — the first-death basis step-up on
     * the joint taxable pool. {@code factor == 1.0} sets basis to value (full step-up, no residual
     * gain); {@code 0.5} steps up half the gain (common-law joint default); {@code 0.0} is a no-op. A
     * lot carrying an embedded LOSS ({@code value < basis}) steps DOWN symmetrically.
     */
    void stepUp(double factor) {
        for (double[] lot : lots) {
            lot[0] += (lot[1] - lot[0]) * factor;
        }
    }

    /**
     * HP1 (first-death basis step-up, EXACT per-owner) — the {@code double} counterpart of
     * {@link TaxableLotsBd#stepUpByOwner}. Raises each lot's basis by the statutory factor for ITS
     * owner versus the decedent: a joint lot by {@code jointFactor}
     * ({@code communityProperty ? 1.0 : 0.5}), a lot owned by the {@code deceased} fully (1.0), a
     * lot owned by the survivor not at all (0.0). Decedent-owned lots are retagged to the survivor
     * afterward. Value is untouched; only future realized-gain arithmetic shrinks. Replaces the T6
     * single-blended-factor approximation so mixed-ownership taxable steps up exactly.
     */
    void stepUpByOwner(LotOwner deceased, double jointFactor) {
        LotOwner survivor = deceased == LotOwner.PRIMARY ? LotOwner.SPOUSE : LotOwner.PRIMARY;
        for (double[] lot : lots) {
            LotOwner owner = LotOwner.fromCode((int) lot[2]);
            double factor;
            if (owner == LotOwner.JOINT) {
                factor = jointFactor;
            } else if (owner == deceased) {
                factor = 1.0;
            } else {
                factor = 0.0;
            }
            lot[0] += (lot[1] - lot[0]) * factor;
            if (owner == deceased) {
                lot[2] = survivor.ordinal();
            }
        }
    }

    double totalValue() {
        double v = 0;
        for (double[] lot : lots) {
            v += lot[1];
        }
        return v;
    }

    double totalBasis() {
        double b = 0;
        for (double[] lot : lots) {
            b += lot[0];
        }
        return b;
    }

    /** Sells {@code amount} of value oldest-first; returns the realized long-term gain. */
    double sellFifo(double amount) {
        double remaining = Math.min(amount, totalValue());
        double gain = 0;
        while (remaining > 1e-12 && !lots.isEmpty()) {
            double[] lot = lots.peekFirst();
            double basis = lot[0];
            double value = lot[1];
            if (value <= remaining + 1e-12) {
                gain += value - basis;   // whole lot sold
                remaining -= value;
                lots.removeFirst();
            } else {
                double sold = remaining;
                double soldBasis = basis * (sold / value);
                gain += sold - soldBasis;
                lot[0] = basis - soldBasis;
                lot[1] = value - sold;
                remaining = 0;
            }
        }
        return gain;
    }

    /** HP3 Part C (asymmetry note): a hot-loop safety valve with no {@link TaxableLotsBd} (det
     * engine) counterpart -- see that class's javadoc for why the asymmetry is intentional, not a
     * gap. Bounded (fires only above {@code cap}) and unreachable at realistic horizons. */
    void consolidateIfNeeded(int cap) {
        if (lots.size() <= cap) {
            return;
        }
        int toMerge = lots.size() - cap + 1;
        double basis = 0;
        double value = 0;
        for (int i = 0; i < toMerge; i++) {
            double[] lot = lots.removeFirst();
            basis += lot[0];
            value += lot[1];
        }
        // HP1: the merged lot is tagged JOINT. This safety valve fires only above `cap` (200) lots —
        // never at realistic horizons, and always AFTER the retired-year reinvestment lots (all
        // already JOINT) dominate — so collapsing owner detail here cannot affect a realistic
        // first-death step-up, which happens well before any consolidation could occur.
        lots.addFirst(lot(basis, value, LotOwner.JOINT));
    }
}
