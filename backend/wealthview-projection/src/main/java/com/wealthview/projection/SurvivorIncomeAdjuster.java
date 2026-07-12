package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.household.PersonId;

/**
 * Household task 5 (transition step 1): rewrites a scenario's income sources into their
 * survivor-phase form — the list the engine feeds {@link IncomeSourceProcessor} for the first-death
 * transition year and every year after. Applied deterministically from the ORIGINAL sources each
 * survivor-phase year (never cumulatively), so it is idempotent across the Social Security
 * convergence re-runs and re-computable identically every year.
 *
 * <p>Three rules, per spec §4.1:
 * <ul>
 *   <li><b>Social Security keep-larger</b> — the survivor keeps the single largest Social Security
 *       benefit (compared at its effective REAL amount in the transition year); every other Social
 *       Security source ends. This automatically models the statutory rule for both orderings (the
 *       larger benefit on either spouse's side).</li>
 *   <li><b>Deceased-owned non-SS × survivor_percent</b> — a non-Social-Security source the deceased
 *       owned continues at its {@code survivor_percent} (0 ⇒ it ends, e.g. a single-life pension).</li>
 *   <li><b>Survivor-owned sources unchanged.</b></li>
 * </ul>
 *
 * <p><b>Age windows (household task 7 rework).</b> {@link IncomeSourceProcessor} now evaluates every
 * income source's {@code start_age}/{@code end_age} against its OWNER's age
 * ({@link IncomeYearMath#resolveSourceAge}), not a single primary-age variable. A surviving
 * DECEASED-owned source is "survivor-attached" for age windows (spec §4.1 / task 5's brief: its
 * window is evaluated against the SURVIVOR's age going forward) — this class achieves that simply by
 * RELABELING the {@code owner} field to the survivor via {@link #relabelToSurvivor}, leaving {@code
 * start_age}/{@code end_age} untouched. {@link IncomeSourceProcessor}'s owner-age lookup then
 * naturally resolves the survivor's REAL age against the original (unshifted) window — reproducing
 * the pre-rework fixed {@code survivorBirthYear − primaryBirthYear} age-shift trick's result exactly,
 * without needing an age-arithmetic hack. Two cases:
 * <ul>
 *   <li><b>Survivor-owned sources</b> (the {@code else} branch below): passed through completely
 *       unchanged. Their {@code owner} already names the survivor, so owner-age evaluation already
 *       resolves the survivor's own age both before AND after the transition — an IDENTITY, no
 *       rework needed.</li>
 *   <li><b>Deceased-owned sources that survive</b> (kept Social Security or {@code survivor_percent}
 *       {@literal >} 0): relabeled to the survivor. This is the one place this class still touches a
 *       record field for age purposes — a fixed OWNERSHIP reassignment, not a per-year age shift.</li>
 * </ul>
 */
final class SurvivorIncomeAdjuster {

    private SurvivorIncomeAdjuster() {}

    /**
     * Rewrites the income sources into their survivor-phase form for one year at or after the
     * first-death transition.
     *
     * @param sources the scenario's ORIGINAL income sources
     * @param household the resolved household context (must be a two-person household with a survivor)
     * @param transitionYear the first-death calendar year
     * @param baseYear the projection's base (reference) year — the income-deflation clock anchor
     * @param scenarioInflationRate the scenario's own inflation rate
     * @return the survivor-phase income source list
     */
    static List<ProjectionIncomeSourceInput> adjust(List<ProjectionIncomeSourceInput> sources,
                                                    HouseholdContext household, int transitionYear,
                                                    int baseYear, BigDecimal scenarioInflationRate) {
        if (sources == null || sources.isEmpty()) {
            return sources;
        }
        PersonId survivor = household.survivor();
        PersonId deceased = survivor == PersonId.PRIMARY ? PersonId.SPOUSE : PersonId.PRIMARY;
        int yearsFromBaseAtTransition = Math.max(0, transitionYear - baseYear) + 1;
        UUID keptSsId = keepLargerSocialSecurityId(sources, yearsFromBaseAtTransition, scenarioInflationRate);

        var adjusted = new ArrayList<ProjectionIncomeSourceInput>(sources.size());
        for (var source : sources) {
            boolean deceasedOwned = ownerOf(source) == deceased;
            if (source.incomeType() == IncomeSourceType.SOCIAL_SECURITY) {
                if (keptSsId == null || !keptSsId.equals(source.id())) {
                    continue; // the smaller Social Security benefit ends
                }
                adjusted.add(deceasedOwned ? relabelToSurvivor(source, source.annualAmount(), survivor) : source);
            } else if (deceasedOwned) {
                BigDecimal pct = source.survivorPercent() != null ? source.survivorPercent() : BigDecimal.ONE;
                if (pct.signum() <= 0) {
                    continue; // survivor_percent 0 ⇒ the source ends
                }
                adjusted.add(relabelToSurvivor(source, source.annualAmount().multiply(pct), survivor));
            } else {
                adjusted.add(source); // survivor-owned / joint: unchanged (identity, see class Javadoc)
            }
        }
        return adjusted;
    }

    private static PersonId ownerOf(ProjectionIncomeSourceInput source) {
        return "spouse".equals(source.owner()) ? PersonId.SPOUSE : PersonId.PRIMARY;
    }

    /**
     * The id of the Social Security source with the largest effective real benefit at the transition
     * year (ties resolve to the first encountered, deterministic), or {@code null} when there are no
     * Social Security sources.
     */
    private static UUID keepLargerSocialSecurityId(List<ProjectionIncomeSourceInput> sources,
                                                   int yearsFromBaseAtTransition,
                                                   BigDecimal scenarioInflationRate) {
        UUID keptId = null;
        BigDecimal largest = null;
        for (var source : sources) {
            if (source.incomeType() != IncomeSourceType.SOCIAL_SECURITY) {
                continue;
            }
            BigDecimal amount = IncomeYearMath.realAmount(source, yearsFromBaseAtTransition, scenarioInflationRate);
            if (largest == null || amount.compareTo(largest) > 0) {
                largest = amount;
                keptId = source.id();
            }
        }
        return keptId;
    }

    /**
     * A copy of {@code source} carrying {@code newAmount} and its {@code owner} reassigned to {@code
     * survivor} — {@code start_age}/{@code end_age} are left EXACTLY as configured (household task 7:
     * owner-age evaluation resolves the survivor's real age against them directly; see the class
     * Javadoc). A no-op reassignment when the source was already survivor-owned (can't happen for a
     * DECEASED-owned source by construction, but documented for clarity).
     */
    private static ProjectionIncomeSourceInput relabelToSurvivor(ProjectionIncomeSourceInput source,
                                                                 BigDecimal newAmount, PersonId survivor) {
        String survivorOwner = survivor == PersonId.PRIMARY ? "primary" : "spouse";
        return new ProjectionIncomeSourceInput(
                source.id(), source.name(), source.incomeType(), newAmount,
                source.startAge(), source.endAge(), source.inflationRate(), source.oneTime(),
                source.taxTreatment(), source.annualOperatingExpenses(), source.annualMortgageInterest(),
                source.annualMortgagePrincipal(), source.annualPropertyTax(), source.depreciationMethod(),
                source.depreciationByYear(), survivorOwner, source.survivorPercent());
    }
}
