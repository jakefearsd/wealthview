package com.wealthview.projection;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.SpendingPlan;
import com.wealthview.core.projection.dto.TierBasedSpendingPlan;

/**
 * A spending plan held fixed: what it pays in each retirement year (real dollars) and, for a plan that stores a
 * Roth conversion schedule, what it converts. Both are indexed by years from retirement.
 */
record FixedSpendingSchedule(double[] spending, @Nullable double[] conversionByYear) {

    /**
     * Resolves {@code plan} for {@code years} retirement years starting at {@code retirementYear}. A tier plan is
     * scaled by {@code survivorFactor} from the household's first-death index, as the deterministic engine does; a
     * guardrail plan already carries that scaling, so it is read as stored. Only a guardrail plan carries
     * conversions; they are mapped from calendar year to year index.
     */
    // ShortMethodName: 'of' is the conventional JDK-style static factory method name.
    @SuppressWarnings("PMD.ShortMethodName")
    static FixedSpendingSchedule of(SpendingPlan plan, int retirementYear, int retirementAge, int years,
                                    BigDecimal inflationRate, @Nullable TrialSimulator.HouseholdSim household,
                                    double survivorFactor) {
        double[] spending = new double[years];
        for (int y = 0; y < years; y++) {
            spending[y] = plan.resolveYear(retirementYear + y, retirementAge + y, y + 1, inflationRate,
                    BigDecimal.ZERO).totalSpending().doubleValue();
        }
        if (plan instanceof TierBasedSpendingPlan) {
            HouseholdMcResolver.scaleFromTransition(spending, household, survivorFactor, years);
        }
        return new FixedSpendingSchedule(spending, conversionsOf(plan, retirementYear, years));
    }

    // ReturnEmptyCollectionRatherThanNull: null means "no conversion schedule", which downstream code (the pool
    // setup, the trial config) treats differently from an all-zero schedule.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    @Nullable
    private static double[] conversionsOf(SpendingPlan plan, int retirementYear, int years) {
        var schedule = plan.conversionSchedule();
        if (schedule.isEmpty()) {
            return null;
        }
        double[] conversions = new double[years];
        for (int y = 0; y < years; y++) {
            var amount = schedule.get().get(retirementYear + y);
            conversions[y] = amount == null ? 0.0 : amount.doubleValue();
        }
        return conversions;
    }
}
