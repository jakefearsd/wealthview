package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * <p><b>Age windows.</b> The engine evaluates every income source's {@code start_age}/{@code end_age}
 * against the PRIMARY member's age (the single age it threads through the year loop). When the
 * survivor is the SPOUSE, a surviving DECEASED-owned source must instead follow the survivor's age
 * (spec §4.1). Rather than fork the processor, its window is re-based into the primary-age frame by a
 * fixed {@code survivorBirthYear − primaryBirthYear} shift, so {@code isActiveForAge(source,
 * primaryAge)} evaluates as if against the survivor's age. Survivor-owned (and joint) sources are
 * left untouched — "unchanged" per spec, and consistent with their pre-transition evaluation frame.
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
        // adjust() is only ever reached for a two-person household (the engine guards on the
        // transition year), so the spouse is always present.
        HouseholdContext.Person spouse = Objects.requireNonNull(household.spouse(),
                "survivor-phase income adjustment requires a two-person household");
        int ageShift = survivor == PersonId.PRIMARY
                ? 0
                : spouse.birthYear() - household.primary().birthYear();
        int yearsFromBaseAtTransition = Math.max(0, transitionYear - baseYear) + 1;
        UUID keptSsId = keepLargerSocialSecurityId(sources, yearsFromBaseAtTransition, scenarioInflationRate);

        var adjusted = new ArrayList<ProjectionIncomeSourceInput>(sources.size());
        for (var source : sources) {
            boolean deceasedOwned = ownerOf(source) == deceased;
            if (source.incomeType() == IncomeSourceType.SOCIAL_SECURITY) {
                if (keptSsId == null || !keptSsId.equals(source.id())) {
                    continue; // the smaller Social Security benefit ends
                }
                adjusted.add(deceasedOwned ? reframe(source, source.annualAmount(), ageShift) : source);
            } else if (deceasedOwned) {
                BigDecimal pct = source.survivorPercent() != null ? source.survivorPercent() : BigDecimal.ONE;
                if (pct.signum() <= 0) {
                    continue; // survivor_percent 0 ⇒ the source ends
                }
                adjusted.add(reframe(source, source.annualAmount().multiply(pct), ageShift));
            } else {
                adjusted.add(source); // survivor-owned / joint: unchanged
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
     * A copy of {@code source} carrying {@code newAmount} and its {@code start_age}/{@code end_age}
     * shifted by {@code ageShift} (null end age stays null). {@code ageShift} is zero when the survivor
     * is the primary, so this is an amount-only rewrite in that case.
     */
    private static ProjectionIncomeSourceInput reframe(ProjectionIncomeSourceInput source,
                                                       BigDecimal newAmount, int ageShift) {
        Integer newEndAge = source.endAge() != null ? source.endAge() + ageShift : null;
        return new ProjectionIncomeSourceInput(
                source.id(), source.name(), source.incomeType(), newAmount,
                source.startAge() + ageShift, newEndAge, source.inflationRate(), source.oneTime(),
                source.taxTreatment(), source.annualOperatingExpenses(), source.annualMortgageInterest(),
                source.annualMortgagePrincipal(), source.annualPropertyTax(), source.depreciationMethod(),
                source.depreciationByYear(), source.owner(), source.survivorPercent());
    }
}
