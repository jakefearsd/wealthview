package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import com.wealthview.core.projection.BondMaturityReturnsProvider.MaturityReturnMatrix;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentMix;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.strategy.WithdrawalOrder;

/**
 * The scenario-level bond tent a Monte Carlo pass runs with. The {@code mixShares} and
 * {@code nominalYields} arrays are indexed by {@code BondMaturity.ordinal()}, shortest first. Engine-internal:
 * built once per pass and never mutated.
 */
record TrialTentPlan(double coverageYears, double sizingRealYield, double[] mixShares, double[] nominalYields,
                     List<PoolType> placementOrder, int yearsBeforeRetirement, int buildUpStartIndex,
                     double contributionCap, MaturityReturnMatrix maturities) {

    /**
     * Resolves a scenario's tent for one pass. Missing (null) shares and yields read as 0; the build-up
     * start is clamped to today when the configured build-up reaches back before it.
     */
    // ShortMethodName: 'of' is the conventional JDK-style static factory method name.
    @SuppressWarnings("PMD.ShortMethodName")
    static TrialTentPlan of(BondTentConfig config, WithdrawalOrder order, double inflationRate,
                            int yearsBeforeRetirement, double contributionCap, MaturityReturnMatrix maturities) {
        var shares = config.planMix().shares();
        var yields = config.yields();
        int buildUp = config.buildUpStartYears() == null ? 0 : config.buildUpStartYears();
        return new TrialTentPlan(config.coverageYears().doubleValue(),
                BondTentSizer.mixRealYield(yields, shares, inflationRate),
                valuesOf(shares), valuesOf(yields), order.drawSequence(), yearsBeforeRetirement,
                Math.max(0, yearsBeforeRetirement - buildUp), contributionCap, maturities);
    }

    /** The same plan with {@code mix}'s shares and the real yield they earn at {@code yields}. */
    TrialTentPlan withMix(BondTentMix mix, BondMaturityValues yields, double inflationRate) {
        return new TrialTentPlan(coverageYears, BondTentSizer.mixRealYield(yields, mix.shares(), inflationRate),
                valuesOf(mix.shares()), valuesOf(yields), placementOrder, yearsBeforeRetirement,
                buildUpStartIndex, contributionCap, maturities);
    }

    /** Years from the build-up start to retirement. */
    int buildUpSpan() {
        return yearsBeforeRetirement - buildUpStartIndex;
    }

    /** The straight build-up line: 0 before the start, the full {@code target} at retirement. */
    double lineLevel(int yearFromToday, double target) {
        if (yearFromToday < buildUpStartIndex) {
            return 0;
        }
        int span = buildUpSpan();
        if (span == 0 || yearFromToday >= yearsBeforeRetirement) {
            return target;
        }
        return target * (yearFromToday - buildUpStartIndex) / span;
    }

    private static double[] valuesOf(BondMaturityValues values) {
        double[] out = new double[BondMaturity.values().length];
        for (var maturity : BondMaturity.values()) {
            BigDecimal value = values.get(maturity);
            out[maturity.ordinal()] = value == null ? 0.0 : value.doubleValue();
        }
        return out;
    }
}
