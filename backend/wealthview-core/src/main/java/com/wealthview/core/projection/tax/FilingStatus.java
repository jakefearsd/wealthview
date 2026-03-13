package com.wealthview.core.projection.tax;

import java.util.Locale;

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
        if (s == null) {
            return SINGLE;
        }
        if ("married_filing_jointly".equals(s.toLowerCase(Locale.US))) {
            return MARRIED_FILING_JOINTLY;
        }
        return SINGLE;
    }
}
