package com.wealthview.core.projection.dto;

import java.util.Map;

/**
 * The real returns a crash replay earns in each calendar year, with a long-run average for every year that falls
 * outside the historical window.
 *
 * @param byCalendarYear historical years only, keyed by the calendar year they are replayed into
 * @param longRun        the long-run average returns used for any calendar year not in {@code byCalendarYear}
 */
public record HistoricalReturnPath(Map<Integer, YearReturns> byCalendarYear, YearReturns longRun) {

    public HistoricalReturnPath {
        byCalendarYear = Map.copyOf(byCalendarYear);
    }

    /** The returns for {@code calendarYear}: its historical year when one is stored, otherwise {@link #longRun()}. */
    public YearReturns forYear(int calendarYear) {
        return byCalendarYear.getOrDefault(calendarYear, longRun);
    }
}
