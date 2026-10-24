package com.wealthview.projection;

import java.util.Arrays;
import java.util.List;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.PoolType;

/**
 * The Monte Carlo twin of {@link TentReservation}: a bond tent held as a reservation inside the three
 * household pools, in {@code double} and real terms. One instance per trial, mutated in place.
 *
 * <p>Sleeves live in one flat {@code double[12]} indexed {@code pool.ordinal() * 4 + maturity.ordinal()}.
 * A pool owns no copy of its sleeves' dollars: the pool balance still includes them, so a reservation can
 * never exceed its pool's balance -- {@link #clampAll} enforces that, moving any excess to other pools'
 * free room (longest maturity first) and spending only what no pool can hold.
 *
 * <p>The growth index tracks the growth assets only (the pool returns weighted by each pool's growth
 * part); it starts at 1.0 with a running peak that includes every value so far.
 *
 * <p><b>Allocation discipline (hot loop):</b> like {@link TrialPools}, no method allocates -- all
 * parameters are scalars and the only state is the sleeve array and a scratch array sized at construction.
 */
final class TrialTent {

    private static final int MATURITIES = BondMaturity.values().length;
    private static final int POOLS = PoolType.values().length;

    private final TrialTentPlan plan;
    private final double[] sleeveReturns;
    private final double[] sleeves = new double[POOLS * MATURITIES];
    private final double[] displaced = new double[MATURITIES];
    private double index = 1.0;
    private double peak = 1.0;

    // ArrayIsStoredDirectly: sleeveReturns is the trial's own read-only return grid, handed off for this
    // trial's exclusive use (see TrialPools' constructor for the same contract). UseVarargs: it is a grid,
    // not a variable-length argument list.
    @SuppressWarnings({"PMD.ArrayIsStoredDirectly", "PMD.UseVarargs"})
    TrialTent(TrialTentPlan plan, double[] sleeveReturns) {
        this.plan = plan;
        this.sleeveReturns = sleeveReturns;
    }

    int yearsBeforeRetirement() {
        return plan.yearsBeforeRetirement();
    }

    double total() {
        double sum = 0;
        for (double sleeve : sleeves) {
            sum += sleeve;
        }
        return sum;
    }

    double totalIn(PoolType pool) {
        double sum = 0;
        int base = pool.ordinal() * MATURITIES;
        for (int m = 0; m < MATURITIES; m++) {
            sum += sleeves[base + m];
        }
        return sum;
    }

    double byMaturity(BondMaturity maturity) {
        double sum = 0;
        for (int p = 0; p < POOLS; p++) {
            sum += sleeves[p * MATURITIES + maturity.ordinal()];
        }
        return sum;
    }

    /** One sleeve: {@code pool}'s reservation at {@code maturity}. */
    double sleeve(PoolType pool, BondMaturity maturity) {
        return sleeves[pool.ordinal() * MATURITIES + maturity.ordinal()];
    }

    boolean isEmpty() {
        return total() <= 0;
    }

    /**
     * Reserves {@code min(cap, max(0, level - total()))} more, filling each pool in placement order up to
     * its free balance (balance minus what it already reserves) and splitting by the plan's mix shares.
     */
    void topUp(double level, double taxable, double traditional, double roth, double cap) {
        double needed = Math.min(cap, Math.max(0, level - total()));
        List<PoolType> order = plan.placementOrder();
        for (int i = 0; i < order.size() && needed > 0; i++) {
            PoolType pool = order.get(i);
            double free = Math.max(0, balanceOf(pool, taxable, traditional, roth) - totalIn(pool));
            double taken = Math.min(needed, free);
            if (taken > 0) {
                int base = pool.ordinal() * MATURITIES;
                for (int m = 0; m < MATURITIES; m++) {
                    sleeves[base + m] += taken * plan.mixShares()[m];
                }
                needed -= taken;
            }
        }
    }

    /** The pool after one year: growth part at {@code poolReturn}, each sleeve at its own return. No mutation. */
    double grownBalance(PoolType pool, double balance, double poolReturn, int yearFromToday) {
        double grown = (balance - totalIn(pool)) * (1 + poolReturn);
        int base = pool.ordinal() * MATURITIES;
        int row = yearFromToday * MATURITIES;
        for (int m = 0; m < MATURITIES; m++) {
            grown += sleeves[base + m] * (1 + sleeveReturns[row + m]);
        }
        return grown;
    }

    void growSleeves(int yearFromToday) {
        int row = yearFromToday * MATURITIES;
        for (int p = 0; p < POOLS; p++) {
            for (int m = 0; m < MATURITIES; m++) {
                sleeves[p * MATURITIES + m] *= 1 + sleeveReturns[row + m];
            }
        }
    }

    /** Sum of sleeve times typed nominal yield for {@code pool}; no mutation. */
    double interestIn(PoolType pool) {
        double interest = 0;
        int base = pool.ordinal() * MATURITIES;
        for (int m = 0; m < MATURITIES; m++) {
            interest += sleeves[base + m] * plan.nominalYields()[m];
        }
        return interest;
    }

    /** The pool returns weighted by each pool's growth part (balance minus reservation); 0 when none is left. */
    double growthReturn(double taxable, double traditional, double roth, double taxableReturn,
                        double traditionalReturn, double rothReturn) {
        double taxablePart = Math.max(0, taxable - totalIn(PoolType.TAXABLE));
        double traditionalPart = Math.max(0, traditional - totalIn(PoolType.TRADITIONAL));
        double rothPart = Math.max(0, roth - totalIn(PoolType.ROTH));
        double weight = taxablePart + traditionalPart + rothPart;
        if (weight <= 0) {
            return 0;
        }
        return (taxablePart * taxableReturn + traditionalPart * traditionalReturn + rothPart * rothReturn) / weight;
    }

    void advanceIndex(double growthReturn) {
        index *= 1 + growthReturn;
        peak = Math.max(peak, index);
    }

    boolean isBelowPeak() {
        return index < peak;
    }

    /** Removes {@code min(amount, total())}: shortest maturity first, within a maturity in placement order. */
    void spend(double amount) {
        double remaining = amount;
        List<PoolType> order = plan.placementOrder();
        for (int m = 0; m < MATURITIES && remaining > 0; m++) {
            for (int i = 0; i < order.size() && remaining > 0; i++) {
                int slot = order.get(i).ordinal() * MATURITIES + m;
                double taken = Math.min(remaining, sleeves[slot]);
                sleeves[slot] -= taken;
                remaining -= taken;
            }
        }
    }

    /**
     * Clamps every pool's reservation to its balance. Excess leaves a pool shortest maturity first and is
     * re-placed into other pools' free room in placement order, longest maturity first; whatever no pool can
     * hold is spent.
     */
    // ForLoopCanBeForeach: for-each over the List would allocate an Iterator per call in the hot loop.
    @SuppressWarnings("PMD.ForLoopCanBeForeach")
    void clampAll(double taxable, double traditional, double roth) {
        Arrays.fill(displaced, 0);
        for (PoolType pool : PoolType.values()) {
            double excess = totalIn(pool) - Math.max(0, balanceOf(pool, taxable, traditional, roth));
            int base = pool.ordinal() * MATURITIES;
            for (int m = 0; m < MATURITIES && excess > 0; m++) {
                double taken = Math.min(excess, sleeves[base + m]);
                sleeves[base + m] -= taken;
                displaced[m] += taken;
                excess -= taken;
            }
        }
        List<PoolType> order = plan.placementOrder();
        for (int i = 0; i < order.size(); i++) {
            PoolType pool = order.get(i);
            double free = Math.max(0, balanceOf(pool, taxable, traditional, roth) - totalIn(pool));
            int base = pool.ordinal() * MATURITIES;
            for (int m = MATURITIES - 1; m >= 0 && free > 0; m--) {
                double moved = Math.min(displaced[m], free);
                sleeves[base + m] += moved;
                displaced[m] -= moved;
                free -= moved;
            }
        }
    }

    private static double balanceOf(PoolType pool, double taxable, double traditional, double roth) {
        return switch (pool) {
            case TAXABLE -> taxable;
            case TRADITIONAL -> traditional;
            case ROTH -> roth;
        };
    }
}
