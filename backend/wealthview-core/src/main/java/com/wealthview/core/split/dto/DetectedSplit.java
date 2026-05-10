package com.wealthview.core.split.dto;

import java.time.LocalDate;

/**
 * A split detected by an external feed (Finnhub/Yahoo).
 * {@code numerator}:{@code denominator} represents new-shares-per-old-shares,
 * e.g. 4:1 means a holder of 100 shares now has 400.
 */
public record DetectedSplit(String symbol, LocalDate date, int numerator, int denominator) {

    public DetectedSplit {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("split ratio must be positive: " + numerator + ":" + denominator);
        }
    }
}
