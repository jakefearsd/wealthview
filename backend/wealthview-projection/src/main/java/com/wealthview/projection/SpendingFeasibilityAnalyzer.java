package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.common.CompoundGrowth;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.SpendingFeasibilitySummary;
import com.wealthview.core.projection.dto.SpendingPlan;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * Evaluates whether a spending plan is sustainable across the projected years and
 * annotates each retired year with viability detail. Extracted from
 * {@link DeterministicProjectionEngine} to isolate feasibility analysis.
 */
final class SpendingFeasibilityAnalyzer {

    private static final BigDecimal SHORTFALL_TOLERANCE = new BigDecimal("-10");

    /**
     * Walks the projected years and produces an overall feasibility summary for the
     * given spending plan, identifying the first shortfall year and the weakest
     * (inflation-adjusted) retirement year.
     */
    @Nullable
    SpendingFeasibilitySummary computeFeasibility(List<ProjectionYearDto> yearlyData,
                                                  @Nullable SpendingPlan spendingPlan,
                                                  BigDecimal inflationRate) {
        if (spendingPlan == null) {
            return null;
        }

        // If this is an optimizer-validated plan with a conversion schedule,
        // the MC optimizer already validated sustainability at the user's confidence level.
        // Re-validating with deterministic assumptions would produce contradictory results.
        if (spendingPlan.conversionSchedule().isPresent()) {
            return new SpendingFeasibilitySummary(true, null, null, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        Integer firstShortfallYear = null;
        Integer firstShortfallAge = null;
        BigDecimal minRealSurplus = null;
        BigDecimal sustainableForWeakest = null;
        BigDecimal requiredForWeakest = null;

        int retiredYearIndex = 0;
        for (var year : yearlyData) {
            if (!year.retired()) {
                continue;
            }
            retiredYearIndex++;

            if (year.spendingSurplus() != null && year.spendingSurplus().compareTo(SHORTFALL_TOLERANCE) < 0
                    && firstShortfallYear == null) {
                firstShortfallYear = year.year();
                firstShortfallAge = year.age();
            }

            BigDecimal availableNominal = year.withdrawals();
            if (year.incomeStreamsTotal() != null) {
                availableNominal = availableNominal.add(year.incomeStreamsTotal());
            }

            BigDecimal nominalRequired = BigDecimal.ZERO;
            if (year.essentialExpenses() != null) {
                nominalRequired = nominalRequired.add(year.essentialExpenses());
            }
            if (year.discretionaryExpenses() != null) {
                nominalRequired = nominalRequired.add(year.discretionaryExpenses());
            }
            if (year.taxLiability() != null) {
                nominalRequired = nominalRequired.add(year.taxLiability());
            }

            BigDecimal expenseInflationFactor = retiredYearIndex > 1
                    ? CompoundGrowth.factor(inflationRate, retiredYearIndex - 1)
                    : BigDecimal.ONE;

            BigDecimal realAvailable = expenseInflationFactor.compareTo(BigDecimal.ZERO) > 0
                    ? availableNominal.divide(expenseInflationFactor, SCALE, ROUNDING)
                    : availableNominal;

            BigDecimal realRequired = expenseInflationFactor.compareTo(BigDecimal.ZERO) > 0
                    ? nominalRequired.divide(expenseInflationFactor, SCALE, ROUNDING)
                    : nominalRequired;

            BigDecimal realSurplus = realAvailable.subtract(realRequired);

            if (minRealSurplus == null || realSurplus.compareTo(minRealSurplus) < 0) {
                minRealSurplus = realSurplus;
                sustainableForWeakest = realAvailable;
                requiredForWeakest = realRequired;
            }
        }

        if (sustainableForWeakest == null) {
            return new SpendingFeasibilitySummary(true, null, null, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        boolean feasible = firstShortfallYear == null;
        return new SpendingFeasibilitySummary(feasible, firstShortfallYear, firstShortfallAge,
                sustainableForWeakest, requiredForWeakest);
    }

    /**
     * Annotates a retired year's DTO with resolved essential/discretionary spending,
     * net need, surplus, and post-cut discretionary spending. Returns {@code base}
     * unchanged for working years or when no spending plan is set.
     */
    ProjectionYearDto applyViability(ProjectionYearDto base, @Nullable SpendingPlan spendingPlan,
                                     int year, int age, int yearsInRetirement,
                                     BigDecimal inflationRate, BigDecimal activeIncome) {
        if (spendingPlan == null || !base.retired()) {
            return base;
        }

        var resolved = spendingPlan.resolveYear(year, age, yearsInRetirement, inflationRate, activeIncome);
        BigDecimal essential = resolved.essential();
        BigDecimal discretionary = resolved.discretionary();

        BigDecimal taxBurden = base.taxLiability() != null ? base.taxLiability() : BigDecimal.ZERO;
        BigDecimal netNeed = essential.add(discretionary).subtract(activeIncome).max(BigDecimal.ZERO);
        BigDecimal totalAvailable = base.withdrawals().add(activeIncome);
        BigDecimal totalRequired = essential.add(discretionary).add(taxBurden);
        BigDecimal surplus = totalAvailable.subtract(totalRequired);

        BigDecimal discAfterCuts;
        if (surplus.compareTo(BigDecimal.ZERO) < 0) {
            discAfterCuts = discretionary.add(surplus).max(BigDecimal.ZERO);
        } else {
            discAfterCuts = discretionary;
        }

        return base.withViability(essential, discretionary, activeIncome, netNeed, surplus, discAfterCuts);
    }
}
