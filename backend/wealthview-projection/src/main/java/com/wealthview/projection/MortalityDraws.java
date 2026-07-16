package com.wealthview.projection;

/**
 * Sub-project B (stochastic mortality), task 5: the per-trial sampled mortality for one optimization
 * run, in trial order. All arrays are {@code trialCount}-long and index-aligned by trial.
 *
 * <p>The fields fall into TWO distinct kinds — do not conflate them:
 * <ul>
 *   <li><b>Horizon-clamped indices</b> ({@link #transitionIdx}, {@link #truncateIdx},
 *       {@link #survivorIsPrimary}) drive the trial-simulation loop. They carry the SAME semantics
 *       the fixed-death {@link HouseholdMcResolver#resolve} derives — {@code transitionIdx} is the
 *       retirement-anchored first-death year index (clamped to {@code [0, years]}, with {@code years}
 *       the "no in-window transition" sentinel), and {@code truncateIdx} the second-death year index
 *       {@code +1} (clamped to {@code [0, years]}). {@link MortalityDrawGenerator} maps each trial's
 *       sampled ages through {@code HouseholdContext.of} and the resolver's exact formulas so the
 *       stochastic and fixed-death paths splice identically (task 6 consumes these).</li>
 *   <li><b>Raw sampled death ages</b> ({@link #firstDeathAge}, {@link #secondDeathAge}) are NOT
 *       horizon-clamped: {@code firstDeathAge} is the death age of whoever dies first and
 *       {@code secondDeathAge} the survivor's death age, straight off the sampler even when they land
 *       beyond the projection horizon. Task 7 reports the death-age distribution and the
 *       "survivor reaches age N" longevity metric from these, so they must preserve the true sampled
 *       age.</li>
 * </ul>
 *
 * @param transitionIdx      per-trial first-death year index (horizon-clamped; {@code years} sentinel
 *                           when the first death never falls inside the modeled window)
 * @param truncateIdx        per-trial second-death year index {@code +1} (horizon-clamped to
 *                           {@code [0, years]})
 * @param survivorIsPrimary  per-trial {@code true} iff the primary is the survivor (outlives the
 *                           spouse; younger person wins a same-year tie)
 * @param firstDeathAge      per-trial RAW sampled death age of whoever dies FIRST (un-clamped)
 * @param secondDeathAge     per-trial RAW sampled death age of the SURVIVOR (un-clamped)
 */
record MortalityDraws(int[] transitionIdx, int[] truncateIdx, boolean[] survivorIsPrimary,
                      int[] firstDeathAge, int[] secondDeathAge) {}
