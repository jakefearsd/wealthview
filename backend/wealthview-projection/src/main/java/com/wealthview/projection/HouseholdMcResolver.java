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
                ? survivorFactorOverride.doubleValue() : DEFAULT_SURVIVOR_SPENDING_FACTOR;

        // The MC year index of the first death; -1 when it falls outside the modeled window (both
        // alive the whole time, or first death at/after the horizon end). A death at/before the
        // retirement start clamps to index 0 (the whole retirement is survivor-phase).
        int inWindowTransitionIdx = -1;
        List<ProjectionIncomeSourceInput> survivorSources = null;
        FilingStatus postStatus = preStatus;
        if (household.transitionYear().isPresent()) {
            int firstDeathIdx = household.transitionYear().get() - retirementYear;
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
                blendedStepUpFactor(input.accounts(), survivorIsPrimary, input.communityProperty()),
                truncateYearIdx);

        return new Resolved(sim, survivorFactor, inWindowTransitionIdx, survivorSources, postStatus, household);
    }

    /**
     * The blended joint-taxable basis step-up factor, delegating to the deterministic
     * {@link PoolStrategy#blendedStepUpFactor} so both engines apply the identical
     * initial-balance-weighted rate (deceased-owned taxable steps up fully, joint at the community/
     * common-law rate, survivor-owned not at all).
     */
    private static double blendedStepUpFactor(List<ProjectionAccountInput> accounts,
                                              boolean survivorIsPrimary, boolean communityProperty) {
        PersonId deceased = survivorIsPrimary ? PersonId.SPOUSE : PersonId.PRIMARY;
        BigDecimal primaryTaxable = sumTaxable(accounts, "primary");
        BigDecimal spouseTaxable = sumTaxable(accounts, "spouse");
        BigDecimal jointTaxable = sumTaxable(accounts, "joint");
        return PoolStrategy.blendedStepUpFactor(primaryTaxable, spouseTaxable, jointTaxable,
                deceased, communityProperty).doubleValue();
    }

    private static double sumTraditional(List<ProjectionAccountInput> accounts, String owner) {
        return sumByTypeAndOwner(accounts, PoolStrategy.POOL_TRADITIONAL, owner).doubleValue();
    }

    private static double sumRoth(List<ProjectionAccountInput> accounts, String owner) {
        return sumByTypeAndOwner(accounts, PoolStrategy.POOL_ROTH, owner).doubleValue();
    }

    private static BigDecimal sumTaxable(List<ProjectionAccountInput> accounts, String owner) {
        return sumByTypeAndOwner(accounts, PoolStrategy.POOL_TAXABLE, owner);
    }

    /** The "primary" owner is the default for any account whose owner is null or unrecognized,
     * matching {@code OwnerPool.ownerOf} (only {@code "spouse"} / {@code "joint"} are distinct). */
    private static BigDecimal sumByTypeAndOwner(List<ProjectionAccountInput> accounts,
                                                String type, String owner) {
        BigDecimal sum = BigDecimal.ZERO;
        for (var account : accounts) {
            if (type.equals(account.accountType()) && owner.equals(ownerOrPrimary(account.owner()))) {
                sum = sum.add(account.initialBalance());
            }
        }
        return sum;
    }

    private static String ownerOrPrimary(@Nullable String owner) {
        return "spouse".equals(owner) || "joint".equals(owner) ? owner : "primary";
    }
}
