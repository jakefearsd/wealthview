package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

/**
 * One column of a crash replay: the scenario without a tent ({@code name} "No tent", null tent fields) or with one
 * mix's tent.
 *
 * @param tentTarget          the mix's tent size, or {@code null} without a tent
 * @param lowestBalance       the lowest end balance over the retired years
 * @param stocksSoldWhileDown the total sold from the growth part in retired years below the stock high
 * @param yearsCovered        retirement years the tent paid for plus the years' worth left in it
 * @param tentLeftAtCheckpoint the tent at the end of the year the primary turns 70 (or of the covered window if
 *                            later); {@code null} when the horizon ends first
 * @param balanceAt90         the balance entering age 90, or {@code null} when the horizon ends first
 */
public record BondTentReplayOutcome(
        String name,
        @Nullable BigDecimal tentTarget,
        BigDecimal lowestBalance,
        BigDecimal stocksSoldWhileDown,
        @Nullable BigDecimal yearsCovered,
        @Nullable BigDecimal tentLeftAtCheckpoint,
        @Nullable BigDecimal balanceAt90,
        List<BondTentReplayYear> years) {
}
