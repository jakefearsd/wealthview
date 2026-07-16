package com.wealthview.projection;

import java.util.Objects;
import java.util.Random;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.household.PersonId;
import com.wealthview.core.projection.mortality.MortalityTable;

/**
 * Sub-project B (stochastic mortality), task 5: precomputes per-trial {@link MortalityDraws} from a
 * household {@link GuardrailOptimizationInput}, sampling each spouse's death age with
 * {@link MortalitySampler} and mapping the sampled ages to transition/truncate indices with the
 * EXACT formulas the fixed-death {@link HouseholdMcResolver#resolve} already uses.
 *
 * <p>Rather than re-deriving the first-death/second-death/survivor logic, each trial builds a real
 * {@link HouseholdContext#of} from that trial's sampled death ages (the same call {@code resolve}
 * makes for the fixed death ages) and reads its {@code transitionYear()}/{@code secondDeathYear()}/
 * {@code survivor()} back out — so the min/max-death-year and younger-survives-on-tie rules stay in
 * ONE place. The index math is the SAME shared {@link HouseholdIndexMath#transitionIndex} /
 * {@link HouseholdIndexMath#truncateIndex} {@code resolve} calls (a single source of truth, not a
 * line-for-line copy), which keeps the stochastic path spliceable by task 6 exactly like the
 * fixed-death path.
 *
 * <p>Each spouse is sampled conditional on being alive at their RETIREMENT-year age
 * ({@code retirementYear - birthYear}): the Monte Carlo models retirement onward only, so a sampled
 * death can never predate the window. Draws advance in trial order, both spouses per trial (primary
 * then spouse), mirroring {@link PortfolioPathGenerator}'s per-trial draw discipline.
 */
final class MortalityDrawGenerator {

    /**
     * Seed offset for the dedicated stochastic-mortality {@link Random} stream. Kept DISTINCT from
     * the return-path rng (offset {@code 0} — {@code new Random(input.seed())} in
     * {@link OptimizationContextBuilder}) and the Roth joint-conversion search rng (offset {@code +1}
     * — {@code new Random(input.seed() + 1)} in {@link JointConversionSearch}) so drawing per-trial
     * death ages never perturbs either of those streams (the byte-identical anchor). Its magnitude is
     * arbitrary; only its distinctness from {@code 0} and {@code 1} matters. The literal is the ASCII
     * bytes of "MORT", chosen purely as a self-documenting, obviously-non-colliding constant.
     */
    static final long MORTALITY_SEED_OFFSET = 0x4D4F5254L;

    private MortalityDrawGenerator() {
    }

    /**
     * Samples {@code trialCount} per-trial death-age pairs and derives their horizon-clamped
     * transition/truncate indices plus raw survivor death ages. Requires a household input with a
     * non-null {@link GuardrailOptimizationInput#mortalityTable()} and
     * {@link GuardrailOptimizationInput#spouseBirthYear()} — the caller ({@link
     * OptimizationContextBuilder}) gates on the stochastic-mortality toggle before calling.
     *
     * @param input          the run's household input (primary/spouse birth years, sexes, table, horizon)
     * @param retirementYear the calendar year MC year index 0 anchors to
     * @param years          the modeled horizon length (the index sentinel / clamp ceiling)
     * @param mortRng        the dedicated, separately-seeded mortality random stream
     * @param trialCount     the number of trials to draw
     * @return the per-trial {@link MortalityDraws}
     */
    static MortalityDraws generate(GuardrailOptimizationInput input, int retirementYear, int years,
                                   Random mortRng, int trialCount) {
        MortalityTable table = Objects.requireNonNull(input.mortalityTable(),
                "stochastic mortality requires a mortality table");
        int primaryBirthYear = input.birthYear();
        int spouseBirthYear = Objects.requireNonNull(input.spouseBirthYear(),
                "stochastic mortality requires a spouse (household)");
        int horizonEndYear = primaryBirthYear + input.endAge();
        int primaryAgeAtRetirement = retirementYear - primaryBirthYear;
        int spouseAgeAtRetirement = retirementYear - spouseBirthYear;
        String primarySex = input.primarySex();
        String spouseSex = input.spouseSex();

        int[] transitionIdx = new int[trialCount];
        int[] truncateIdx = new int[trialCount];
        boolean[] survivorIsPrimary = new boolean[trialCount];
        int[] firstDeathAge = new int[trialCount];
        int[] secondDeathAge = new int[trialCount];

        for (int t = 0; t < trialCount; t++) {
            int primaryDeathAge = MortalitySampler.sampleDeathAge(table, primarySex, primaryAgeAtRetirement, mortRng);
            int spouseDeathAge = MortalitySampler.sampleDeathAge(table, spouseSex, spouseAgeAtRetirement, mortRng);

            HouseholdContext household = HouseholdContext.of(primaryBirthYear, primaryDeathAge,
                    spouseBirthYear, spouseDeathAge, horizonEndYear);
            boolean primarySurvives = household.survivor() == PersonId.PRIMARY;

            transitionIdx[t] = HouseholdIndexMath.transitionIndex(household, retirementYear, years);
            truncateIdx[t] = HouseholdIndexMath.truncateIndex(household, retirementYear, years);
            survivorIsPrimary[t] = primarySurvives;
            // Raw (un-horizon-clamped) sampled ages, mapped by survivor identity: the survivor dies
            // SECOND, so secondDeathAge is the survivor's sampled age and firstDeathAge the other's.
            secondDeathAge[t] = primarySurvives ? primaryDeathAge : spouseDeathAge;
            firstDeathAge[t] = primarySurvives ? spouseDeathAge : primaryDeathAge;
        }
        return new MortalityDraws(transitionIdx, truncateIdx, survivorIsPrimary, firstDeathAge, secondDeathAge);
    }
}
