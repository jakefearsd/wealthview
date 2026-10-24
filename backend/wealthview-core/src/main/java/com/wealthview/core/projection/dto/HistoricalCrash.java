package com.wealthview.core.projection.dto;

import java.util.Arrays;
import java.util.Optional;

/**
 * The named historical crashes a retirement can be replayed against. {@link #firstYear()} and {@link #lastYear()}
 * bound the history years replayed; {@link #label()} is the display text.
 */
public enum HistoricalCrash {
    OIL_SHOCK_1973("oil_shock_1973", 1973, 1974, "1973–74"),
    DOT_COM_2000("dot_com_2000", 2000, 2002, "2000–02"),
    FINANCIAL_CRISIS_2008("financial_crisis_2008", 2008, 2009, "2007–09"),
    INFLATION_2022("inflation_2022", 2022, 2022, "2022");

    private final String key;
    private final int firstYear;
    private final int lastYear;
    private final String label;

    HistoricalCrash(String key, int firstYear, int lastYear, String label) {
        this.key = key;
        this.firstYear = firstYear;
        this.lastYear = lastYear;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public int firstYear() {
        return firstYear;
    }

    public int lastYear() {
        return lastYear;
    }

    public String label() {
        return label;
    }

    public static Optional<HistoricalCrash> fromKey(String key) {
        return Arrays.stream(values()).filter(c -> c.key.equals(key)).findFirst();
    }
}
