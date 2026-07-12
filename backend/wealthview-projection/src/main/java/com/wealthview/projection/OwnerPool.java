package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.household.PersonId;

/**
 * Household task 4: an owner-keyed (PRIMARY/SPOUSE) BigDecimal balance pool — the internal shape of
 * the deterministic engine's traditional and Roth pools once accounts became individually owned.
 * Extracted from {@link PoolStrategy.MultiPool} so the tax cascade stays there while the pool
 * mechanics (owner routing, proportional draws, per-owner RMD forcing) live in one focused type.
 *
 * <p>Every quantity the tax cascade needs is the SUM across owners ({@link #total()}); the per-owner
 * split only matters for the two RMD streams, owner-routed contributions/conversions, and
 * proportional draws. A single-person pool holds exactly one {@link PersonId#PRIMARY} entry, so every
 * method reduces to scalar arithmetic on that one entry — the byte-identical single-person anchor.
 * The backing map is an {@link EnumMap}, so iteration is always in enum order (PRIMARY leads), which
 * {@link #debitProportional} relies on for its remainder convention.
 */
final class OwnerPool {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Map<PersonId, BigDecimal> balances;

    private OwnerPool(Map<PersonId, BigDecimal> balances) {
        this.balances = balances;
    }

    /** Owner an account belongs to: {@code "spouse"} -> SPOUSE, everything else (incl. the
     * taxable-only {@code "joint"} default) -> PRIMARY. */
    private static PersonId ownerOf(ProjectionAccountInput account) {
        return "spouse".equals(account.owner()) ? PersonId.SPOUSE : PersonId.PRIMARY;
    }

    /** Seeds a pool from accounts' initial balances, guaranteeing a PRIMARY entry (zero if empty). */
    static OwnerPool ofBalances(List<ProjectionAccountInput> accounts) {
        return build(accounts, ProjectionAccountInput::initialBalance);
    }

    /** An owner-keyed annual-contribution schedule, keyed identically to {@link #ofBalances}. */
    static OwnerPool ofContributions(List<ProjectionAccountInput> accounts) {
        return build(accounts, ProjectionAccountInput::annualContribution);
    }

    private static OwnerPool build(List<ProjectionAccountInput> accounts,
                                   Function<ProjectionAccountInput, BigDecimal> amountOf) {
        var map = new EnumMap<PersonId, BigDecimal>(PersonId.class);
        for (var account : accounts) {
            map.merge(ownerOf(account), amountOf.apply(account), BigDecimal::add);
        }
        map.putIfAbsent(PersonId.PRIMARY, BigDecimal.ZERO);
        return new OwnerPool(map);
    }

    /** Sum across all owners — the balance every public {@link PoolStrategy} accessor reports. */
    BigDecimal total() {
        BigDecimal sum = BigDecimal.ZERO;
        for (var value : balances.values()) {
            sum = sum.add(value);
        }
        return sum;
    }

    /** A detached copy of the per-owner balances (for RMD-stream inputs and mementos). */
    Map<PersonId, BigDecimal> byOwner() {
        return new EnumMap<>(balances);
    }

    /** Adds each owner's slice from a contribution schedule, routing by account owner. */
    void creditAll(OwnerPool contributions) {
        for (var entry : contributions.balances.entrySet()) {
            balances.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
        }
    }

    /** Adds {@code amount} to one owner's balance, creating the entry if absent. */
    void credit(PersonId owner, BigDecimal amount) {
        balances.merge(owner, amount, BigDecimal::add);
    }

    /** Grows every owner's slice at {@code rate} (rounded per owner); returns the summed growth. */
    BigDecimal grow(BigDecimal rate) {
        BigDecimal totalGrowth = BigDecimal.ZERO;
        for (var entry : balances.entrySet()) {
            BigDecimal growth = entry.getValue().multiply(rate).setScale(SCALE, ROUNDING);
            entry.setValue(entry.getValue().add(growth));
            totalGrowth = totalGrowth.add(growth);
        }
        return totalGrowth;
    }

    /**
     * Debits {@code amount} split proportionally by owner balance; returns the per-owner amounts
     * actually debited (used to credit the same owner's Roth on a conversion). Rounding convention
     * (documented at the seam): the leading owner's share is {@code amount × bal ÷ total} rounded
     * (scale {@value #SCALE}, {@link RoundingMode#HALF_UP}); the trailing owner takes the exact
     * remainder ({@code amount} minus what was already allocated), so the pool total drops by EXACTLY
     * {@code amount} — exactness of the sum over symmetry. A single-owner pool (the single-person
     * anchor) or a non-positive total takes the full amount with NO division, keeping every
     * pre-household path bit-for-bit; a resulting negative (the Roth tax catch-all) is cleared by
     * {@link #floorAtZero}.
     */
    Map<PersonId, BigDecimal> debitProportional(BigDecimal amount) {
        var debited = new EnumMap<PersonId, BigDecimal>(PersonId.class);
        var keys = new ArrayList<>(balances.keySet());
        BigDecimal poolTotal = total();
        if (keys.size() == 1 || poolTotal.signum() <= 0) {
            PersonId only = keys.get(0);
            balances.put(only, balances.get(only).subtract(amount));
            debited.put(only, amount);
            return debited;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < keys.size() - 1; i++) {
            PersonId key = keys.get(i);
            BigDecimal share = amount.multiply(balances.get(key)).divide(poolTotal, SCALE, ROUNDING);
            balances.put(key, balances.get(key).subtract(share));
            debited.put(key, share);
            allocated = allocated.add(share);
        }
        PersonId last = keys.get(keys.size() - 1);
        BigDecimal remainder = amount.subtract(allocated);
        balances.put(last, balances.get(last).subtract(remainder));
        debited.put(last, remainder);
        return debited;
    }

    /**
     * Household task 5 (first-death spousal rollover): moves the ENTIRE {@code from} owner's balance
     * into the {@code to} owner's balance and zeroes {@code from}. An unconditional transfer with no
     * tax, no lot creation, and no RMD/force-out accounting — the treat-as-own inheritance the
     * survivor takes at the first death. Conservation-preserving by construction: {@link #total()} is
     * unchanged. A {@code from == to} call or an empty {@code from} balance is a no-op.
     */
    void transferAll(PersonId from, PersonId to) {
        if (from == to) {
            return;
        }
        BigDecimal fromBalance = balances.getOrDefault(from, BigDecimal.ZERO);
        if (fromBalance.signum() != 0) {
            balances.merge(to, fromBalance, BigDecimal::add);
            balances.put(from, BigDecimal.ZERO);
        }
    }

    /** Forces up to {@code amount} out of ONE owner's balance; returns the amount actually removed. */
    BigDecimal forceOwner(PersonId owner, BigDecimal amount) {
        BigDecimal ownerBalance = balances.getOrDefault(owner, BigDecimal.ZERO);
        BigDecimal forced = amount.min(ownerBalance).max(BigDecimal.ZERO);
        if (forced.signum() > 0) {
            balances.put(owner, ownerBalance.subtract(forced));
        }
        return forced;
    }

    /** Clamps each owner's slice at zero (the tax cascade can drive the Roth catch-all negative). */
    void floorAtZero() {
        balances.replaceAll((owner, balance) -> balance.max(BigDecimal.ZERO));
    }

    /** A detached copy of the state for the SS-convergence memento. */
    Map<PersonId, BigDecimal> snapshot() {
        return new EnumMap<>(balances);
    }

    /** Restores from a prior {@link #snapshot()} (a fresh copy so repeated restores are safe). */
    void restore(Map<PersonId, BigDecimal> snapshot) {
        this.balances = new EnumMap<>(snapshot);
    }
}
