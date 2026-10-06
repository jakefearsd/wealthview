package com.wealthview.core.common;

import java.util.Locale;

import org.springframework.lang.Nullable;

/**
 * Canonical form for security ticker symbols. Symbols are matched by exact string equality
 * against prices and holdings, so {@code "aapl"} and {@code " AAPL"} would otherwise create
 * phantom positions that never price.
 */
public final class Symbols {

    private Symbols() {
    }

    /**
     * Trims and upper-cases a symbol; a null or blank symbol normalises to {@code null}.
     */
    @Nullable
    public static String normalize(@Nullable String symbol) {
        if (symbol == null) {
            return null;
        }
        var trimmed = symbol.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
