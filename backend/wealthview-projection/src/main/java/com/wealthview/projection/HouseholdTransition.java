package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.GuardrailSpendingInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.SpendingPlan;
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

    /**
     * Task 8 follow-up #2: the survivor spending factor to actually APPLY when consuming
     * {@code plan}'s resolved spending — shared by {@link RetirementWithdrawalProcessor} (the draw)
     * and {@link SpendingFeasibilityAnalyzer} (the disclosures) so both seams resolve it identically.
     *
     * <p>A frozen guardrail/optimizer schedule ({@link GuardrailSpendingInput}) is ALREADY
     * survivor-scaled end-to-end at optimization time — task 6 pre-scales the essential floors at
     * the {@code OptimizationContextBuilder} choke point, task 8 scales the reported discretionary
     * schedule, and the income splice is household-aware — so the deterministic engine consumes it
     * at factor {@link BigDecimal#ONE}: re-applying the year factor would double-scale
     * (0.75&sup2; = 0.5625×). A schedule persisted BEFORE a household edit is the staleness
     * machinery's problem (the scenario signature covers every household field), not this seam's.
     *
     * <p>Tier-based plans and the plan-less withdrawal-rate strategies ({@code plan == null}) are
     * raw user spending with no survivor awareness — the year's factor applies unchanged
     * ({@code null} factor coalesces to ONE, the pre-household convention).
     */
    static BigDecimal effectiveSpendingFactor(@Nullable SpendingPlan plan, @Nullable BigDecimal factor) {
        if (factor == null || plan instanceof GuardrailSpendingInput) {
            return BigDecimal.ONE;
        }
        return factor;
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
