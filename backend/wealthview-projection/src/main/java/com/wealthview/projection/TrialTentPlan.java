package com.wealthview.projection;

import java.util.List;

import com.wealthview.core.projection.BondMaturityReturnsProvider.MaturityReturnMatrix;
import com.wealthview.core.projection.dto.PoolType;

/**
 * The scenario-level bond tent a Monte Carlo pass runs with. The {@code mixShares} and
 * {@code nominalYields} arrays are indexed by {@code BondMaturity.ordinal()}, shortest first. Engine-internal:
 * built once per pass and never mutated.
 */
record TrialTentPlan(double coverageYears, double sizingRealYield, double[] mixShares, double[] nominalYields,
                     List<PoolType> placementOrder, int yearsBeforeRetirement, int buildUpStartIndex,
                     double contributionCap, MaturityReturnMatrix maturities) {
}
