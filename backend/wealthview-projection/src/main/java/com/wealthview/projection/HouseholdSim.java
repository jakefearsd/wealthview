package com.wealthview.projection;

/**
 * Household task 6: the per-trial first-death transition parameters, {@code null} for a
 * single-person scenario (the byte-identical anchor). Pre-computed once per run by
 * {@link OptimizationContextBuilder} and only READ in the hot loop — the death year is FIXED, so
 * no per-trial recomputation is ever needed.
 *
 * <p>All year fields are retirement-anchored MC year indices (the same {@code y} the trial loop
 * uses). {@code initTraditionalSpouse}/{@code initRothSpouse} carve the spouse's slice out of the
 * config's {@code initTraditional}/{@code initRoth} TOTALS (primary keeps the remainder), so those
 * totals keep their pre-household single-person meaning. Per-owner RMD streams key off each
 * owner's own age ({@code spouseAgeOffset = primaryBirthYear − spouseBirthYear}, so
 * {@code spouseAge = primaryAge + spouseAgeOffset}) and SECURE-2.0 start age. At
 * {@code transitionYearIndex} the deceased's traditional/Roth roll into the survivor's pools and
 * the joint-taxable lots step up EXACTLY per owner (HP1): {@code taxableSeed} seeds the lots
 * tagged by owner and {@code jointStepUpFactor} ({@code communityProperty ? 1.0 : 0.5}) drives
 * the joint lots' rate — the decedent's own lots step fully, the survivor's not at all. When the
 * taxable pool is all-joint (or single-owner) {@code taxableSeed} carries only a joint (or that
 * owner's) slice, so seeding collapses to one lot and the step-up equals the old blended factor —
 * the byte-identical anchor. Trials iterate only up to {@code truncateYearIndex}
 * (= min(horizon, second-death index + 1)).
 */
record HouseholdSim(
        double initTraditionalSpouse, double initRothSpouse,
        int spouseAgeOffset, int spouseRmdStartAge,
        int transitionYearIndex, boolean survivorIsPrimary,
        double jointStepUpFactor, int truncateYearIndex,
        TaxableSeed taxableSeed) {

    /**
     * Sub-project B (stochastic mortality), task 6: a copy of this fixed-death {@code HouseholdSim}
     * with only the three per-trial mortality fields replaced — the trial's own first-death index,
     * survivor identity, and second-death truncation index (from {@link MortalityDraws}). The six
     * identity/structure-invariant fields ({@code initTraditionalSpouse}, {@code initRothSpouse},
     * {@code spouseAgeOffset}, {@code spouseRmdStartAge}, {@code jointStepUpFactor} — the joint
     * statutory step-up rate, whose DIRECTION is driven by {@code survivorIsPrimary} — and
     * {@code taxableSeed}) describe the household's starting state and are shared across every trial,
     * so the stochastic evaluation pass reuses one base {@code HouseholdSim} and withers it per trial.
     */
    HouseholdSim withTrialMortality(int transitionYearIndex, boolean survivorIsPrimary,
                                    int truncateYearIndex) {
        return new HouseholdSim(initTraditionalSpouse, initRothSpouse, spouseAgeOffset, spouseRmdStartAge,
                transitionYearIndex, survivorIsPrimary, jointStepUpFactor, truncateYearIndex, taxableSeed);
    }

    /**
     * A copy of this household with the trial's own starting state: the spouse's traditional/Roth
     * slices and the three taxable lot VALUES come from {@code start}. The taxable lot bases and every
     * other field are unchanged -- the basis stays the expected scalar in every trial, so a trial whose
     * taxable pool grew less than its basis seeds a lot with an unrealized loss.
     */
    HouseholdSim withStartBalances(TrialStartBalances start) {
        var seed = new TaxableSeed(taxableSeed.jointBasis(), start.taxableJoint(),
                taxableSeed.primaryBasis(), start.taxablePrimary(),
                taxableSeed.spouseBasis(), start.taxableSpouse());
        return new HouseholdSim(start.traditionalSpouse(), start.rothSpouse(), spouseAgeOffset,
                spouseRmdStartAge, transitionYearIndex, survivorIsPrimary, jointStepUpFactor,
                truncateYearIndex, seed);
    }
}
