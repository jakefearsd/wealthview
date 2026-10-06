package com.wealthview.core.projection.tax;

import java.util.Locale;

import org.springframework.lang.Nullable;

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

    /**
     * Resolves a scenario's effective filing status. An explicit value always wins; an unset
     * (null/blank) one follows the household: {@link #MARRIED_FILING_JOINTLY} when the scenario has
     * a spouse ({@code spouseBirthYear} present), else {@link #SINGLE}. Every engine and the
     * guardrail staleness signature resolve through here, so a married household that never opened
     * the filing-status control (and saved scenarios from before it was always sent) is not taxed
     * with single brackets and deduction.
     */
    public static FilingStatus resolve(@Nullable String filingStatus, @Nullable Integer spouseBirthYear) {
        if (filingStatus == null || filingStatus.isBlank()) {
            return spouseBirthYear != null ? MARRIED_FILING_JOINTLY : SINGLE;
        }
        return fromString(filingStatus);
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
