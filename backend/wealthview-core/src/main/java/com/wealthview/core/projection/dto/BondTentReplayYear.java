package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

/**
 * One replayed year: the end-of-year portfolio and tent balances, what the tent paid out, what had to be sold from
 * the growth part while the stock index sat below its high, and the historical year the returns came from.
 *
 * @param stocksBelowHigh whether the growth index ended the year below its running high
 * @param historyYear     the historical year replayed, or {@code null} for a long-run average year
 */
public record BondTentReplayYear(
        int year,
        int age,
        BigDecimal balance,
        BigDecimal tentBalance,
        BigDecimal tentDrawn,
        BigDecimal stocksSoldWhileDown,
        boolean stocksBelowHigh,
        @Nullable Integer historyYear) {
}
