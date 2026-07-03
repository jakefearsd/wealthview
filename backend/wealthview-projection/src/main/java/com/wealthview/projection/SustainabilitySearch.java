package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.wealthview.core.common.CompoundGrowth;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;

/**
 * Searches for the maximum sustainable discretionary spending plan given a set of
 * Monte Carlo portfolio paths, and verifies whether a candidate plan survives at the
 * required confidence level.
 *
 * <p>Extracted from {@code MonteCarloSpendingOptimizer} during the Phase 3 decomposition.
 * The simulation-invariant inputs (paths, income, tax context, etc.) are grouped into a
 * {@link SearchContext} parameter object so the per-phase binary searches no longer
 * thread fifteen-argument calls. All numeric logic — binary-search iteration counts,
 * percentile thresholds, target capping — is unchanged.
 */
final class SustainabilitySearch {

    private static final double MAX_SPENDING_CEILING = 500_000;
    /** Binary search iterations used in {@link #evaluateSustainableSpending}. */
    private static final int SPENDING_BINARY_SEARCH_ITERATIONS = 30;
    /** Binary search iterations used in {@link #binarySearchDiscretionary}. */
    private static final int PHASE_BINARY_SEARCH_ITERATIONS = 40;

    private final TrialSimulator trialSimulator;

    SustainabilitySearch(TrialSimulator trialSimulator) {
        this.trialSimulator = trialSimulator;
    }

    /**
     * Simulation-invariant inputs to a sustainability search run. Bundling them as a
     * single parameter object keeps the recursive search call sites concise.
     */
    record SearchContext(
            double[][] paths, double[] income, double[] surplusTax,
            double terminalTarget, int retirementAge, int years, int trialCount,
            double confidenceLevel, double portfolioFloor,
            int cashReserveYears, double cashReturnRate, double inflationRate,
            TaxContext taxCtx, double[] conversionByYear, double[] conversionTaxByYear,
            double[] dsBracketCeilingByYear) {}

    /**
     * Verifies the inflation-adjusted essential floor against portfolio capacity at the
     * required confidence level, returning the affordable floor for each retirement year.
     */
    // AvoidArrayLoops: PMD sees `floors[y] = inflatedFloors[y]` and assumes a plain array copy,
    // but the assignment is conditional (the else branch clamps to portfolio capacity), so
    // Arrays.copyOf / System.arraycopy is not an equivalent substitute.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    static double[] verifyEssentialFloor(double[][] paths, double[] income,
                                          double essentialFloor,
                                          double confidenceLevel, int years, int trialCount,
                                          double inflationRate) {
        double[] floors = new double[years];
        int confidenceIndex = (int) Math.ceil((1 - confidenceLevel) * trialCount) - 1;
        confidenceIndex = Math.max(0, Math.min(confidenceIndex, trialCount - 1));

        // Pre-compute inflation-adjusted floor withdrawals per year
        double[] inflatedFloors = new double[years];
        double[] floorWithdrawals = new double[years];
        for (int y = 0; y < years; y++) {
            inflatedFloors[y] = CompoundGrowth.inflate(essentialFloor, inflationRate, y);
            floorWithdrawals[y] = Math.max(0, inflatedFloors[y] - income[y]);
        }

        // Simulate year-by-year balances with floor withdrawals and compounded growth,
        // advancing every trial one year per outer iteration. Using raw cumulative
        // subtraction from the unconstrained path would overestimate the remaining
        // balance because withdrawn dollars would have compounded if left.
        double[] balances = new double[trialCount];
        for (int t = 0; t < trialCount; t++) {
            balances[t] = paths[t][0];
        }
        double[] balancesAtYear = new double[trialCount];
        for (int y = 0; y < years; y++) {
            for (int t = 0; t < trialCount; t++) {
                double growthFactor = paths[t][y + 1] / paths[t][y];
                balances[t] = Math.max(0, balances[t] * growthFactor - floorWithdrawals[y]);
                balancesAtYear[t] = balances[t];
            }
            Arrays.sort(balancesAtYear);

            double availableAtConfidence = balancesAtYear[confidenceIndex];
            // Capacity = portfolio remaining after cumulative floor withdrawals + this year's income.
            // Terminal target is NOT subtracted here — essential spending is essential.
            // The terminal target constrains discretionary spending via isSustainable().
            double capacityForFloor = availableAtConfidence + income[y];

            if (capacityForFloor >= inflatedFloors[y]) {
                floors[y] = inflatedFloors[y];
            } else {
                floors[y] = Math.max(0, Math.min(inflatedFloors[y], capacityForFloor));
            }
        }
        return floors;
    }

    /**
     * Allocates the maximum sustainable discretionary spending per year. With no phases,
     * a single uniform level is found. With target-bearing phases, allocation is capped
     * to the per-phase target; otherwise phases are filled greedily by priority weight.
     */
    double[] allocateSpending(SearchContext ctx, double[] floors,
                              List<GuardrailPhaseInput> phases) {
        double[] discretionary = new double[ctx.years()];

        double[][] returns = nominalReturns(ctx);

        if (phases == null || phases.isEmpty()) {
            double maxDisc = binarySearchDiscretionary(ctx, returns, floors, discretionary,
                    0, ctx.years() - 1);
            Arrays.fill(discretionary, maxDisc);
            return discretionary;
        }

        // Check if any phase has target spending — use target-based allocation
        boolean hasTargets = phases.stream()
                .anyMatch(p -> p.targetSpending() != null
                        && p.targetSpending().compareTo(BigDecimal.ZERO) > 0);

        if (hasTargets) {
            return allocateByTargets(ctx, returns, floors, phases);
        }

        // Legacy: sort phases by priority weight (highest first)
        var sortedPhases = phases.stream()
                .sorted(Comparator.comparingInt(GuardrailPhaseInput::priorityWeight).reversed())
                .toList();

        for (var phase : sortedPhases) {
            int phaseStart = phase.startAge() - ctx.retirementAge();
            int phaseEnd = phase.endAge() != null
                    ? Math.min(phase.endAge() - ctx.retirementAge(), ctx.years() - 1)
                    : ctx.years() - 1;
            phaseStart = Math.max(0, phaseStart);
            phaseEnd = Math.min(phaseEnd, ctx.years() - 1);

            if (phaseStart > phaseEnd) {
                continue;
            }

            double maxDisc = binarySearchDiscretionary(ctx, returns, floors, discretionary,
                    phaseStart, phaseEnd);

            for (int y = phaseStart; y <= phaseEnd; y++) {
                discretionary[y] = maxDisc;
            }
        }

        return discretionary;
    }

    private double[] allocateByTargets(SearchContext ctx, double[][] returns, double[] floors,
                                       List<GuardrailPhaseInput> phases) {
        double[] discretionary = new double[ctx.years()];

        for (var phase : phases) {
            int phaseStart = phase.startAge() - ctx.retirementAge();
            int phaseEnd = phase.endAge() != null
                    ? Math.min(phase.endAge() - ctx.retirementAge(), ctx.years() - 1)
                    : ctx.years() - 1;
            phaseStart = Math.max(0, phaseStart);
            phaseEnd = Math.min(phaseEnd, ctx.years() - 1);

            if (phaseStart > phaseEnd) {
                continue;
            }

            double found = binarySearchDiscretionary(ctx, returns, floors, discretionary,
                    phaseStart, phaseEnd);

            double capped;
            if (phase.targetSpending() != null
                    && phase.targetSpending().compareTo(BigDecimal.ZERO) > 0) {
                double avgFloor = 0;
                double avgInflatedTarget = 0;
                int count = 0;
                double nominalTarget = phase.targetSpending().doubleValue();
                for (int y = phaseStart; y <= phaseEnd; y++) {
                    avgFloor += floors[y];
                    avgInflatedTarget += CompoundGrowth.inflate(nominalTarget, ctx.inflationRate(), y);
                    count++;
                }
                avgFloor = count > 0 ? avgFloor / count : 0;
                avgInflatedTarget = count > 0 ? avgInflatedTarget / count : nominalTarget;
                double maxDiscretionary = Math.max(0, avgInflatedTarget - avgFloor);
                capped = Math.min(found, maxDiscretionary);
            } else {
                capped = found;
            }

            for (int y = phaseStart; y <= phaseEnd; y++) {
                discretionary[y] = capped;
            }
        }

        return discretionary;
    }

    /**
     * Evaluates the total sustainable first-year spending (essentialFloor + discretionary)
     * for a given conversion schedule. Used by the joint search to score candidate fractions.
     */
    // UseVarargs: the trailing double[] is a per-year indexed floor array, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    double evaluateSustainableSpending(SearchContext ctx, double[] floors) {
        double low = 0;
        double high = MAX_SPENDING_CEILING;
        double[][] returns = nominalReturns(ctx);
        double[] testDiscretionary = new double[ctx.years()];

        for (int iter = 0; iter < SPENDING_BINARY_SEARCH_ITERATIONS; iter++) {
            double mid = (low + high) / 2;
            Arrays.fill(testDiscretionary, mid);

            if (isSustainable(ctx, returns, floors, testDiscretionary)) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return floors[0] + low;
    }

    private double binarySearchDiscretionary(SearchContext ctx, double[][] returns, double[] floors,
                                             double[] currentDiscretionary,
                                             int phaseStart, int phaseEnd) {
        double low = 0;
        double high = MAX_SPENDING_CEILING;

        for (int iter = 0; iter < PHASE_BINARY_SEARCH_ITERATIONS; iter++) {
            double mid = (low + high) / 2;

            double[] testDiscretionary = currentDiscretionary.clone();
            for (int y = phaseStart; y <= phaseEnd; y++) {
                testDiscretionary[y] = mid;
            }

            if (isSustainable(ctx, returns, floors, testDiscretionary)) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return low;
    }

    /**
     * Derives per-trial nominal returns from the path ratios once per search run. The
     * binary searches call {@code isSustainable} hundreds of times against the same
     * immutable paths, so recomputing the ratios per call would dominate the loop.
     * Package-private so external repeated-check loops (the MC optimizer's
     * reduce-until-sustainable pass) can precompute once too.
     */
    static double[][] nominalReturns(SearchContext ctx) {
        double[][] paths = ctx.paths();
        double[][] returns = new double[ctx.trialCount()][ctx.years()];
        for (int t = 0; t < ctx.trialCount(); t++) {
            for (int y = 0; y < ctx.years(); y++) {
                returns[t][y] = paths[t][y + 1] / paths[t][y] - 1.0;
            }
        }
        return returns;
    }

    /**
     * Returns true when the given discretionary plan keeps the portfolio above the
     * terminal target (and portfolio floor, if set) at the required confidence level.
     * {@code returns} must come from {@link #nominalReturns(SearchContext)} — callers
     * checking repeatedly against the same context precompute it once.
     */
    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    // NPathComplexity: the trial-loop body fans out over independent per-year guards; the path
    // count is multiplicative but each branch is a trivial comparison, so it stays readable.
    @SuppressWarnings({"PMD.UseVarargs", "PMD.NPathComplexity"})
    boolean isSustainable(SearchContext ctx, double[][] returns, double[] floors,
                          double[] discretionary) {
        int trialCount = ctx.trialCount();
        int years = ctx.years();
        double[][] paths = ctx.paths();
        TaxContext taxCtx = ctx.taxCtx();

        double[] finalBalances = new double[trialCount];
        double[] minBalances = new double[trialCount];

        boolean hasPools = taxCtx != null
                && (taxCtx.initTraditional() > 0 || taxCtx.initRoth() > 0);
        double initTaxable = hasPools ? taxCtx.initTaxable() : paths[0][0];
        double initTraditional = hasPools ? taxCtx.initTraditional() : 0;
        double initRoth = hasPools ? taxCtx.initRoth() : 0;
        String order = hasPools ? taxCtx.withdrawalOrder() : "taxable_first";
        double[] marginalRates = hasPools ? taxCtx.marginalRateByYear() : null;

        var config = new TrialSimulator.SimulationConfig(
                initTaxable, initTraditional, initRoth, order, marginalRates,
                ctx.conversionByYear(), ctx.conversionTaxByYear(), ctx.retirementAge(),
                ctx.dsBracketCeilingByYear(), ctx.cashReserveYears(), ctx.cashReturnRate(), false);

        for (int t = 0; t < trialCount; t++) {
            // For non-pool trials, initial balance varies per trial
            TrialSimulator.SimulationConfig trialConfig = hasPools ? config
                    : new TrialSimulator.SimulationConfig(
                            paths[t][0], 0, 0, order, null,
                            ctx.conversionByYear(), ctx.conversionTaxByYear(), ctx.retirementAge(),
                            ctx.dsBracketCeilingByYear(), ctx.cashReserveYears(),
                            ctx.cashReturnRate(), false);

            var result = trialSimulator.simulateTrial(returns[t], ctx.income(), ctx.surplusTax(),
                    floors, discretionary, years, trialConfig);
            finalBalances[t] = result.finalBalance();
            minBalances[t] = result.minBalance();
        }

        Arrays.sort(finalBalances);
        double balanceAtConfidence =
                PercentileCalculator.percentile(finalBalances, 1.0 - ctx.confidenceLevel());
        if (balanceAtConfidence < ctx.terminalTarget()) {
            return false;
        }

        if (ctx.portfolioFloor() > 0) {
            Arrays.sort(minBalances);
            double minAtConfidence =
                    PercentileCalculator.percentile(minBalances, 1.0 - ctx.confidenceLevel());
            if (minAtConfidence < ctx.portfolioFloor()) {
                return false;
            }
        }

        return true;
    }
}
