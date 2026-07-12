package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.SpendingFeasibilitySummary;
import com.wealthview.core.projection.dto.SpendingPlan;

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
     * retirement year. The projection runs in real (today's-dollars) terms, so
     * withdrawals, income and spending are already real and are compared directly —
     * no per-year inflation deflation is applied.
     */
    // NPathComplexity: the per-year walk applies several independent shortfall/weak-year checks.
    // The path count multiplies across those checks but each is a simple comparison-and-record,
    // so the method reads sequentially despite the large NPath number.
    @SuppressWarnings("PMD.NPathComplexity")
    @Nullable
    SpendingFeasibilitySummary computeFeasibility(List<ProjectionYearDto> yearlyData,
                                                  @Nullable SpendingPlan spendingPlan) {
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

        for (var year : yearlyData) {
            if (!year.retired()) {
                continue;
            }

            if (year.spendingSurplus() != null && year.spendingSurplus().compareTo(SHORTFALL_TOLERANCE) < 0
                    && firstShortfallYear == null) {
                firstShortfallYear = year.year();
                firstShortfallAge = year.age();
            }

            BigDecimal realAvailable = year.withdrawals();
            if (year.incomeStreamsTotal() != null) {
                realAvailable = realAvailable.add(year.incomeStreamsTotal());
            }

            BigDecimal realRequired = BigDecimal.ZERO;
            if (year.essentialExpenses() != null) {
                realRequired = realRequired.add(year.essentialExpenses());
            }
            if (year.discretionaryExpenses() != null) {
                realRequired = realRequired.add(year.discretionaryExpenses());
            }
            if (year.taxLiability() != null) {
                realRequired = realRequired.add(year.taxLiability());
            }

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
     *
     * <p>Task 8 follow-up (T10 blocker): {@code survivorSpendingFactor} is the household's per-year
     * survivor factor (1.0 for single-person runs and every pre-transition year), applied to the
     * plan-resolved essential/discretionary via the SAME shared
     * {@link HouseholdTransition#scaleBySurvivorFactor} rule
     * {@link RetirementWithdrawalProcessor} scales the actual pool draw with. Every derived field
     * ({@code netSpendingNeed}/{@code spendingSurplus}/{@code discretionaryAfterCuts}) then follows
     * coherently, and {@link #computeFeasibility} — which reads these DTO fields back — compares the
     * scaled requirement against the scaled withdrawal instead of flagging a phantom permanent
     * shortfall from the transition year forward.
     */
    ProjectionYearDto applyViability(ProjectionYearDto base, @Nullable SpendingPlan spendingPlan,
                                     int year, int age, int yearsInRetirement,
                                     BigDecimal inflationRate, BigDecimal activeIncome,
                                     BigDecimal survivorSpendingFactor) {
        if (spendingPlan == null || !base.retired()) {
            return base;
        }

        var resolved = spendingPlan.resolveYear(year, age, yearsInRetirement, inflationRate, activeIncome);
        // Task 8 follow-up #2: the effective factor is resolved by the SAME shared rule the draw
        // seam uses (HouseholdTransition.effectiveSpendingFactor) — notably a frozen guardrail
        // schedule is already survivor-scaled by the optimizer and is consumed at factor 1.0 here
        // too, so these disclosures never double-scale what the schedule already reduced.
        BigDecimal factor = HouseholdTransition.effectiveSpendingFactor(spendingPlan, survivorSpendingFactor);
        BigDecimal essential = HouseholdTransition.scaleBySurvivorFactor(resolved.essential(), factor);
        BigDecimal discretionary = HouseholdTransition.scaleBySurvivorFactor(resolved.discretionary(), factor);

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
