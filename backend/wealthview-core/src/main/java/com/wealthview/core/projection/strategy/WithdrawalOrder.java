package com.wealthview.core.projection.strategy;

import java.util.Locale;

public enum WithdrawalOrder {
    TAXABLE_FIRST,
    TRADITIONAL_FIRST,
    ROTH_FIRST,
    PRO_RATA,
    DYNAMIC_SEQUENCING;

    public static WithdrawalOrder fromString(String value) {
        if (value == null) {
            return TAXABLE_FIRST;
        }
        return switch (value.toLowerCase(Locale.US)) {
            case "taxable_first" -> TAXABLE_FIRST;
            case "traditional_first" -> TRADITIONAL_FIRST;
            case "roth_first" -> ROTH_FIRST;
            case "pro_rata" -> PRO_RATA;
            case "dynamic_sequencing" -> DYNAMIC_SEQUENCING;
            default -> TAXABLE_FIRST;
        };
    }
}
