package com.wealthview.projection;

/**
 * Simulated guardrail-adaptation rule inputs (audit C9). When a
 * {@link SimulationConfig#adaptation()} is present the trial adapts the year's DISCRETIONARY
 * spending in-simulation toward the DISPLAYED spending corridor instead of running the fixed
 * planned schedule; when it is {@code null} the trial runs the fixed schedule exactly as before
 * (byte-identical no-rules behavior). A single instance is built once per run and shared across
 * every trial (allocation-free hot loop), so this is NOT a per-trial object.
 *
 * <p>All four arrays are indexed by projection year:
 * <ul>
 *   <li>{@code corridorLow}/{@code corridorHigh} — the final, smoothed-and-clamped displayed
 *       corridor (the very arrays that populate {@code GuardrailYearlySpending}). The rule keys
 *       off {@code corridorLow}: only the LOWER guardrail can bind in-simulation, because
 *       constraint (2) caps adapted spending at the planned schedule (no prosperity ratchet) and
 *       {@code corridorHigh} is always {@code >=} the plan — the upper band is display-only here.
 *   <li>{@code expectedStartBalance} — the no-adaptation median start-of-year total portfolio
 *       (year 0 = initial portfolio; year y&gt;0 = the prior year's median end-of-year balance
 *       from the headline terminal pass). The trial's own start-of-year balance is measured
 *       against this to form the {@code trialPortfolio / expectedMedianPortfolio[y]} sensitivity.
 * </ul>
 * {@code maxAnnualAdjustmentRate} is the existing user knob bounding the year-over-year change.
 */
record GuardrailAdaptation(
        double[] corridorLow, double[] corridorHigh,
        double[] expectedStartBalance, double maxAnnualAdjustmentRate) {}
