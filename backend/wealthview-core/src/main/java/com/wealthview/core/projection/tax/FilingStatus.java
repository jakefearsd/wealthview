package com.wealthview.core.projection.tax;

public enum FilingStatus {
    SINGLE("single"),
    MARRIED_FILING_JOINTLY("married_filing_jointly");

    private final String value;

    FilingStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static FilingStatus fromString(String s) {
        if (s == null) return SINGLE;
        return switch (s.toLowerCase()) {
            case "married_filing_jointly" -> MARRIED_FILING_JOINTLY;
            default -> SINGLE;
        };
    }
}
