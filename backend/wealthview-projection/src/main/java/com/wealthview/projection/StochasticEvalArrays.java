package com.wealthview.projection;

import org.springframework.lang.Nullable;

/**
 * Sub-project B (stochastic mortality), task 6: the JOINT (both-alive) per-year income/tax arrays plus
 * the two precomputed survivor regimes, carried on {@link SimulationParameters} for the SEPARATE
 * stochastic evaluation pass ({@link StochasticMortalityEvaluator}). {@code null} unless the run opted
 * into stochastic mortality (a household with a loaded table) — the recommendation flow never reads it,
 * so the fixed-death / single-person engine is byte-identical.
 *
 * <p>The recommendation (spending schedule shown to the user) stays A's fixed-death optimizer output;
 * this bundle drives ONLY the longevity-aware success number computed beside it. The base arrays here
 * are the both-alive phase (built with a context in which both spouses outlive the horizon, so every
 * modeled year uses the joint MFJ per-person age-65 deduction and both owners' income windows), and
 * {@code jointFloors} is the essential floor UNSCALED by the survivor factor — the per-trial splice
 * ({@link TrialSimulator#simulateTrial}) re-applies the survivor income/tables and the ×factor from
 * each trial's OWN sampled first-death index. {@code survivorRegimes} is indexed by
 * {@link TrialSimulator#PRIMARY_SURVIVES}/{@link TrialSimulator#SPOUSE_SURVIVES}.
 * {@code jointOrdinaryTables}/{@code jointLtcg}/{@code jointDsCeiling}/{@code jointRental} mirror the
 * config's own nullability (null ⇒ that mechanism is inactive this run).
 */
record StochasticEvalArrays(
        double[] jointIncome, double[] jointSurplusTax, double[] jointFloors,
        @Nullable double[] jointOrdinaryBase, @Nullable OrdinaryTaxTable[] jointOrdinaryTables,
        @Nullable LtcgTaxTable[] jointLtcg, @Nullable double[] jointDsCeiling,
        @Nullable double[] jointRental,
        TrialSimulator.SurvivorRegime[] survivorRegimes) {}
