package com.wealthview.projection;

import com.wealthview.core.projection.household.HouseholdContext;

/**
 * Sub-project B (stochastic mortality): the shared retirement-anchored index math for the Monte
 * Carlo household transition. Both the fixed-death path ({@link HouseholdMcResolver#resolve}) and
 * the stochastic-mortality path ({@link MortalityDrawGenerator#generate}) derive each trial's
 * transition/truncate indices from ONE place here, instead of re-deriving the same formulas inline
 * on each side. That duplication was a drift risk: a future change to {@code resolve}'s convention
 * would silently diverge from the stochastic path (only the goldens/pins would catch it). Keeping
 * the arithmetic in this single helper makes the two paths byte-identical by construction.
 */
final class HouseholdIndexMath {

    private HouseholdIndexMath() {
    }

    /**
     * The MC first-death transition index (the value both {@code resolve}'s {@code simTransitionIdx}
     * and the generator's per-trial transition index need): {@code years} — the "no in-window
     * transition" sentinel — when the first death never falls inside the modeled window, else
     * {@code transitionYear - retirementYear} clamped to {@code [0, years)}.
     *
     * <p>The {@code firstDeathIdx < 0} clamp collapses a first death BEFORE the MC's own
     * retirement-anchored window (index 0 == {@code retirementYear}) to index 0 — the survivor
     * enters the modeled window already alone, so rollover/step-up/single-filing tables apply from
     * trial year 0. This is the INTENDED MC behavior and deliberately differs from the deterministic
     * engine's wider scope: that engine models {@code baseYear..endYear} (which can include
     * pre-retirement accumulation years) and so CAN show the transition firing at its true
     * pre-retirement calendar year (see {@code HouseholdTransition#resolveYear}); the MC engine never
     * models pre-retirement years at all, so "died five years before retirement" and "died thirty
     * years before retirement" both collapse to the same already-survivor starting state — correct
     * for what the optimizer actually simulates (by retirement the household genuinely IS single).
     * The clamp is unreachable via the stochastic sampler (which conditions each spouse on being
     * alive at retirement) but kept for parity with the fixed-death path. Pinned in
     * {@code HouseholdMcResolverTest} and {@code MortalityDrawsTest}.
     */
    static int transitionIndex(HouseholdContext household, int retirementYear, int years) {
        if (household.transitionYear().isEmpty()) {
            return years;
        }
        int firstDeathIdx = household.transitionYear().get() - retirementYear;
        if (firstDeathIdx < 0) {
            firstDeathIdx = 0;
        }
        return firstDeathIdx < years ? firstDeathIdx : years;
    }

    /**
     * The MC trial truncation index (both {@code resolve}'s {@code truncateYearIdx} and the
     * generator's per-trial truncate index): the survivor's death-year index {@code +1} clamped to
     * {@code [0, years]}, or {@code years} when the second death never falls inside the modeled
     * window.
     */
    static int truncateIndex(HouseholdContext household, int retirementYear, int years) {
        return household.secondDeathYear()
                .map(sy -> Math.max(0, Math.min(years, sy - retirementYear + 1)))
                .orElse(years);
    }
}
