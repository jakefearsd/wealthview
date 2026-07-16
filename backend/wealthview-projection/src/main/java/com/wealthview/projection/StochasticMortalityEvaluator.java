package com.wealthview.projection;

import java.util.Objects;

/**
 * Sub-project B (stochastic mortality), task 6: the SEPARATE stochastic evaluation pass. It re-runs the
 * Monte Carlo trials over the ALREADY-optimized (fixed-death) spending schedule — the recommendation is
 * NOT re-derived — but with per-trial sampled deaths ({@link MortalityDraws}) spliced in via the task-6
 * three-regime mechanism ({@link TrialSimulator.SurvivorRegime} + {@link TrialSimulator.HouseholdSim#
 * withTrialMortality}). It produces the longevity-aware success number that sub-project B adds BESIDE
 * the fixed-death result.
 *
 * <p>The base arrays are the JOINT (both-alive) phase and the essential floor is UNSCALED
 * ({@link StochasticEvalArrays}); each trial splices in its own survivor identity's regime and the
 * ×survivor-factor from its OWN first-death index. The output is the raw per-trial
 * {@link StochasticMortalityEvaluation}; task 7 aggregates it into the user-facing summary.
 *
 * <p>Only invoked for a run with a non-null {@link SimulationParameters#stochasticEval()} (a household
 * that opted into stochastic mortality), so it never touches the fixed-death / single-person engine.
 */
final class StochasticMortalityEvaluator {

    private final TrialSimulator trialSimulator;

    StochasticMortalityEvaluator(TrialSimulator trialSimulator) {
        this.trialSimulator = trialSimulator;
    }

    /**
     * Runs the evaluation pass over {@code jointDiscretionary} (the fixed-death-optimized discretionary
     * schedule, UNSCALED by the survivor factor — the per-trial splice re-applies it). Returns the raw
     * per-trial success flags plus the sampled first/second death ages (straight from the draws).
     */
    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable argument
    // list -- varargs would change the call contract and invite accidental misuse.
    // NPathComplexity: the per-trial config assembly fans out over independent pool/tax guards (the
    // same shape SustainabilitySearch.runTrials / GuardrailResponseBuilder carry a narrow suppression
    // for) -- each branch is a trivial simPools-gated array pick, far simpler than the NPath number.
    @SuppressWarnings({"PMD.UseVarargs", "PMD.NPathComplexity"})
    StochasticMortalityEvaluation evaluate(OptimizationSetup ctx, double[] jointDiscretionary,
                                           double[] conversionByYear, double[] conversionTaxByYear) {
        SimulationParameters sim = ctx.sim();
        // Both are non-null by the caller's guard (evaluate is only invoked for a stochastic run — see
        // MonteCarloSpendingOptimizer#evaluateStochasticMortality); requireNonNull states that contract.
        StochasticEvalArrays arrays = Objects.requireNonNull(
                sim.stochasticEval(), "stochastic evaluation requires the joint/regime arrays");
        MortalityDraws draws = Objects.requireNonNull(
                sim.mortalityDraws(), "stochastic evaluation requires the per-trial mortality draws");
        // The base fixed-death HouseholdSim is withered per trial (transition/truncate/survivor from the
        // draws). Non-null for a stochastic run (always a two-person household); requireNonNull states it.
        TrialSimulator.HouseholdSim baseHousehold = Objects.requireNonNull(
                sim.household(), "stochastic evaluation requires a household");
        PortfolioSetup portfolio = ctx.portfolio();
        int trialCount = sim.trialCount();
        int years = sim.years();

        // Pool setup mirrors GuardrailResponseBuilder's terminal pass: with a conversion schedule or
        // any traditional/Roth pool, simulate the fixed pool balances; otherwise the whole portfolio
        // sits in the taxable pool. jointOrdinary/jointLtcg/... are only wired when pools are simulated.
        boolean simPools = conversionByYear != null
                || portfolio.initTraditional() > 0 || portfolio.initRoth() > 0;
        double initTaxable = simPools ? portfolio.initTaxable() : portfolio.initialPortfolio();
        double initTraditional = simPools ? portfolio.initTraditional() : 0;
        double initRoth = simPools ? portfolio.initRoth() : 0;
        String order = simPools && portfolio.withdrawalOrder() != null
                ? portfolio.withdrawalOrder() : "taxable_first";
        OrdinaryTaxTable[] ordinaryTables = simPools ? arrays.jointOrdinaryTables() : null;
        double[] ordinaryBase = simPools ? arrays.jointOrdinaryBase() : null;
        LtcgTaxTable[] ltcgTables = simPools ? arrays.jointLtcg() : null;
        double[] rental = simPools ? arrays.jointRental() : null;

        boolean[] success = new boolean[trialCount];
        for (int t = 0; t < trialCount; t++) {
            TrialSimulator.HouseholdSim household = baseHousehold.withTrialMortality(
                    draws.transitionIdx()[t], draws.survivorIsPrimary()[t], draws.truncateIdx()[t]);
            var config = new TrialSimulator.SimulationConfig(
                    initTaxable, initTraditional, initRoth, order,
                    ordinaryTables, ordinaryBase, conversionByYear, conversionTaxByYear,
                    sim.retirementAge(), arrays.jointDsCeiling(),
                    portfolio.cashReserveYears(), portfolio.cashReturnRate(), false,
                    sim.taxableReturns()[t], sim.traditionalReturns()[t], sim.rothReturns()[t],
                    sim.rmdStartAge(), portfolio.initTaxableBasis(), ltcgTables, sim.dividendYield(),
                    null, rental, sim.interestYield(), sim.taxableEquityShare(), household,
                    arrays.survivorRegimes(), sim.survivorSpendingFactor());
            var result = trialSimulator.simulateTrial(arrays.jointIncome(), arrays.jointSurplusTax(),
                    arrays.jointFloors(), jointDiscretionary, years, config);
            success[t] = result.success();
        }
        return new StochasticMortalityEvaluation(success, draws.firstDeathAge(), draws.secondDeathAge());
    }
}
