package com.wealthview.core.common;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

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

    /**
     * Looks a symbol up exactly as given and, only when that finds nothing and the normalised form
     * differs, again by its {@link #normalize normalised} form. Writes store the normalised form,
     * but rows written before that (for example {@code "vti"} or {@code " AAPL"}) keep the symbol
     * as it was typed and are never rewritten, so a lookup must keep finding them by their stored
     * spelling while a loosely typed query still finds the normalised rows.
     *
     * @param lookup  the query to run for one spelling of the symbol
     * @param isFound whether a query result counts as a match
     */
    public static <T> T lookUpExactThenNormalized(@Nullable String symbol, Function<String, T> lookup,
                                                  Predicate<T> isFound) {
        T exact = lookup.apply(symbol);
        var normalized = normalize(symbol);
        if (normalized == null || normalized.equals(symbol) || isFound.test(exact)) {
            return exact;
        }
        return lookup.apply(normalized);
    }
}
