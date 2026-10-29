package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.PoolType;

/**
 * The bond-tent wiring for {@link MultiPool}: owns the {@link TentReservation} and its
 * setup, and applies them to the pools' growth and clamping. Every entry point is inert (touches no
 * pool, allocates nothing) until a tent is configured and holds a reservation, so a scenario without
 * a tent runs exactly the pre-tent code paths.
 *
 * <p>The reservation is clamped lazily: a debit never touches it, so the clamp must run before
 * anything that could hide a debit by crediting the pool (contributions, reinvested surplus, a Roth
 * conversion, RMD proceeds) and before growth. That keeps spent sleeves spent -- never refilled. A
 * transfer in kind (a conversion or an RMD) clamps before its debit and again after its credit, so the
 * sleeves a debit displaces can migrate into the dollars the credit lands.
 */
final class TentPools {

    private final TaxableLotsBd lots;
    private final OwnerPool traditional;
    private final OwnerPool roth;
    private TentReservation tent = TentReservation.empty();
    private PoolStrategy.TentSetup setup;
    /** The pools' current growth returns: the per-pool rates the pools grow at, replaced each replay year. */
    private PoolStrategy.PoolReturns returns;

    TentPools(TaxableLotsBd lots, OwnerPool traditional, OwnerPool roth, PoolStrategy.PoolReturns returns) {
        this.returns = returns;
        this.lots = lots;
        this.traditional = traditional;
        this.roth = roth;
    }

    void configure(PoolStrategy.TentSetup newSetup) {
        this.setup = newSetup;
        this.tent = TentReservation.empty();
    }

    /** Limits each pool's reservation to its balance; a no-op without a reservation. */
    void clamp() {
        if (!tent.isEmpty()) {
            tent.clampAll(this::balanceOf, setup.placementOrder());
        }
    }

    boolean holdsIn(PoolType pool) {
        return tent.totalIn(pool).signum() > 0;
    }

    TentReservation snapshot() {
        return tent.copy();
    }

    void restore(TentReservation saved) {
        this.tent = saved.copy();
    }

    void topUp(BigDecimal target) {
        topUp(target, null);
    }

    /** Reserves up to {@code target} but at most {@code cap} more; a null cap is uncapped. */
    void topUp(BigDecimal target, @Nullable BigDecimal cap) {
        if (setup != null) {
            clamp();
            tent.topUp(target, cap, setup.placementOrder(), setup.mixShares(), this::balanceOf);
        }
    }

    /** The pools' current growth returns; {@link MultiPool} grows its pools at these. */
    PoolStrategy.PoolReturns returns() {
        return returns;
    }

    /** Records the pools' new growth returns and re-points the sleeves at {@code sleeveReturns}. */
    void useYearReturns(PoolStrategy.PoolReturns newReturns, Map<BondMaturity, BigDecimal> sleeveReturns) {
        this.returns = newReturns;
        useSleeveReturns(sleeveReturns);
    }

    /** Replaces the sleeves' returns (already net of fee); does nothing without a setup. */
    void useSleeveReturns(Map<BondMaturity, BigDecimal> sleeveReturns) {
        if (setup != null) {
            setup = new PoolStrategy.TentSetup(setup.placementOrder(), setup.mixShares(), sleeveReturns,
                    setup.nominalYields());
        }
    }

    /** Spends {@code min(amount, tent)}, shortest maturity first; returns what was spent (0 without a tent). */
    BigDecimal spend(BigDecimal amount) {
        if (setup == null) {
            return BigDecimal.ZERO;
        }
        clamp();
        return tent.spend(amount, setup.placementOrder());
    }

    /**
     * The balance-weighted return of the unreserved (growth) part, {@code sum (V-T) r / sum (V-T)}, from
     * current balances and reservations; zero when no growth part is left.
     */
    BigDecimal growthPartReturn() {
        return growthPartReturn(returns.taxable(), returns.traditional(), returns.roth());
    }

    BigDecimal growthPartReturn(BigDecimal taxableReturn, BigDecimal traditionalReturn, BigDecimal rothReturn) {
        clamp();
        BigDecimal taxable = growthPart(PoolType.TAXABLE);
        BigDecimal trad = growthPart(PoolType.TRADITIONAL);
        BigDecimal rothPart = growthPart(PoolType.ROTH);
        BigDecimal base = taxable.add(trad).add(rothPart);
        if (base.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal growth = taxable.multiply(taxableReturn).add(trad.multiply(traditionalReturn))
                .add(rothPart.multiply(rothReturn));
        return growth.divide(base, PoolStrategy.SCALE + 4, PoolStrategy.ROUNDING);
    }

    private BigDecimal growthPart(PoolType pool) {
        return balanceOf(pool).subtract(tent.totalIn(pool)).max(BigDecimal.ZERO);
    }

    BigDecimal total() {
        clamp();
        return tent.total();
    }

    Map<BondMaturity, BigDecimal> byMaturity() {
        clamp();
        return setup == null ? Map.of() : tent.byMaturity();
    }

    /**
     * Grows a traditional or Roth pool: with a reservation, every owner slice scales by the pool's
     * blended rate; otherwise the plain {@link OwnerPool#grow}.
     */
    BigDecimal growOwnerPool(OwnerPool pool, PoolType type, BigDecimal poolReturn) {
        if (!holdsIn(type)) {
            return pool.grow(poolReturn);
        }
        BigDecimal before = pool.total();
        BigDecimal after = tent.balanceAfterGrowth(type, before, poolReturn, setup.realReturns());
        return pool.grow(TentReservation.effectiveRate(before, after));
    }

    /**
     * Taxable growth with a reservation: the pool reaches {@code (V-T)(1+r) + sum T_m(1+r_m)}. A
     * retired year distributes the audit-C1 split over the unreserved part only, plus the sleeves'
     * typed nominal interest (reinvested at cost); an accumulation year books nothing (audit C8).
     * Either way the pool total lands exactly on the blended target.
     */
    Optional<TentReservation.TaxableYear> growTaxable(boolean retired, BigDecimal poolReturn,
                                                      BigDecimal equityShare, BigDecimal dividendYield,
                                                      BigDecimal interestYield) {
        if (!holdsIn(PoolType.TAXABLE)) {
            return Optional.empty();
        }
        BigDecimal before = lots.totalValue();
        var year = tent.taxableYear(before, poolReturn, retired, equityShare.multiply(dividendYield),
                BigDecimal.ONE.subtract(equityShare).multiply(interestYield),
                setup.realReturns(), setup.nominalYields());
        BigDecimal appreciated = year.target().subtract(year.distribution());
        lots.growToExactTotal(TentReservation.effectiveRate(before, appreciated), appreciated);
        lots.addLot(year.distribution());   // zero (accumulation year) is a no-op
        return Optional.of(year);
    }

    private BigDecimal balanceOf(PoolType pool) {
        return switch (pool) {
            case TAXABLE -> lots.totalValue();
            case TRADITIONAL -> traditional.total();
            case ROTH -> roth.total();
        };
    }
}
