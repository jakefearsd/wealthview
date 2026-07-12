package com.wealthview.projection;

/**
 * Builds the {@link TrialSimulator.GuardrailAdaptation} rule inputs (audit C9 corridor +
 * median-proxy derivation) from a candidate discretionary spending schedule.
 *
 * <p>Extracted (T24) so the reporting-only with-rules pass ({@link GuardrailResponseBuilder},
 * which derives the rule inputs from the FINAL optimized schedule) and the in-search gate
 * ({@link SustainabilitySearch}, which derives them from each CANDIDATE schedule the binary search
 * evaluates) share ONE derivation instead of forking it. Both callers supply their own
 * {@code medianBalanceByYear} (a no-adaptation, tracked trial pass over the SAME candidate
 * schedule) — this class only does the candidate-schedule-shaped corridor math and array assembly
 * that is otherwise identical between the two call sites.
 */
final class GuardrailRuleInputBuilder {

    private GuardrailRuleInputBuilder() {
    }

    /**
     * Builds the corridor and {@code expectedStartBalance} rule inputs for one candidate schedule.
     *
     * @param paths raw Monte Carlo portfolio paths (unsimulated growth trajectories), used only to
     *         derive the displayed corridor via {@link SpendingCorridorCalculator}
     * @param income per-year outside income
     * @param floors per-year essential-floor spending for this candidate
     * @param discretionary per-year discretionary spending for this candidate — the schedule under
     *         evaluation
     * @param years projection horizon
     * @param trialCount number of Monte Carlo trials
     * @param initTotalBalance the initial total portfolio balance (taxable + traditional + roth),
     *         used to seed {@code expectedStartBalance[0]}
     * @param medianBalanceByYear the no-adaptation MEDIAN total-portfolio balance at the END of
     *         each year, from an actual (tracked) trial pass over this candidate schedule — the
     *         caller's responsibility to compute, since it requires running trials through
     *         {@link TrialSimulator}, which this class deliberately does not do (keeps the shared
     *         derivation pure array/corridor math, with no simulation dependency)
     * @param maxAnnualAdjustmentRate the user's year-over-year adjustment-rate knob
     * @return the {@link TrialSimulator.GuardrailAdaptation} rule inputs for this candidate schedule
     */
    static TrialSimulator.GuardrailAdaptation build(double[][] paths, double[] income,
            double[] floors, double[] discretionary, int years, int trialCount,
            double initTotalBalance, double[] medianBalanceByYear, double maxAnnualAdjustmentRate) {
        double[][] corridors = SpendingCorridorCalculator.computeCorridors(
                paths, income, floors, discretionary, years, trialCount);
        SpendingCorridorCalculator.smoothCorridors(corridors[0], corridors[1], years);

        // Clamp corridors to bracket recommended spending (smoothing can overshoot at phase
        // boundaries) -- mirrors GuardrailResponseBuilder.build's identical clamp on the final
        // schedule.
        for (int y = 0; y < years; y++) {
            double recommended = floors[y] + discretionary[y];
            corridors[0][y] = Math.min(corridors[0][y], recommended);
            corridors[1][y] = Math.max(corridors[1][y], recommended);
        }

        double[] expectedStartBalance = new double[years];
        expectedStartBalance[0] = initTotalBalance;
        if (years > 1) {
            System.arraycopy(medianBalanceByYear, 0, expectedStartBalance, 1, years - 1);
        }

        return new TrialSimulator.GuardrailAdaptation(
                corridors[0], corridors[1], expectedStartBalance, maxAnnualAdjustmentRate);
    }
}
