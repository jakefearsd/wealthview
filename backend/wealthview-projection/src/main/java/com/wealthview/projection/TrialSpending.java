package com.wealthview.projection;

/**
 * Per-year spending resolution for {@link TrialSimulator}: the surplus/withdrawal split, the planned-vs-adapted
 * total, and the simulated guardrail-adaptation rule. Static; {@code adaptYearSpending} is allocation-free in the
 * hot loop.
 */
final class TrialSpending {

    /** Audit A4 bundle: the year's surplus/withdrawal split for spending and base-income tax. */
    record SpendingFunding(double grossSurplus, double fundedFromSurplus,
                           double unfundedBaseTax, double withdrawal) {}

    /** Splits this year's income against spending into: the gross surplus, the portion of the base
     * income tax funded from that surplus, the unfunded base-tax remainder (drained from pools), and
     * the spending withdrawal need. Extracted from {@link TrialSimulator#simulateTrial} to keep the NCSS-capped hot
     * method within budget. */
    static SpendingFunding resolveSpendingFunding(double income, double spending, double baseIncomeTax) {
        double grossSurplus = income - spending;
        double fundedFromSurplus = Math.min(Math.max(0, grossSurplus), baseIncomeTax);
        return new SpendingFunding(grossSurplus, fundedFromSurplus,
                baseIncomeTax - fundedFromSurplus, Math.max(0, spending - income));
    }

    /**
     * Resolves this trial-year's total spending. On the no-rules path (no
     * {@link GuardrailAdaptation} on the config) this is the fixed planned schedule
     * {@code floor + discretionary}, byte-identical to pre-C9. On the with-rules pass it is the
     * corridor-coupled adaptation of {@link #adaptYearSpending} (audit C9).
     */
    static double resolveYearSpending(SimulationConfig config, int y,
                                       double floor, double discretionary,
                                       double trialStartTotal, double prevSpending) {
        double plannedSpending = floor + discretionary;
        GuardrailAdaptation adaptation = config.adaptation();
        return adaptation != null
                ? adaptYearSpending(adaptation, y, plannedSpending, floor, trialStartTotal, prevSpending)
                : plannedSpending;
    }

    /**
     * Applies the simulated guardrail-adaptation rule (audit C9) for one trial-year: returns the
     * TOTAL spending the trial should attempt this year, adapting the planned schedule toward the
     * displayed corridor based on the trial's actual portfolio state.
     *
     * <p><b>Corridor coupling — precise semantics.</b> The trigger signal is a portfolio-ratio
     * PROXY — {@code impliedSpending = plannedSpending * (trialStartBalance /
     * expectedMedianStart[y])} — GATED BY the displayed corridor thresholds; it is NOT derived the
     * way the corridor itself was ({@link SpendingCorridorCalculator} builds the band from
     * cross-trial percentile dispersion of sustainable capacity, whereas this signal is a simple
     * ratio scaling of the plan by the trial's portfolio state relative to the no-adaptation
     * median). When the proxy falls below the displayed lower guardrail ({@code corridorLow[y]})
     * the trial CUTS discretionary toward the proxy ITSELF — so simulated spending CAN fall below
     * {@code corridorLow[y]}, bounded only by the floor and the year-over-year rate cap. Otherwise
     * it RECOVERS toward the planned schedule (the upper guardrail {@code corridorHigh[y]} is the
     * ceiling of that recovery, but the no-ratchet cap at {@code plannedSpending <=
     * corridorHigh[y]} always binds first — see constraint 2, so the upper band is display-only
     * in-simulation). The with-rules success rate therefore measures "success when following this
     * specific ratio-cut rule", not "spending always kept inside the shown corridor".
     *
     * <p><b>Bounds.</b> The year-over-year change is capped at {@code maxAnnualAdjustmentRate}
     * relative to the prior year's spending (the same relative form the pre-optimization
     * {@link SpendingSmoother#applyYearOverYearSmoothing} uses). Result is then capped at
     * {@code plannedSpending} (never exceed the plan — recovering cuts, never an unbounded
     * prosperity ratchet) and floored at {@code floor} (the essential floor is inviolate — cuts
     * never take spending below it). Because floors are never cut, the adaptation cannot cause a
     * floor shortfall within its own year; across cumulative multi-year tax paths (RMD force-outs,
     * gross-ups, LTCG interactions) with-rules-&gt;=-no-rules success monotonicity is NOT formally
     * proven — it is empirically pinned by the integration tests (including the RMD/tax-dynamics
     * pairwise pins) and a 60,000-trial-pair adversarial probe that found zero floor or
     * final-balance regressions.
     *
     * <p>Pure, allocation-free, no branching on trial identity — safe for the hot loop.
     */
    static double adaptYearSpending(GuardrailAdaptation adapt, int y,
                                     double plannedSpending, double floor,
                                     double trialStartBalance, double prevSpending) {
        double rate = adapt.maxAnnualAdjustmentRate();
        double expected = adapt.expectedStartBalance()[y];
        double ratio = expected > 0 ? trialStartBalance / expected : 1.0;
        double impliedSpending = plannedSpending * ratio;

        double target;
        if (impliedSpending < adapt.corridorLow()[y]) {
            // Lower guardrail breached: cut toward the implied capacity.
            target = impliedSpending;
        } else {
            // At/above the lower guardrail: recover toward the planned schedule, ceilinged by the
            // upper guardrail (which is >= plan, so the plan cap below binds first).
            target = Math.min(plannedSpending, adapt.corridorHigh()[y]);
        }

        double maxUp = prevSpending * (1 + rate);
        double maxDown = prevSpending * (1 - rate);
        double adapted = Math.max(maxDown, Math.min(maxUp, target));
        adapted = Math.min(adapted, plannedSpending);   // no prosperity ratchet
        return Math.max(adapted, floor);                // essential floor inviolate
    }

    private TrialSpending() {
    }
}
