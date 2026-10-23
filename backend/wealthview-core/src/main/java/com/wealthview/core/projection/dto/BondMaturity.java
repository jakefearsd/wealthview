package com.wealthview.core.projection.dto;

/**
 * Constant-maturity Treasury buckets used by the bond tent (spec 4.3).
 *
 * <p>The declaration order is shortest-first, and the tent's draw order (spec 4.3 rule 3) relies on it.
 * {@link #ULTRASHORT} is never stored in {@code bond_maturity_returns}: its returns are the base return
 * matrix's {@link AssetClass#CASH} column, because the spec reuses the 3-month T-bill series for SGOV-like funds.
 */
public enum BondMaturity {
    ULTRASHORT("ultrashort"),
    SHORT("short"),
    INTERMEDIATE("intermediate"),
    LONG("long");

    private final String key;

    BondMaturity(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static BondMaturity fromKey(String key) {
        for (BondMaturity bm : values()) {
            if (bm.key.equals(key)) {
                return bm;
            }
        }
        throw new IllegalArgumentException("Unknown bond maturity key: " + key);
    }
}
