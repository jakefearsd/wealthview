package com.wealthview.core.price;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import com.wealthview.persistence.entity.PriceEntity;

/**
 * Shared date-grid and price-lookup plumbing for the portfolio history services
 * ({@code TheoreticalPortfolioService}, {@code CombinedPortfolioHistoryService}), which sample
 * values on the same weekly-Friday grid and resolve prices with the same carry-forward
 * (floorEntry) semantics. Their valuation logic intentionally differs (all-or-nothing pricing vs
 * skip-unpriced-symbols); only this scaffolding is common.
 */
public final class PriceHistorySupport {

    private PriceHistorySupport() {
    }

    /**
     * Every Friday in {@code [start, end]}, with {@code end} itself appended when it is not
     * already the last Friday — so a history chart always extends to the current date.
     */
    public static List<LocalDate> weeklyFridaysWithEndDate(LocalDate start, LocalDate end) {
        var dates = new ArrayList<LocalDate>();
        var friday = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        while (!friday.isAfter(end)) {
            dates.add(friday);
            friday = friday.plusWeeks(1);
        }
        if (dates.isEmpty() || !dates.getLast().equals(end)) {
            dates.add(end);
        }
        return dates;
    }

    /**
     * Groups price rows into symbol → (date → close) with {@link NavigableMap} date lookup, so
     * callers resolve a valuation date's price via {@code floorEntry} (latest price at or before
     * the date — carry-forward across weekends/gaps).
     */
    public static Map<String, NavigableMap<LocalDate, BigDecimal>> buildPriceMap(List<PriceEntity> prices) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> priceMap = new HashMap<>();
        for (var price : prices) {
            priceMap.computeIfAbsent(price.getSymbol(), k -> new TreeMap<>())
                    .put(price.getDate(), price.getClosePrice());
        }
        return priceMap;
    }
}
