package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.PoolType;

/**
 * A bond tent held as a reservation inside the deterministic engine's account pools: dollars in the
 * taxable, traditional or Roth pool earmarked as bond sleeves by maturity. A reservation changes
 * only how a pool grows (each sleeve earns its own maturity's return) and which dollars a debit
 * spends first (growth assets before sleeves, shortest maturity first); withdrawal order, RMDs,
 * conversions and taxes keep operating on pool totals.
 *
 * <p>Sleeve values are held at {@link PoolStrategy#SCALE}. The pool owns no copy of the sleeves'
 * dollars: the pool balance still includes them, so a reservation can never exceed its pool's
 * balance -- {@link #clampTo} enforces that lazily, removing any excess shortest maturity first.
 */
final class TentReservation {

    private static final int SCALE = PoolStrategy.SCALE;
    private static final int RATE_SCALE = PoolStrategy.SCALE + 4;

    private final Map<PoolType, Map<BondMaturity, BigDecimal>> sleeves = new EnumMap<>(PoolType.class);

    private TentReservation() {
    }

    static TentReservation empty() {
        return new TentReservation();
    }

    boolean isEmpty() {
        return total().signum() == 0;
    }

    BigDecimal total() {
        BigDecimal sum = BigDecimal.ZERO;
        for (PoolType pool : sleeves.keySet()) {
            sum = sum.add(totalIn(pool));
        }
        return sum;
    }

    BigDecimal totalIn(PoolType pool) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : sleevesOf(pool).values()) {
            sum = sum.add(value);
        }
        return sum;
    }

    /** Sleeve value by maturity summed over pools; every maturity is present, shortest first. */
    Map<BondMaturity, BigDecimal> byMaturity() {
        var sum = zeroed();
        for (Map<BondMaturity, BigDecimal> poolSleeves : sleeves.values()) {
            poolSleeves.forEach((maturity, value) -> sum.merge(maturity, value, BigDecimal::add));
        }
        return sum;
    }

    /**
     * Reserves {@code amount} in {@code pool}, split across maturities by {@code mixShares}. The
     * sleeve rounding residual lands on the largest share so the split sums to exactly {@code amount}.
     */
    void reserve(PoolType pool, BigDecimal amount, Map<BondMaturity, BigDecimal> mixShares) {
        if (amount.signum() <= 0) {
            return;
        }
        var split = zeroed();
        BigDecimal allocated = BigDecimal.ZERO;
        BondMaturity largest = BondMaturity.ULTRASHORT;
        for (BondMaturity maturity : BondMaturity.values()) {
            BigDecimal share = mixShares.getOrDefault(maturity, BigDecimal.ZERO);
            BigDecimal part = amount.multiply(share).setScale(SCALE, PoolStrategy.ROUNDING);
            split.put(maturity, part);
            allocated = allocated.add(part);
            if (share.compareTo(mixShares.getOrDefault(largest, BigDecimal.ZERO)) > 0) {
                largest = maturity;
            }
        }
        split.merge(largest, amount.subtract(allocated), BigDecimal::add);
        Map<BondMaturity, BigDecimal> target = sleevesOf(pool);
        split.forEach((maturity, value) -> target.merge(maturity, value, BigDecimal::add));
    }

    /** Grows {@code pool}'s sleeves, each at its own maturity's return; returns the growth. */
    BigDecimal grow(PoolType pool, Map<BondMaturity, BigDecimal> realReturns) {
        BigDecimal growth = BigDecimal.ZERO;
        for (var entry : sleevesOf(pool).entrySet()) {
            BigDecimal rate = realReturns.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            BigDecimal part = entry.getValue().multiply(rate).setScale(SCALE, PoolStrategy.ROUNDING);
            entry.setValue(entry.getValue().add(part));
            growth = growth.add(part);
        }
        return growth;
    }

    /**
     * A pool of balance {@code balance} (already clamped) after one year: the growth assets earn
     * {@code poolReturn}, each sleeve earns its maturity's return, i.e.
     * {@code (balance - T)(1 + poolReturn) + sum T_m (1 + r_m)}. Mutates the sleeves.
     */
    BigDecimal balanceAfterGrowth(PoolType pool, BigDecimal balance, BigDecimal poolReturn,
                                  Map<BondMaturity, BigDecimal> realReturns) {
        BigDecimal growthAssets = balance.subtract(totalIn(pool));
        BigDecimal assetGrowth = growthAssets.multiply(poolReturn).setScale(SCALE, PoolStrategy.ROUNDING);
        return balance.add(assetGrowth).add(grow(pool, realReturns));
    }

    /** The rate that takes {@code before} to {@code after}, at more precision than a balance. */
    static BigDecimal effectiveRate(BigDecimal before, BigDecimal after) {
        return after.divide(before, RATE_SCALE, PoolStrategy.ROUNDING).subtract(BigDecimal.ONE);
    }

    /** Removes any excess of this pool's sleeves over {@code poolBalance}, shortest maturity first. */
    void clampTo(PoolType pool, BigDecimal poolBalance) {
        BigDecimal excess = totalIn(pool).subtract(poolBalance.max(BigDecimal.ZERO));
        if (excess.signum() <= 0) {
            return;
        }
        for (var entry : sleevesOf(pool).entrySet()) {
            BigDecimal taken = excess.min(entry.getValue());
            entry.setValue(entry.getValue().subtract(taken));
            excess = excess.subtract(taken);
            if (excess.signum() == 0) {
                return;
            }
        }
    }

    /**
     * One taxable-pool year with a reservation: the blended {@code target} total and the distribution
     * (qualified {@code dividend} plus ordinary {@code interest}) that is reinvested at cost. The
     * audit-C1 split runs over the unreserved part only; the sleeves add their typed nominal interest.
     * An accumulation year distributes nothing (audit C8).
     */
    record TaxableYear(BigDecimal growth, BigDecimal target, BigDecimal dividend, BigDecimal interest) {
        BigDecimal distribution() {
            return dividend.add(interest);
        }
    }

    /** Grows the taxable sleeves and prices the year; see {@link TaxableYear}. */
    TaxableYear taxableYear(BigDecimal before, BigDecimal poolReturn, boolean retired, BigDecimal equityYieldRate,
                            BigDecimal bondYieldRate, Map<BondMaturity, BigDecimal> realReturns,
                            Map<BondMaturity, BigDecimal> nominalYields) {
        BigDecimal unreserved = before.subtract(totalIn(PoolType.TAXABLE));
        BigDecimal sleeveInterest = interestIn(PoolType.TAXABLE, nominalYields);
        BigDecimal target = balanceAfterGrowth(PoolType.TAXABLE, before, poolReturn, realReturns);
        if (!retired) {
            return new TaxableYear(target.subtract(before), target, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal dividend = unreserved.multiply(equityYieldRate).setScale(SCALE, PoolStrategy.ROUNDING);
        BigDecimal interest = unreserved.multiply(bondYieldRate).setScale(SCALE, PoolStrategy.ROUNDING)
                .add(sleeveInterest);
        return new TaxableYear(target.subtract(before), target, dividend.max(BigDecimal.ZERO),
                interest.max(BigDecimal.ZERO));
    }

    /** Clamps every pool's reservation to that pool's balance (see {@link #clampTo}). */
    void clampAll(Function<PoolType, BigDecimal> balanceOf) {
        for (PoolType pool : PoolType.values()) {
            clampTo(pool, balanceOf.apply(pool));
        }
    }

    /**
     * Reserves {@code max(0, target - reserved)} more, filling each pool in {@code placementOrder}
     * up to its unreserved balance; the caller has already clamped.
     */
    void topUp(BigDecimal target, List<PoolType> placementOrder, Map<BondMaturity, BigDecimal> mixShares,
               Function<PoolType, BigDecimal> balanceOf) {
        BigDecimal needed = target.subtract(total());
        for (PoolType pool : placementOrder) {
            BigDecimal free = balanceOf.apply(pool).subtract(totalIn(pool)).max(BigDecimal.ZERO);
            BigDecimal taken = needed.min(free);
            reserve(pool, taken, mixShares);
            needed = needed.subtract(taken);
        }
    }

    /** Sum of sleeve times typed nominal yield for {@code pool}; no mutation. */
    BigDecimal interestIn(PoolType pool, Map<BondMaturity, BigDecimal> nominalYields) {
        BigDecimal interest = BigDecimal.ZERO;
        for (var entry : sleevesOf(pool).entrySet()) {
            interest = interest.add(entry.getValue()
                    .multiply(nominalYields.getOrDefault(entry.getKey(), BigDecimal.ZERO)));
        }
        return interest.setScale(SCALE, PoolStrategy.ROUNDING);
    }

    TentReservation copy() {
        var copy = new TentReservation();
        sleeves.forEach((pool, poolSleeves) -> copy.sleeves.put(pool, new EnumMap<>(poolSleeves)));
        return copy;
    }

    private Map<BondMaturity, BigDecimal> sleevesOf(PoolType pool) {
        return sleeves.computeIfAbsent(pool, key -> zeroed());
    }

    private static Map<BondMaturity, BigDecimal> zeroed() {
        var map = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        for (BondMaturity maturity : BondMaturity.values()) {
            map.put(maturity, BigDecimal.ZERO);
        }
        return map;
    }
}
