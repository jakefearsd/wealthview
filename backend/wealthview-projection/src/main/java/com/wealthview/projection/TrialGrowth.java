package com.wealthview.projection;

import com.wealthview.core.projection.dto.PoolType;

/**
 * Per-year pool growth for {@link TrialSimulator}, with and without a bond tent, including the tax on the
 * interest the taxable pool earns. Static, double-based hot-loop helpers.
 */
final class TrialGrowth {

    /** Bundles {@link TrialPools#growTaxable} and {@link TrialTaxes#applyInterestTax} (audit
     * C1) into a single call, keeping {@link TrialSimulator#simulateTrial}'s NCSS-capped hot method lean.
     * {@code baseWithInterest} is the ordinary-income base every later pricing call this
     * trial-year (RMD, conversion, spending draw) stacks on top of. */
    record GrowthYieldResult(double dividendIncome, double baseWithInterest) {}

    static GrowthYieldResult applyGrowthAndInterestTax(TrialPools tp,
            double taxableReturn, double dividendYield, double interestYield, double taxableEquityShare,
            OrdinaryTaxTable table, double base) {
        var split = tp.growTaxable(taxableReturn, dividendYield, interestYield, taxableEquityShare);
        TrialTaxes.applyInterestTax(tp, split.interestIncome(), table, base);
        return new GrowthYieldResult(split.dividendIncome(), base + split.interestIncome());
    }

    /** The per-year inputs of {@link #applyTentGrowth}: the tent year index, the three pool returns, and the
     * year's ordinary tax table and base income. */
    record TentGrowthYear(int tentYear, double taxableReturn, double traditionalReturn, double rothReturn,
                                  OrdinaryTaxTable table, double base) {
    }

    /** A pool's return once its tent sleeves earn their own: {@code grown / balance - 1}, or the raw
     * {@code poolReturn} when the pool is empty. */
    private static double effectiveReturn(TrialTent tent, PoolType pool, double balance, double poolReturn) {
        return balance == 0 ? poolReturn : tent.preparedGrowth(pool) / balance - 1;
    }

    /**
     * The tent's growth step: every pool grows at its effective return (growth part at the pool return,
     * sleeves at their own), the taxable pool through the reservation-aware split so the sleeves' typed
     * interest is taxed as ordinary income. Reads sleeve interest and the growth-index return from the
     * start-of-year sleeves and balances, then grows the sleeves and advances the index.
     */
    static GrowthYieldResult applyTentGrowth(TrialPools tp, TrialTent tent, SimulationConfig config,
            TentGrowthYear year) {
        double taxableReturn = year.taxableReturn();
        double traditionalReturn = year.traditionalReturn();
        double rothReturn = year.rothReturn();
        double base = year.base();
        double taxable = tp.taxable();
        double traditional = tp.traditionalTotal();
        double roth = tp.rothTotal();
        double growth = tent.growthReturn(taxable, traditional, roth, taxableReturn, traditionalReturn, rothReturn);
        var split = tp.growTaxable(effectiveReturn(tent, PoolType.TAXABLE, taxable, taxableReturn),
                config.dividendYield(), config.interestYield(), config.taxableEquityShare(),
                tent.totalIn(PoolType.TAXABLE), tent.interestIn(PoolType.TAXABLE));
        TrialTaxes.applyInterestTax(tp, split.interestIncome(), year.table(), base);
        tp.growNonTaxablePools(effectiveReturn(tent, PoolType.TRADITIONAL, traditional, traditionalReturn),
                effectiveReturn(tent, PoolType.ROTH, roth, rothReturn));
        tent.growSleeves(year.tentYear());
        tent.advanceIndex(growth);
        return new GrowthYieldResult(split.dividendIncome(), base + split.interestIncome());
    }

    private TrialGrowth() {
    }
}
