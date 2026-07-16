package com.wealthview.projection;

import org.springframework.lang.Nullable;

/**
 * MC simulation run parameters — how many trials, over what time horizon, with what returns.
 *
 * <p>{@code portfolioPaths} is the blended cumulative total-portfolio path (floor/corridor calcs);
 * {@code taxableReturns}/{@code traditionalReturns}/{@code rothReturns} are the per-trial per-pool
 * real return sequences the trial simulator grows each pool at. {@code rmdStartAge} (derived from
 * the account owner's birth year via {@link RmdCalculator#rmdStartAge}) is carried here so both
 * {@link SustainabilitySearch} and {@link GuardrailResponseBuilder} build every
 * {@link TrialSimulator.SimulationConfig} from the same value.
 *
 * <p>{@code returnMean} is the RESOLVED effective growth assumption for the Roth-conversion
 * simulator — a real, fee-adjusted rate produced once by
 * {@link OptimizationContextBuilder#resolveReturnMean} (audit C4) and consumed by both
 * {@link JointConversionSearch} (drives the {@code ConversionSimulator}) and
 * {@link GuardrailResponseBuilder} (echoed on the response so users see the rate actually used).
 *
 * <p>{@code interestYield} (audit C1) is the scenario's bond/cash-sleeve nominal interest-yield
 * proxy, resolved once (falling back to {@code ScenarioParamsParser.DEFAULT_INTEREST_YIELD} when
 * unset) exactly like {@code dividendYield}. {@code taxableEquityShare} is the taxable pool's
 * balance-weighted equity share ({@link PoolStrategy#taxableEquityShare}), used to split the
 * taxable pool's annual yield between qualified-dividend treatment (the equity share, at {@code
 * dividendYield}) and ordinary-interest treatment (the bond+cash share, at {@code interestYield})
 * in {@link TrialSimulator}, exactly like the deterministic engine's {@code PoolStrategy.MultiPool}.
 */
record SimulationParameters(
        int retirementYear, int retirementAge, int endAge, int years,
        int trialCount, double confidenceLevel, double inflationRate,
        double[][] portfolioPaths,
        double[][] taxableReturns, double[][] traditionalReturns, double[][] rothReturns,
        int rmdStartAge,
        double dividendYield,
        double feeRate,
        double returnMean,
        double interestYield,
        double taxableEquityShare,
        // Household task 6: the survivor spending factor (1.0 ⇒ single-person / no transition). The
        // floors/discretionary arrays are ALREADY pre-scaled by it from the transition index; this
        // raw value is carried only so GuardrailResponseBuilder can scale its own freshly-filled
        // original-floor disclosure array consistently.
        double survivorSpendingFactor,
        // Household task 6: the first-death transition params for the MC trials ({@code null} ⇒
        // single-person, the byte-identical anchor). Threaded into every
        // {@link TrialSimulator.SimulationConfig} so the search and the terminal/response passes run
        // the identical household economics.
        @Nullable TrialSimulator.HouseholdSim household,
        // Sub-project B (stochastic mortality), task 5: the per-trial sampled death-age draws
        // ({@code null} ⇒ toggle off / single-person, the byte-identical anchor — the fixed-death
        // {@code household} transition indices above drive every trial then). Precomputed on a
        // DEDICATED rng stream by {@link MortalityDrawGenerator} and threaded here for task 6, which
        // will splice each trial's own {@code transitionIdx}/{@code truncateIdx}/{@code
        // survivorIsPrimary} into the trial loop instead of the single fixed set.
        @Nullable MortalityDraws mortalityDraws
) {}
