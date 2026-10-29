package com.wealthview.projection;

import org.springframework.lang.Nullable;

/**
 * Sub-project B (stochastic mortality), task 6: one survivor phase's full set of per-year income
 * and tax arrays, pre-computed ONCE per run for a FIXED survivor identity (see
 * {@link TrialSimulator#PRIMARY_SURVIVES}/{@link TrialSimulator#SPOUSE_SURVIVES}). Under stochastic
 * mortality either spouse can be the survivor and the first death lands at a per-trial year, so —
 * unlike the fixed-death path, which pre-splices a single survivor phase into the base arrays at
 * one known index — the base arrays passed to {@link TrialSimulator#simulateTrial} carry the JOINT
 * (both-alive) phase and each trial splices in the regime for its OWN survivor identity from its OWN
 * transition index (three-regime precompute + per-trial splice, Approach A).
 *
 * <p>All arrays are year-indexed over the full horizon; only the {@code [transitionIdx, years)} tail
 * is ever read for a given trial (the head comes from the joint base). Built SINGLE-filing with that
 * survivor's own age-65 deduction and keep-larger-SS + survivor-percent income — the survivor phase
 * the fixed-death engine already builds, generalized to emit BOTH identities. {@code
 * ltcgTaxTableByYear}/{@code dsBracketCeilingByYear}/{@code rentalIncomeByYear} mirror the config's
 * own nullability (null ⇒ that mechanism is inactive this run). The survivor spending factor is NOT
 * folded in here — it stays a cheap in-loop multiply from the transition index (shared with the
 * fixed-death path via {@link HouseholdMcResolver#scaleFromTransition}), so there is no scaled copy.
 */
record SurvivorRegime(
        double[] income, double[] surplusTax, double[] ordinaryBaseIncomeByYear,
        OrdinaryTaxTable[] ordinaryTaxTableByYear,
        @Nullable LtcgTaxTable[] ltcgTaxTableByYear,
        @Nullable double[] dsBracketCeilingByYear,
        @Nullable double[] rentalIncomeByYear) {}
