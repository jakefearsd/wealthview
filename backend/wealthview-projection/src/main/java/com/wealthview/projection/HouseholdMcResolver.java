package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.household.LifeExpectancy;
import com.wealthview.core.projection.household.PersonId;
import com.wealthview.core.projection.tax.FilingStatus;

/**
 * Household task 6: resolves the Monte Carlo first-death transition inputs from a
 * {@link GuardrailOptimizationInput}, mirroring the deterministic engine's {@code HouseholdContext} +
 * {@code PoolStrategy.applyFirstDeathTransition} economics in the MC's retirement-anchored,
 * double-precision frame.
 *
 * <p>A single-person input ({@code spouseBirthYear == null}) resolves to {@link #single()} — a
 * {@code null} {@link TrialSimulator.HouseholdSim}, factor 1.0, no in-window transition — so the
 * builder takes its pre-household path bit-for-bit (the byte-identical anchor). A two-person input
 * always produces a {@link TrialSimulator.HouseholdSim} (owner-split pools + two RMD streams run
 * whether or not the first death falls inside the horizon); the income/tax-status/floor transforms
 * ({@link #inWindowTransitionIdx()} {@code >= 0}) apply only when the first death lands inside the
 * modeled retirement window.
 */
final class HouseholdMcResolver {

    /** Spec §4 default survivor spending factor when a household omits it. */
    private static final double DEFAULT_SURVIVOR_SPENDING_FACTOR = 0.75;

    // HP3 Part B-2: ScenarioCrudService.validateSurvivorSpendingFactor enforces this SAME [0.5, 1.0]
    // range at write time, but a directly-constructed GuardrailOptimizationInput (tests, a future
    // write path, a stale persisted value) bypasses that check entirely -- clamp defensively here
    // too so an out-of-range value can never reach the trial simulator.
    private static final double MIN_SURVIVOR_SPENDING_FACTOR = 0.5;
    private static final double MAX_SURVIVOR_SPENDING_FACTOR = 1.0;

    /**
     * The resolved Monte Carlo household transition inputs for one optimization run.
     *
     * @param sim the per-trial transition params for {@link TrialSimulator} ({@code null} ⇒ single)
     * @param survivorFactor floors/discretionary scaling from the transition year (1.0 ⇒ none)
     * @param inWindowTransitionIdx the MC year index of the first death when it lands inside the
     *        modeled window (drives income/tax-status/floor splicing); {@code -1} otherwise
     * @param survivorSources the survivor-phase income sources (SS keep-larger + survivor_percent);
     *        {@code null} unless there is an in-window transition
     * @param postStatus the filing status from the transition year (SINGLE) — {@code preStatus} when
     *        there is no in-window transition
     * @param context household task 7 (spec §4 step 6): the resolved two-person
     *        {@link HouseholdContext}, threaded into the per-year exact tax tables
     *        ({@link OptimizationContextBuilder#ordinaryTablesByYear}/{@code ltcgTablesByYear}) for
     *        the per-person age-65 deduction. {@code null} for a single-person input.
     */
    record Resolved(
            @Nullable TrialSimulator.HouseholdSim sim,
            double survivorFactor,
            int inWindowTransitionIdx,
            @Nullable List<ProjectionIncomeSourceInput> survivorSources,
            FilingStatus postStatus,
            @Nullable HouseholdContext context) {}

    private HouseholdMcResolver() {}

    static Resolved single(FilingStatus preStatus) {
        return new Resolved(null, 1.0, -1, null, preStatus, null);
    }

    /** HP3 Part B-2: clamps an explicit override to the documented [0.5, 1.0] range -- see the
     * field comment above. A no-op for every value {@code ScenarioCrudService} would have accepted
     * (the byte-identical anchor for the in-range/default paths). */
    private static double clampSurvivorSpendingFactor(double factor) {
        return Math.max(MIN_SURVIVOR_SPENDING_FACTOR, Math.min(MAX_SURVIVOR_SPENDING_FACTOR, factor));
    }

    static Resolved resolve(GuardrailOptimizationInput input, int retirementYear, int years,
                            double inflationRate, FilingStatus preStatus) {
        Integer spouseBirthYear = input.spouseBirthYear();
        if (spouseBirthYear == null) {
            return single(preStatus);
        }
        int primaryBirthYear = input.birthYear();
        Integer primaryDeathAgeOverride = input.primaryDeathAge();
        int primaryDeathAge = primaryDeathAgeOverride != null
                ? primaryDeathAgeOverride : LifeExpectancy.defaultDeathAge(primaryBirthYear);
        Integer spouseDeathAgeOverride = input.spouseDeathAge();
        int spouseDeathAge = spouseDeathAgeOverride != null
                ? spouseDeathAgeOverride : LifeExpectancy.defaultDeathAge(spouseBirthYear);
        int horizonEndYear = primaryBirthYear + input.endAge();
        HouseholdContext household = HouseholdContext.of(primaryBirthYear, primaryDeathAge,
                spouseBirthYear, spouseDeathAge, horizonEndYear);

        boolean survivorIsPrimary = household.survivor() == PersonId.PRIMARY;
        BigDecimal survivorFactorOverride = input.survivorSpendingFactor();
        double survivorFactor = survivorFactorOverride != null
                ? clampSurvivorSpendingFactor(survivorFactorOverride.doubleValue())
                : DEFAULT_SURVIVOR_SPENDING_FACTOR;

        // The MC year index of the first death; -1 when it falls outside the modeled window (both
        // alive the whole time, or first death at/after the horizon end). A death at/before the
        // retirement start clamps to index 0 (the whole retirement is survivor-phase).
        int inWindowTransitionIdx = -1;
        List<ProjectionIncomeSourceInput> survivorSources = null;
        FilingStatus postStatus = preStatus;
        if (household.transitionYear().isPresent()) {
            int firstDeathIdx = household.transitionYear().get() - retirementYear;
            // Household task 8 (T6-review): a first death BEFORE the MC's own retirement-anchored
            // window (index 0 == retirementYear) clamps to index 0 -- the survivor enters the
            // modeled window already alone, so rollover/step-up/single-filing tables apply from
            // trial year 0. This is the INTENDED MC behavior, not a bug, and it deliberately
            // differs from the deterministic engine's scope: the deterministic engine models
            // baseYear..endYear (which can include pre-retirement accumulation years) and so CAN
            // show the transition firing at its true pre-retirement calendar year when that falls
            // inside its wider horizon (see HouseholdTransition#resolveYear). The MC engine never
            // models pre-retirement years at all, so it cannot distinguish "died five years before
            // retirement" from "died thirty years before retirement" -- both collapse to the same
            // already-survivor starting state, which is correct for what the optimizer actually
            // simulates (by retirement the household genuinely IS single). Pinned in
            // HouseholdMcResolverTest.
            if (firstDeathIdx < 0) {
                firstDeathIdx = 0;
            }
            if (firstDeathIdx < years) {
                inWindowTransitionIdx = firstDeathIdx;
                survivorSources = SurvivorIncomeAdjuster.adjust(input.incomeSources(), household,
                        household.transitionYear().get(), input.baseYear(),
                        BigDecimal.valueOf(inflationRate));
                postStatus = FilingStatus.SINGLE;
            }
        }

        // The SIM always exists for a two-person household: the owner-split pools and two RMD streams
        // run every modeled year. transitionYearIndex >= years means the rollover/step-up never fire
        // (first death beyond the window). truncateYearIndex ends the trial at the second death.
        int simTransitionIdx = inWindowTransitionIdx >= 0 ? inWindowTransitionIdx : years;
        int truncateYearIdx = household.secondDeathYear()
                .map(sy -> Math.max(0, Math.min(years, sy - retirementYear + 1)))
                .orElse(years);
        var sim = new TrialSimulator.HouseholdSim(
                sumTraditional(input.accounts(), "spouse"),
                sumRoth(input.accounts(), "spouse"),
                primaryBirthYear - spouseBirthYear,
                RmdCalculator.rmdStartAge(spouseBirthYear),
                simTransitionIdx,
                survivorIsPrimary,
                // HP1: joint-taxable statutory rate (community/common-law). Decedent- and
                // survivor-owned rates are applied per-lot inside stepUpByOwner, not baked in here.
                input.communityProperty() ? 1.0 : 0.5,
                truncateYearIdx,
                taxableSeed(input.accounts()));

        return new Resolved(sim, survivorFactor, inWindowTransitionIdx, survivorSources, postStatus, household);
    }

    /**
     * HP1: the joint-taxable pool's initial seed split by owner (basis + value per
     * {@code joint}/{@code primary}/{@code spouse}), so the Monte Carlo lots can be seeded with owner
     * tags for an exact first-death step-up. An all-joint (or single-owner) taxable pool yields a
     * seed with only that owner's slice, so seeding collapses to one lot — byte-identical to the
     * pre-HP1 single aggregate lot.
     */
    private static TrialSimulator.TaxableSeed taxableSeed(List<ProjectionAccountInput> accounts) {
        return new TrialSimulator.TaxableSeed(
                sumTaxableBasis(accounts, "joint"), sumTaxable(accounts, "joint"),
                sumTaxableBasis(accounts, "primary"), sumTaxable(accounts, "primary"),
                sumTaxableBasis(accounts, "spouse"), sumTaxable(accounts, "spouse"));
    }

    private static double sumTraditional(List<ProjectionAccountInput> accounts, String owner) {
        return sumByTypeAndOwner(accounts, PoolStrategy.POOL_TRADITIONAL, owner).doubleValue();
    }

    private static double sumRoth(List<ProjectionAccountInput> accounts, String owner) {
        return sumByTypeAndOwner(accounts, PoolStrategy.POOL_ROTH, owner).doubleValue();
    }

    private static double sumTaxable(List<ProjectionAccountInput> accounts, String owner) {
        return sumByTypeAndOwner(accounts, PoolStrategy.POOL_TAXABLE, owner).doubleValue();
    }

    /** HP1: cost-basis sum of the taxable accounts in one owner category. */
    private static double sumTaxableBasis(List<ProjectionAccountInput> accounts, String owner) {
        double sum = 0.0;
        for (var account : accounts) {
            if (PoolStrategy.POOL_TAXABLE.equals(account.accountType())
                    && owner.equals(PoolStrategy.ownerCategory(account.owner()))) {
                sum += account.costBasis().doubleValue();
            }
        }
        return sum;
    }

    /**
     * Household task 8 (dedup): type-AND-owner-filtered sum, sharing {@link PoolStrategy#ownerCategory}
     * for the owner-partition rule instead of re-deriving it (the pre-task-8 {@code ownerOrPrimary}
     * duplicate this replaces). Not lifted onto {@link PoolStrategy} itself as a public sum-by-owner
     * method — that interface is already near PMD's {@code ExcessivePublicCount} threshold, and this
     * type-filtered shape is MC-local (the deterministic side seeds its lots per account directly).
     */
    private static BigDecimal sumByTypeAndOwner(List<ProjectionAccountInput> accounts,
                                                String type, String owner) {
        BigDecimal sum = BigDecimal.ZERO;
        for (var account : accounts) {
            if (type.equals(account.accountType()) && owner.equals(PoolStrategy.ownerCategory(account.owner()))) {
                sum = sum.add(account.initialBalance());
            }
        }
        return sum;
    }

    /**
     * Household task 8 (dedup): scales {@code values[y] *= factor} for {@code y} in
     * {@code [fromIdx, years)} -- the survivor-spending-factor rule shared by
     * {@link OptimizationContextBuilder} (the essential floor) and {@link GuardrailResponseBuilder}
     * (the floor again, plus the reported discretionary/corridor plan). A no-op when {@code fromIdx}
     * is outside {@code [0, years)} (single-person, no in-window transition, or a household whose
     * transition sentinel already equals {@code years}) or {@code factor == 1.0} (no survivor
     * spending reduction configured).
     */
    static void scaleFromTransition(double[] values, int fromIdx, double factor, int years) {
        if (fromIdx < 0 || fromIdx >= years || factor == 1.0) {
            return;
        }
        for (int y = fromIdx; y < years; y++) {
            values[y] *= factor;
        }
    }

    /**
     * HP2 (dedup): survivor-factor scaling keyed off a resolved {@link TrialSimulator.HouseholdSim}.
     * A {@code null} household (single-person, the byte-identical anchor) is a no-op; otherwise it
     * scales {@code values[y] *= factor} for {@code y >= household.transitionYearIndex()} via the
     * primitive {@link #scaleFromTransition(double[], int, double, int)} — the SAME seam that scales
     * the essential floor in {@link OptimizationContextBuilder}. Shared by
     * {@link MonteCarloSpendingOptimizer}'s single discretionary pre-scaling (so the search's terminal
     * pass simulates exactly the schedule reported to the user) and {@link GuardrailResponseBuilder}'s
     * floor-clamp disclosure, so the two consumers apply the factor identically and exactly once.
     */
    static void scaleFromTransition(double[] values, @Nullable TrialSimulator.HouseholdSim household,
                                    double factor, int years) {
        if (household == null) {
            return;
        }
        scaleFromTransition(values, household.transitionYearIndex(), factor, years);
    }
}
