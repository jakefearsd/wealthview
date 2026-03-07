package com.wealthview.core.projection.strategy;

public enum WithdrawalOrder {
    TAXABLE_FIRST,
    TRADITIONAL_FIRST,
    ROTH_FIRST,
    PRO_RATA;

    public static WithdrawalOrder fromString(String value) {
        if (value == null) {
            return TAXABLE_FIRST;
        }
        return switch (value.toLowerCase()) {
            case "taxable_first" -> TAXABLE_FIRST;
            case "traditional_first" -> TRADITIONAL_FIRST;
            case "roth_first" -> ROTH_FIRST;
            case "pro_rata" -> PRO_RATA;
            default -> TAXABLE_FIRST;
        };
    }
}
