package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.BondTentReplayYear;
import com.wealthview.core.projection.dto.HistoricalReturnPath;
import com.wealthview.core.projection.dto.ProjectionAccountInput;

/**
 * One replay pass: feeds each projected year's historical returns to the pools, tracks the growth index that
 * decides whether the stock market is below its high, spends the tent on demand, and records a row per year.
 * The static hooks are no-ops for a {@code null} replayer, so the main projection never touches this class.
 */
final class CrashReplayer {

    private static final int MONEY_SCALE = 2;
    private static final int INDEX_SCALE = 12;

    private final HistoricalReturnPath path;
    private final List<ProjectionAccountInput> accounts;
    private final BigDecimal inflationRate;
    private final BigDecimal feeRate;
    private final List<BondTentReplayYear> years = new ArrayList<>();
    private BigDecimal index = BigDecimal.ONE;
    private BigDecimal peak = BigDecimal.ONE;
    private BigDecimal growthReturn = BigDecimal.ZERO;
    private BigDecimal totalAfterGrowth = BigDecimal.ZERO;
    private BigDecimal tentAfterGrowth = BigDecimal.ZERO;
    @Nullable
    private Integer historyYear;

    CrashReplayer(HistoricalReturnPath path, List<ProjectionAccountInput> accounts, BigDecimal inflationRate,
                  BigDecimal feeRate) {
        this.path = path;
        this.accounts = accounts;
        this.inflationRate = inflationRate;
        this.feeRate = feeRate;
    }

    /** Whether the growth index sits below its running high, as of the last year grown. */
    boolean isBelowPeak() {
        return index.compareTo(peak) < 0;
    }

    List<BondTentReplayYear> years() {
        return List.copyOf(years);
    }

    /** Points the pools at {@code year}'s returns and remembers the growth part's return; call before growth. */
    static void beforeGrowth(@Nullable CrashReplayer replayer, PoolStrategy pool, int year) {
        if (replayer != null) {
            replayer.useReturnsOf(pool, year);
        }
    }

    /** Advances the growth index and remembers the totals just after growth; call right after growth. */
    static void afterGrowth(@Nullable CrashReplayer replayer, PoolStrategy pool) {
        if (replayer != null) {
            replayer.advance(pool);
        }
    }

    /** Spends the tent on a down year's net outflow and appends the year's row; call once the year is done. */
    static void settle(@Nullable CrashReplayer replayer, PoolStrategy pool, int year, int age, boolean retired) {
        if (replayer != null) {
            replayer.record(pool, year, age, retired);
        }
    }

    private void useReturnsOf(PoolStrategy pool, int year) {
        var yearReturns = path.forYear(year);
        historyYear = yearReturns.historyYear();
        var sleeves = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        for (var maturity : BondMaturity.values()) {
            // Sleeves earn the maturity's return for the year net of the fee, as the main projection's do.
            sleeves.put(maturity, BigDecimal.valueOf(yearReturns.maturities().getOrDefault(maturity, 0.0))
                    .subtract(feeRate));
        }
        pool.useYearReturns(PoolReturnBlender.poolReturnsFor(accounts, yearReturns.assets(), inflationRate, feeRate),
                sleeves);
        growthReturn = pool.growthPartReturn();
    }

    private void advance(PoolStrategy pool) {
        index = index.multiply(BigDecimal.ONE.add(growthReturn)).setScale(INDEX_SCALE, RoundingMode.HALF_UP);
        peak = peak.max(index);
        totalAfterGrowth = pool.getTotal();
        tentAfterGrowth = pool.tentTotal();
    }

    private void record(PoolStrategy pool, int year, int age, boolean retired) {
        BigDecimal outflow = totalAfterGrowth.subtract(pool.getTotal()).max(BigDecimal.ZERO);
        boolean below = isBelowPeak();
        boolean spendsTent = retired && below;
        if (spendsTent) {
            // A debit that went past a pool's growth part already came out of the tent; spend only the rest.
            BigDecimal alreadyDrawn = tentAfterGrowth.subtract(pool.tentTotal()).max(BigDecimal.ZERO);
            pool.spendTent(outflow.subtract(alreadyDrawn).max(BigDecimal.ZERO));
        }
        BigDecimal tentNow = pool.tentTotal();
        BigDecimal drawn = tentAfterGrowth.subtract(tentNow).max(BigDecimal.ZERO);
        BigDecimal sold = spendsTent ? outflow.subtract(drawn).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        years.add(new BondTentReplayYear(year, age, money(pool.getTotal()), money(tentNow), money(drawn),
                money(sold), below, historyYear));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
