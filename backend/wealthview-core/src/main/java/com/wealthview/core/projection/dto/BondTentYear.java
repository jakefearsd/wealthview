package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

/**
 * The tent in one projected year, after that year's growth and withdrawals: the balance per maturity and in total,
 * and the build-up line's target for the year ({@code null} outside the build-up window).
 */
public record BondTentYear(
        int year,
        BondMaturityValues balances,
        BigDecimal total,
        @Nullable BigDecimal buildUpTarget) {
}
