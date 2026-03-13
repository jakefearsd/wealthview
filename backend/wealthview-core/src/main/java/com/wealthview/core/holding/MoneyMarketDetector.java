package com.wealthview.core.holding;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

public final class MoneyMarketDetector {

    private static final Set<String> KNOWN_SYMBOLS = Set.of(
            "SPAXX", "FDRXX", "FZFXX", "VMFXX", "VMMXX",
            "SWVXX", "SNVXX", "SPRXX");

    private static final BigDecimal DEFAULT_RATE = new BigDecimal("4.0000");

    private MoneyMarketDetector() {
    }

    public static boolean isMoneyMarket(String symbol) {
        return symbol != null && KNOWN_SYMBOLS.contains(symbol.toUpperCase(Locale.US));
    }

    public static BigDecimal defaultRate() {
        return DEFAULT_RATE;
    }
}
