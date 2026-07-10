package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

public record AssetAllocation(Map<AssetClass, BigDecimal> weights) {

    private static final int SCALE = 6;

    public AssetAllocation {
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("AssetAllocation requires at least one weight");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (var e : weights.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() < 0) {
                throw new IllegalArgumentException("Negative or null weight for " + e.getKey());
            }
            sum = sum.add(e.getValue());
        }
        if (sum.signum() == 0) {
            throw new IllegalArgumentException("AssetAllocation weights sum to zero");
        }
        var normalized = new EnumMap<AssetClass, BigDecimal>(AssetClass.class);
        for (var e : weights.entrySet()) {
            normalized.put(e.getKey(), e.getValue().divide(sum, SCALE, RoundingMode.HALF_UP));
        }
        weights = Map.copyOf(normalized);
    }

    public double blend(Map<AssetClass, Double> perClassReturn) {
        double total = 0.0;
        for (var e : weights.entrySet()) {
            Double r = perClassReturn.get(e.getKey());
            if (r != null) {
                total += e.getValue().doubleValue() * r;
            }
        }
        return total;
    }

    public static AssetAllocation fromDoubles(Map<AssetClass, Double> weights) {
        var bd = new EnumMap<AssetClass, BigDecimal>(AssetClass.class);
        weights.forEach((k, v) -> bd.put(k, BigDecimal.valueOf(v)));
        return new AssetAllocation(bd);
    }

    public static final AssetAllocation ALL_US =
            fromDoubles(Map.of(AssetClass.US_STOCK, 1.0));
}
