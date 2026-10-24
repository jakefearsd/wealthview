package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

/**
 * One column of the fixed-spending simulation: the plan run with no tent or with one mix. The years-covered fields
 * are {@code null} for the no-tent column; {@code balanceAt90Median} is {@code null} when the horizon ends first.
 */
public record BondTentSimulationColumn(
        String name,
        @Nullable BigDecimal tentTarget,
        BigDecimal successRate,
        @Nullable BigDecimal yearsCoveredMedian,
        @Nullable BigDecimal yearsCoveredWorst10,
        @Nullable BigDecimal balanceAt90Median) {
}
