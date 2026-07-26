package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.LotOwner;
import com.wealthview.core.projection.dto.PoolType;
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
 *
 * <p><b>Scope note (HP3 Part C, verified present per the feature review):</b> a first death BEFORE
 * this MC window (i.e. before the retirement-anchored index 0) clamps {@code inWindowTransitionIdx}
 * to {@code 0} rather than a negative/out-of-window sentinel -- the survivor enters the modeled
 * window already alone. This deliberately differs from the deterministic engine's wider scope,
 * which models {@code baseYear..endYear} (including pre-retirement accumulation years) and so CAN
 * show the transition firing at its true pre-retirement calendar year -- see
 * {@link HouseholdTransition#resolveYear}. The MC engine never models pre-retirement years at all,
 * so "died five years before retirement" and "died thirty years before retirement" both collapse to
 * the same already-survivor starting state; see {@link HouseholdIndexMath#transitionIndex} (the
 * shared clamp site) for the full rationale and {@code HouseholdMcResolverTest} for the pin.
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
     *        {@link HouseholdContext}, threaded into the per-year exact tax tables (task 15: via
     *        {@code OptimizationContextBuilder#buildRegime}'s {@code OrdinaryTaxTable.computeAll}/
     *        {@code LtcgTaxTable.computeAll} calls) for the per-person age-65 deduction. {@code null}
     *        for a single-person input.
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

        // The MC first-death transition index for the sim loop: the shared helper owns the
        // retirement-anchoring and the "died before the window -> index 0" clamp (see the full
        // rationale on HouseholdIndexMath.transitionIndex), keeping this identical to the stochastic
        // path in MortalityDrawGenerator. `years` is the sentinel meaning "no in-window transition".
        int simTransitionIdx = HouseholdIndexMath.transitionIndex(household, retirementYear, years);
        // The MC year index of the first death when it lands inside the modeled window (drives the
        // income/tax-status splice below); -1 when it falls outside (both alive the whole time, or
        // first death at/after the horizon end -> the `years` sentinel).
        int inWindowTransitionIdx = simTransitionIdx < years ? simTransitionIdx : -1;
        List<ProjectionIncomeSourceInput> survivorSources = null;
        FilingStatus postStatus = preStatus;
        if (inWindowTransitionIdx >= 0) {
            survivorSources = SurvivorIncomeAdjuster.adjust(input.incomeSources(), household,
                    household.transitionYear().get(), input.baseYear(),
                    BigDecimal.valueOf(inflationRate));
            postStatus = FilingStatus.SINGLE;
        }

        // The SIM always exists for a two-person household: the owner-split pools and two RMD streams
        // run every modeled year. transitionYearIndex >= years means the rollover/step-up never fire
        // (first death beyond the window). truncateYearIndex ends the trial at the second death.
        int truncateYearIdx = HouseholdIndexMath.truncateIndex(household, retirementYear, years);
        var sim = new TrialSimulator.HouseholdSim(
                sumTraditional(input.accounts(), LotOwner.SPOUSE),
                sumRoth(input.accounts(), LotOwner.SPOUSE),
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
                sumTaxableBasis(accounts, LotOwner.JOINT), sumTaxable(accounts, LotOwner.JOINT),
                sumTaxableBasis(accounts, LotOwner.PRIMARY), sumTaxable(accounts, LotOwner.PRIMARY),
                sumTaxableBasis(accounts, LotOwner.SPOUSE), sumTaxable(accounts, LotOwner.SPOUSE));
    }

    private static double sumTraditional(List<ProjectionAccountInput> accounts, LotOwner owner) {
        return sumByTypeAndOwner(accounts, PoolType.TRADITIONAL, owner).doubleValue();
    }

    private static double sumRoth(List<ProjectionAccountInput> accounts, LotOwner owner) {
        return sumByTypeAndOwner(accounts, PoolType.ROTH, owner).doubleValue();
    }

    private static double sumTaxable(List<ProjectionAccountInput> accounts, LotOwner owner) {
        return sumByTypeAndOwner(accounts, PoolType.TAXABLE, owner).doubleValue();
    }

    /** HP1: cost-basis sum of the taxable accounts owned by one {@link LotOwner}. */
    private static double sumTaxableBasis(List<ProjectionAccountInput> accounts, LotOwner owner) {
        double sum = 0.0;
        for (var account : accounts) {
            if (account.poolType() == PoolType.TAXABLE && account.ownerType() == owner) {
                sum += account.costBasis().doubleValue();
            }
        }
        return sum;
    }

    /**
     * Household task 8 (dedup): type-AND-owner-filtered sum over the typed {@link
     * ProjectionAccountInput#poolType()}/{@link ProjectionAccountInput#ownerType()} accessors (task
     * 16 — replaces the former {@code PoolStrategy.ownerCategory} string-normalization bridge). Not
     * lifted onto {@link PoolStrategy} itself as a public sum-by-owner method — that interface is
     * already near PMD's {@code ExcessivePublicCount} threshold, and this type-filtered shape is
     * MC-local (the deterministic side seeds its lots per account directly).
     */
    private static BigDecimal sumByTypeAndOwner(List<ProjectionAccountInput> accounts,
                                                PoolType type, LotOwner owner) {
        BigDecimal sum = BigDecimal.ZERO;
        for (var account : accounts) {
            if (type == account.poolType() && owner == account.ownerType()) {
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
