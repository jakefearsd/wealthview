package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.RentalLossCalculator;

/**
 * Computes per-year rental taxable-income adjustments for the Roth conversion
 * optimizer, applying passive-loss rules with MAGI that includes conversion income.
 *
 * <p>For REPS/active properties, MAGI doesn't affect the result — all losses
 * are fully deductible. For passive properties, high MAGI (from conversions)
 * phases out the $25K exception, reducing the deductible loss.
 *
 * <p>Package-private collaborator extracted from {@link RothConversionOptimizer}.
 */
final class RentalAdjustmentCalculator {

    private final List<ProjectionIncomeSourceInput> rentalSources;
    private final RentalLossCalculator rentalLossCalculator;
    private final int birthYear;
    private final int retirementAge;

    RentalAdjustmentCalculator(List<ProjectionIncomeSourceInput> incomeSources,
                               RentalLossCalculator rentalLossCalculator,
                               int birthYear, int retirementAge) {
        this.rentalSources = incomeSources != null
                ? incomeSources.stream()
                        .filter(s -> s.incomeType() == IncomeSourceType.RENTAL_PROPERTY)
                        .toList()
                : List.of();
        this.rentalLossCalculator = rentalLossCalculator;
        this.birthYear = birthYear;
        this.retirementAge = retirementAge;
    }

    /** True when there are no rental sources to account for. */
    boolean isEmpty() {
        return rentalSources.isEmpty();
    }

    /** A fresh per-source suspended-loss map seeded with zero carryforward. */
    Map<ProjectionIncomeSourceInput, BigDecimal> initSuspendedLosses() {
        var map = new HashMap<ProjectionIncomeSourceInput, BigDecimal>();
        for (var source : rentalSources) {
            map.put(source, BigDecimal.ZERO);
        }
        return map;
    }

    /**
     * Computes the rental taxable income adjustment for a single year using
     * {@link RentalLossCalculator} with MAGI that includes the conversion amount.
     *
     * @param yearIndex year within the simulation
     * @param magi      modified adjusted gross income INCLUDING conversion amount
     * @param suspended per-source suspended loss map (mutated with new values)
     * @return net rental taxable income adjustment (negative = loss offsetting income)
     */
    double adjustmentForYear(int yearIndex, double magi,
                             Map<ProjectionIncomeSourceInput, BigDecimal> suspended) {
        if (rentalSources.isEmpty() || rentalLossCalculator == null) {
            return 0;
        }

        int age = retirementAge + yearIndex;
        int calendarYear = birthYear + age;
        double yearAdjustment = 0;

        for (var source : rentalSources) {
            if (!ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                continue;
            }
            var rentalResult = RentalIncomeHelper.computeForSource(
                    source, yearIndex, calendarYear, magi,
                    suspended.getOrDefault(source, BigDecimal.ZERO),
                    rentalLossCalculator);
            suspended.put(source, rentalResult.newSuspendedLoss());
            yearAdjustment += rentalResult.netTaxableIncome();
        }

        return yearAdjustment;
    }
}
