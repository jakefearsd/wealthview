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

    private final TrialPassRunner trialPassRunner;

    StochasticMortalityEvaluator(TrialSimulator trialSimulator) {
        this.trialPassRunner = new TrialPassRunner(trialSimulator);
    }

    /**
     * Runs the evaluation pass over {@code jointDiscretionary} (the fixed-death-optimized discretionary
     * schedule, UNSCALED by the survivor factor — the per-trial splice re-applies it). Returns the raw
     * per-trial success flags plus the sampled first/second death ages (straight from the draws).
     */
    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable argument
    // list -- varargs would change the call contract and invite accidental misuse.
    // Task 12: the NPathComplexity suppression this method used to carry is gone -- the per-trial
    // config assembly (the fan-out over pool/tax guards) moved into TrialPassRunner/TrialConfigFactory,
    // so this method is now a linear sequence of guards + one builder chain, well under the threshold.
    @SuppressWarnings("PMD.UseVarargs")
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

        // Pool setup mirrors GuardrailResponseBuilder's terminal pass (task 12: now the SAME shared
        // PoolSimSetup.resolve(PortfolioSetup, ...) implementation, not a hand-maintained copy): with
        // a conversion schedule or any traditional/Roth pool, simulate the fixed pool balances;
        // otherwise the whole portfolio sits in the taxable pool. The joint-array tax tables are only
        // wired when pools are simulated.
        var poolSetup = PoolSimSetup.resolve(portfolio, conversionByYear);
        var configFactory = TrialConfigFactory.builder(poolSetup)
                .taxTables(poolSetup.simPools() ? arrays.jointOrdinaryTables() : null,
                        poolSetup.simPools() ? arrays.jointOrdinaryBase() : null)
                .conversions(conversionByYear, conversionTaxByYear)
                .ages(sim.retirementAge(), sim.rmdStartAge())
                .dsBracketCeilingByYear(arrays.jointDsCeiling())
                .cashReserve(portfolio.cashReserveYears(), portfolio.cashReturnRate())
                .returns(sim.taxableReturns(), sim.traditionalReturns(), sim.rothReturns())
                .taxableBasis(portfolio.initTaxableBasis())
                .ltcgTaxTableByYear(poolSetup.simPools() ? arrays.jointLtcg() : null)
                .dividendYield(sim.dividendYield())
                .rentalIncomeByYear(poolSetup.simPools() ? arrays.jointRental() : null)
                .interestYield(sim.interestYield())
                .taxableEquityShare(sim.taxableEquityShare())
                .survivorRegimes(arrays.survivorRegimes(), sim.survivorSpendingFactor())
                // No .household(...)/.trackYearBalances(...)/.adaptation(...) here: every trial below
                // supplies its OWN spliced household via the per-trial override (this factory's
                // baked-in household default is never read), and this evaluation pass never tracks
                // per-year balances or runs the guardrail-adaptation rule -- the two CRITICAL
                // asymmetries from GuardrailResponseBuilder/SustainabilitySearch's chains (see the
                // task-12 report) preserved by simply never varying them off their false/null default.
                .build();

        var pass = trialPassRunner.run(trialCount, years, configFactory,
                arrays.jointIncome(), arrays.jointSurplusTax(), arrays.jointFloors(), jointDiscretionary,
                null, false,
                t -> baseHousehold.withTrialMortality(
                        draws.transitionIdx()[t], draws.survivorIsPrimary()[t], draws.truncateIdx()[t]));
        return new StochasticMortalityEvaluation(pass.successFlags(), draws.firstDeathAge(), draws.secondDeathAge());
    }
}
