package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-account asset allocation as four percentages (0–100) summing to 100. The wire/edit shape
 * for {@link CreateProjectionAccountRequest} and {@link ProjectionAccountResponse}; maps to the
 * entity's {@code Map<String,BigDecimal>} (keyed by {@link AssetClass#key()}) which
 * {@code AssetAllocation} normalizes downstream.
 */
public record AllocationDto(BigDecimal usStock, BigDecimal intlStock, BigDecimal bond, BigDecimal cash) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    /** Throws if any component is null/negative or the four do not sum to 100 (±0.01). */
    public void validate() {
        BigDecimal sum = BigDecimal.ZERO;
        for (var v : new BigDecimal[] {usStock, intlStock, bond, cash}) {
            if (v == null || v.signum() < 0) {
                throw new IllegalArgumentException("allocation percentages must be non-null and non-negative");
            }
            sum = sum.add(v);
        }
        if (sum.subtract(HUNDRED).abs().compareTo(TOLERANCE) > 0) {
            throw new IllegalArgumentException("allocation percentages must sum to 100 (got " + sum + ")");
        }
    }

    /** Percentages keyed by {@link AssetClass#key()}, dropping zero weights. */
    public Map<String, BigDecimal> toWeightMap() {
        var map = new LinkedHashMap<String, BigDecimal>();
        putIfPositive(map, AssetClass.US_STOCK, usStock);
        putIfPositive(map, AssetClass.INTL_STOCK, intlStock);
        putIfPositive(map, AssetClass.BOND, bond);
        putIfPositive(map, AssetClass.CASH, cash);
        return map;
    }

    private static void putIfPositive(Map<String, BigDecimal> map, AssetClass ac, BigDecimal v) {
        if (v != null && v.signum() > 0) {
            map.put(ac.key(), v);
        }
    }

    /** Normalized {@link AssetAllocation} (fractions summing to 1.0) → percentages. */
    public static AllocationDto fromAllocation(AssetAllocation allocation) {
        var w = allocation.weights();
        return new AllocationDto(pct(w, AssetClass.US_STOCK), pct(w, AssetClass.INTL_STOCK),
                pct(w, AssetClass.BOND), pct(w, AssetClass.CASH));
    }

    private static BigDecimal pct(Map<AssetClass, BigDecimal> w, AssetClass ac) {
        var frac = w.getOrDefault(ac, BigDecimal.ZERO);
        return frac.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
