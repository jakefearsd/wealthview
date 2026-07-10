package com.wealthview.core.projection.dto;

public enum AssetClass {
    US_STOCK("us_stock"),
    INTL_STOCK("intl_stock"),
    BOND("bond"),
    CASH("cash");

    private final String key;

    AssetClass(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static AssetClass fromKey(String key) {
        for (AssetClass ac : values()) {
            if (ac.key.equals(key)) {
                return ac;
            }
        }
        throw new IllegalArgumentException("Unknown asset class key: " + key);
    }
}
