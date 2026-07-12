package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.household.PersonId;

/**
 * Household task 5: fires the first-death transition and resolves a projection year's survivor-mode
 * inputs. Extracted from {@link DeterministicProjectionEngine} so the engine's per-year orchestration
 * stays lean (mirrors {@link RmdStreamCalculator}).
 *
 * <p>The transition (spousal rollover + taxable basis step-up + filing flip on the pools — see
 * {@link PoolStrategy#applyFirstDeathTransition}) fires exactly ONCE, in the transition year, AFTER
 * that year's growth and per-owner RMD force-out (so the decedent's year-of-death RMD is still taken)
 * and — because this runs before {@link YearFinanceResolver}'s Social Security convergence snapshot —
 * its pool mutations are captured by that snapshot, so the convergence re-runs restore to the
 * post-transition state and neither re-apply nor revert the transition (idempotent-once). From the
 * transition year forward, income switches to its survivor-phase form (Social Security keep-larger,
 * deceased-owned non-SS × survivor_percent — see {@link SurvivorIncomeAdjuster}) and spending scales
 * by the survivor factor. Single-person / both-alive years return the untouched inputs and a factor
 * of {@link BigDecimal#ONE} — the byte-identical anchor.
 */
final class HouseholdTransition {

    /** A projection year's survivor-mode income sources and spending factor. */
    record SurvivorYear(List<ProjectionIncomeSourceInput> incomeSources, BigDecimal spendingFactor) {}

    private HouseholdTransition() {}

    /**
     * Scales a plan-resolved spending amount by the survivor factor, short-circuiting to the
     * original value when the factor is exactly 1.0 so the pre-transition and single-person paths
     * stay byte-identical (no BigDecimal scale/precision drift from a redundant multiply).
     *
     * <p>Task 8 follow-up: the ONE deterministic-engine scaling rule, shared by
     * {@link RetirementWithdrawalProcessor} (the actual pool draw, transition step 4) and
     * {@link SpendingFeasibilityAnalyzer} (the essential/discretionary viability DISCLOSURES and the
     * feasibility verdict derived from them). The two seams resolve the same {@code SpendingPlan}
     * through independent calls, so they MUST scale identically — pre-fix the analyzer didn't scale
     * at all, reporting a phantom permanent shortfall for every post-transition year (T10 blocker).
     */
    static BigDecimal scaleBySurvivorFactor(BigDecimal amount, BigDecimal factor) {
        if (factor.compareTo(BigDecimal.ONE) == 0) {
            return amount;
        }
        return amount.multiply(factor).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    static SurvivorYear resolveYear(@Nullable HouseholdContext household, PoolStrategy pool, int year,
                                    List<ProjectionIncomeSourceInput> incomeSources, int baseYear,
                                    BigDecimal inflationRate, BigDecimal survivorSpendingFactor,
                                    boolean communityProperty) {
        if (household == null || household.transitionYear().isEmpty()
                || year < household.transitionYear().get()) {
            return new SurvivorYear(incomeSources, BigDecimal.ONE);
        }
        int transitionYear = household.transitionYear().get();
        if (transitionYear == year) {
            PersonId survivor = household.survivor();
            PersonId deceased = survivor == PersonId.PRIMARY ? PersonId.SPOUSE : PersonId.PRIMARY;
            pool.applyFirstDeathTransition(deceased, survivor, communityProperty);
        }
        var adjusted = SurvivorIncomeAdjuster.adjust(incomeSources, household, transitionYear,
                baseYear, inflationRate);
        return new SurvivorYear(adjusted, survivorSpendingFactor);
    }
}
