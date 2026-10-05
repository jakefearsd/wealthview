package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

/**
 * Per-year "tax space" (Phase 1a, spec §1.2): remaining room in each ordinary bracket and LTCG band,
 * the Social Security inclusion zone, NIIT headroom, IRMAA tier distance for the premium year this
 * year's MAGI sets, and the effective marginal rate of the next $1,000 of ordinary income / LTCG.
 * Social Security fields are null when there is no benefit; IRMAA fields are null unless someone is
 * Medicare-age in {@code year + 2}.
 */
public record TaxSpaceYear(
        int year, int age, BigDecimal magi,
        BigDecimal marginalOrdinaryRate, List<BracketRoom> bracketRoom,
        BigDecimal ltcgZeroRoom, BigDecimal ltcgFifteenRoom,
        @Nullable BigDecimal provisionalIncome, @Nullable BigDecimal ssBaseThreshold,
        @Nullable BigDecimal ssUpperThreshold, @Nullable BigDecimal ssInclusionRate,
        BigDecimal niitHeadroom,
        @Nullable Integer irmaaPremiumYear, @Nullable Integer irmaaTier,
        @Nullable BigDecimal irmaaRoomToNextTier, @Nullable BigDecimal irmaaNextTierAnnualCost,
        BigDecimal effectiveMarginalOrdinary, BigDecimal effectiveMarginalLtcg) {

    /** Gross-income room left before income crosses the top of the bracket taxed at {@code rate}. */
    public record BracketRoom(BigDecimal rate, BigDecimal grossCeiling, BigDecimal room) {
    }
}
