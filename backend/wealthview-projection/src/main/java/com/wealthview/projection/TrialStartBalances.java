package com.wealthview.projection;

import java.util.Arrays;
import java.util.List;

import com.wealthview.core.projection.dto.LotOwner;
import com.wealthview.core.projection.dto.PoolType;

/**
 * Per-trial balances at retirement start, one value per pool and owner slice. Each Monte Carlo trial
 * accumulates every account through the pre-retirement years on its own return path, so the balances
 * a trial retires with vary across trials; this record carries one trial's result.
 */
record TrialStartBalances(double taxable, double traditional, double roth,
                          double traditionalSpouse, double rothSpouse,
                          double taxableJoint, double taxablePrimary, double taxableSpouse) {

    /** Total start balance across the three tax pools, summed in the same order as the expected path. */
    double total() {
        return taxable + traditional + roth;
    }

    /**
     * Sums per-account balances into pool and owner slices. {@code balances[i]} belongs to
     * {@code accounts.get(i)}; traditional and roth include every owner, the spouse slices include only
     * {@link LotOwner#SPOUSE}, and the taxable owner slices split the taxable pool by owner.
     */
    // ShortMethodName: 'of' is the conventional JDK-style static factory method name.
    // UseVarargs: balances is a per-account indexed array paired by position with accounts, not a
    // variable list of like-typed arguments; varargs would hide the length contract checked above.
    @SuppressWarnings({"PMD.ShortMethodName", "PMD.UseVarargs"})
    static TrialStartBalances of(List<PoolReturnModel.AccountReturnSource> accounts, double[] balances) {
        if (accounts.size() != balances.length) {
            throw new IllegalArgumentException(
                    "accounts and balances differ in length: " + accounts.size() + " vs " + balances.length);
        }
        double taxable = 0;
        double traditional = 0;
        double roth = 0;
        double traditionalSpouse = 0;
        double rothSpouse = 0;
        double taxableJoint = 0;
        double taxablePrimary = 0;
        double taxableSpouse = 0;
        for (int i = 0; i < balances.length; i++) {
            var account = accounts.get(i);
            double balance = balances[i];
            boolean spouse = account.owner() == LotOwner.SPOUSE;
            if (account.poolType() == PoolType.TAXABLE) {
                taxable += balance;
                if (account.owner() == LotOwner.JOINT) {
                    taxableJoint += balance;
                } else if (spouse) {
                    taxableSpouse += balance;
                } else {
                    taxablePrimary += balance;
                }
            } else if (account.poolType() == PoolType.TRADITIONAL) {
                traditional += balance;
                if (spouse) {
                    traditionalSpouse += balance;
                }
            } else {
                roth += balance;
                if (spouse) {
                    rothSpouse += balance;
                }
            }
        }
        return new TrialStartBalances(taxable, traditional, roth, traditionalSpouse, rothSpouse,
                taxableJoint, taxablePrimary, taxableSpouse);
    }

    /**
     * These balances with each pool moved by the given amount (floored at 0), its owner slices scaled
     * proportionally. A pool with nothing in it takes the amount on its primary slice (taxable: joint).
     */
    TrialStartBalances adjustedBy(double taxableAmount, double traditionalAmount, double rothAmount) {
        double newTaxable = Math.max(0, taxable + taxableAmount);
        double newTraditional = Math.max(0, traditional + traditionalAmount);
        double newRoth = Math.max(0, roth + rothAmount);
        double taxableScale = scale(taxable, newTaxable);
        boolean taxableEmpty = taxable <= 0;
        return new TrialStartBalances(newTaxable, newTraditional, newRoth,
                traditionalSpouse * scale(traditional, newTraditional), rothSpouse * scale(roth, newRoth),
                taxableEmpty ? newTaxable : taxableJoint * taxableScale,
                taxableEmpty ? 0 : taxablePrimary * taxableScale,
                taxableEmpty ? 0 : taxableSpouse * taxableScale);
    }

    private static double scale(double old, double adjusted) {
        return old > 0 ? adjusted / old : 0;
    }

    /**
     * Median of {@link #total()} over the given trials. An even count averages the two middle values.
     */
    // UseVarargs: starts is a collection of trial results already held as an array by the caller.
    @SuppressWarnings("PMD.UseVarargs")
    static double medianTotal(TrialStartBalances[] starts) {
        if (starts.length == 0) {
            throw new IllegalArgumentException("medianTotal requires at least one trial");
        }
        double[] totals = Arrays.stream(starts).mapToDouble(TrialStartBalances::total).sorted().toArray();
        int mid = totals.length / 2;
        if (totals.length % 2 == 1) {
            return totals[mid];
        }
        return (totals[mid - 1] + totals[mid]) / 2.0;
    }
}
