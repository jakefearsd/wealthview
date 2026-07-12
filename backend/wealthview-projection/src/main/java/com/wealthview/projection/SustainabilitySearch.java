package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.lang.Nullable;

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
     *
     * @param gateOnAdaptiveRules T24: when {@code true} (and {@code maxAnnualAdjustmentRate} is
     *         positive), {@link #isSustainable} evaluates each candidate schedule WITH the audit-C9
     *         simulated guardrail-adaptation rule active (derived fresh per candidate via {@link
     *         #buildCandidateAdaptation}) and gates on ITS success rate, instead of the original
     *         no-adaptation success rate. {@code false} (every pre-T24 caller) preserves the exact
     *         original single-pass behavior. T26: {@link JointConversionSearch}'s conversion-fraction
     *         scoring search now threads this straight from the profile's toggle too, so an arm's
     *         score reflects the SAME objective the discretionary search gates on. (The resulting
     *         joint-optimum coherence — gated-scored arms never worse than no-adapt-scored arms —
     *         empirically holds for the pinned fixture but is NOT a cross-search theorem: the joint
     *         search's grid + golden-section refine is local, and its final numbers come from a
     *         different path set than arm selection; see
     *         {@code JointConversionSearchGatedObjectiveTest}.)
     * @param maxAnnualAdjustmentRate the user's year-over-year adjustment-rate knob, resolved to a
     *         primitive {@code double} (0 when the request omitted it) -- needed here (not just on
     *         {@link TrialSimulator.GuardrailAdaptation}) because a non-positive rate makes the rule
     *         a no-op, in which case {@link #isSustainable} silently falls back to the no-adaptation
     *         gate even when {@code gateOnAdaptiveRules} is {@code true}.
     */
    record SearchContext(
            double[][] paths, double[] income, double[] surplusTax,
            double terminalTarget, int retirementAge, int years, int trialCount,
            double confidenceLevel, double portfolioFloor,
            int cashReserveYears, double cashReturnRate,
            TaxContext taxCtx, double[] conversionByYear, double[] conversionTaxByYear,
            double[] dsBracketCeilingByYear,
            double[][] taxableReturns, double[][] traditionalReturns, double[][] rothReturns,
            int rmdStartAge,
            double initTaxableBasis, LtcgTaxTable[] ltcgTaxTableByYear, double dividendYield,
            double interestYield, double taxableEquityShare,
            boolean gateOnAdaptiveRules, double maxAnnualAdjustmentRate,
            // Household task 6: first-death transition params, threaded into each trial config
            // ({@code null} ⇒ single-person). See {@link TrialSimulator.HouseholdSim}.
            @Nullable TrialSimulator.HouseholdSim household) {}

    /**
     * Verifies the essential floor against portfolio capacity at the required confidence level,
     * returning the affordable floor for each retirement year. Real-terms projection: the floor is
     * constant real (today's dollars), so it is NOT inflation-escalated across years.
     */
    // AvoidArrayLoops: PMD sees `floors[y] = realFloors[y]` and assumes a plain array copy,
    // but the assignment is conditional (the else branch clamps to portfolio capacity), so
    // Arrays.copyOf / System.arraycopy is not an equivalent substitute.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    static double[] verifyEssentialFloor(double[][] paths, double[] income,
                                          double essentialFloor,
                                          double confidenceLevel, int years, int trialCount) {
        double[] floors = new double[years];
        int confidenceIndex = (int) Math.ceil((1 - confidenceLevel) * trialCount) - 1;
        confidenceIndex = Math.max(0, Math.min(confidenceIndex, trialCount - 1));

        // Constant-real floor withdrawals per year (floor held constant in today's dollars).
        double[] inflatedFloors = new double[years];
        double[] floorWithdrawals = new double[years];
        for (int y = 0; y < years; y++) {
            inflatedFloors[y] = essentialFloor;
            floorWithdrawals[y] = Math.max(0, essentialFloor - income[y]);
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

        if (phases == null || phases.isEmpty()) {
            double maxDisc = binarySearchDiscretionary(ctx, floors, discretionary,
                    0, ctx.years() - 1);
            Arrays.fill(discretionary, maxDisc);
            return discretionary;
        }

        // Check if any phase has target spending — use target-based allocation
        boolean hasTargets = phases.stream()
                .anyMatch(p -> p.targetSpending() != null
                        && p.targetSpending().compareTo(BigDecimal.ZERO) > 0);

        if (hasTargets) {
            return allocateByTargets(ctx, floors, phases);
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

            double maxDisc = binarySearchDiscretionary(ctx, floors, discretionary,
                    phaseStart, phaseEnd);

            for (int y = phaseStart; y <= phaseEnd; y++) {
                discretionary[y] = maxDisc;
            }
        }

        return discretionary;
    }

    private double[] allocateByTargets(SearchContext ctx, double[] floors,
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

            double found = binarySearchDiscretionary(ctx, floors, discretionary,
                    phaseStart, phaseEnd);

            double capped;
            if (phase.targetSpending() != null
                    && phase.targetSpending().compareTo(BigDecimal.ZERO) > 0) {
                double avgFloor = 0;
                double avgTarget = 0;
                int count = 0;
                // Real-terms projection: the per-phase target is constant real (today's dollars).
                double realTarget = phase.targetSpending().doubleValue();
                for (int y = phaseStart; y <= phaseEnd; y++) {
                    avgFloor += floors[y];
                    avgTarget += realTarget;
                    count++;
                }
                avgFloor = count > 0 ? avgFloor / count : 0;
                avgTarget = count > 0 ? avgTarget / count : realTarget;
                double maxDiscretionary = Math.max(0, avgTarget - avgFloor);
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
     * Bisection assumes {@link #isSustainable} is monotone in the discretionary level — see the
     * T24 soundness caveat on {@link #binarySearchDiscretionary}. T26: this joint-search path can
     * now also run under the adaptive-rules gate, so the caveat applies here identically; the
     * empirical support is {@code GuardrailAdaptiveGateIntegrationTest}'s bisection sweep, which
     * pins the MAIN-search context (not re-swept per arm context) — empirical evidence for the
     * pinned fixtures, not a proof.
     */
    // UseVarargs: the trailing double[] is a per-year indexed floor array, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    double evaluateSustainableSpending(SearchContext ctx, double[] floors) {
        double low = 0;
        double high = MAX_SPENDING_CEILING;
        double[] testDiscretionary = new double[ctx.years()];

        for (int iter = 0; iter < SPENDING_BINARY_SEARCH_ITERATIONS; iter++) {
            double mid = (low + high) / 2;
            Arrays.fill(testDiscretionary, mid);

            if (isSustainable(ctx, floors, testDiscretionary)) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return floors[0] + low;
    }

    /**
     * Bisects the maximum sustainable uniform discretionary level for one phase window.
     *
     * <p>Bisection soundness caveat (T24): this search (like {@link #evaluateSustainableSpending})
     * assumes {@link #isSustainable} is MONOTONE in the discretionary level — sustainable at
     * {@code x} implies sustainable at every {@code x' < x}. On the classic no-adaptation gate that
     * holds trivially (lower spending only preserves balances). With the T24 adaptive-rules gate
     * the rule's corridor and median-reference inputs are themselves re-derived from each candidate
     * schedule, so cross-candidate monotonicity is NOT formally proven — like the
     * with-rules-&gt;=-no-rules success monotonicity (see {@code TrialSimulator#adaptYearSpending}),
     * it is empirically pinned: {@code GuardrailAdaptiveGateIntegrationTest}'s permanent grid sweep
     * (1000-unit steps plus 100-unit refinement through the transition, adaptation-gated stressed
     * fixture) asserts exactly one sustainable→unsustainable transition.
     */
    private double binarySearchDiscretionary(SearchContext ctx, double[] floors,
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

            if (isSustainable(ctx, floors, testDiscretionary)) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return low;
    }

    /**
     * Returns true when the given discretionary plan funds the essential floor in at
     * least {@code ctx.confidenceLevel()} of trials (the target success probability).
     * Terminal target and portfolio floor, when set, are evaluated as additional
     * bequest-style constraints on top of that primary success-rate gate — they no
     * longer drive sustainability on their own. Each trial grows its pools at the
     * per-pool real return sequences carried on the {@link SearchContext}
     * ({@code taxableReturns}/{@code traditionalReturns}/{@code rothReturns}).
     *
     * <p>T24: when {@code ctx.gateOnAdaptiveRules()} is set (and the adjustment rate is positive),
     * the gate is computed from a trial pass with the audit-C9 simulated guardrail-adaptation rule
     * active — see {@link #buildCandidateAdaptation}. Every pre-T24 caller (and every caller with
     * the toggle off) takes the exact original single no-adaptation pass, byte-identical.
     */
    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    boolean isSustainable(SearchContext ctx, double[] floors, double[] discretionary) {
        TrialSimulator.GuardrailAdaptation adaptation = null;
        if (ctx.gateOnAdaptiveRules() && ctx.maxAnnualAdjustmentRate() > 0) {
            adaptation = buildCandidateAdaptation(ctx, floors, discretionary);
        }
        TrialBatch batch = runTrials(ctx, floors, discretionary, adaptation, false);

        // Primary gate: the fraction of trials that fund the essential floor every year
        // must meet the target confidence (success probability).
        int successCount = 0;
        for (boolean success : batch.successFlags()) {
            if (success) {
                successCount++;
            }
        }
        double successRate = (double) successCount / ctx.trialCount();
        if (successRate < ctx.confidenceLevel()) {
            return false;
        }

        double[] finalBalances = batch.finalBalances();
        double[] minBalances = batch.minBalances();

        // Optional bequest constraints, layered on top of the success gate — only
        // enforced when the caller explicitly set a positive target/floor.
        if (ctx.terminalTarget() > 0) {
            Arrays.sort(finalBalances);
            double balanceAtConfidence =
                    PercentileCalculator.percentile(finalBalances, 1.0 - ctx.confidenceLevel());
            if (balanceAtConfidence < ctx.terminalTarget()) {
                return false;
            }
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

    /** One batch of {@code ctx.trialCount()} trial runs (T24 extraction of {@link #isSustainable}'s
     * original single-pass loop, generalized so it can also run the T24 reference/gate passes).
     * {@code yearBalances} is {@code [years][trialCount]} (year-major, matching
     * {@link GuardrailResponseBuilder}'s own layout) and {@code null} unless tracking was
     * requested. */
    private record TrialBatch(double[] finalBalances, double[] minBalances, boolean[] successFlags,
                               double[][] yearBalances) {}

    /**
     * Runs one batch of trials for {@code discretionary} under {@code adaptation} ({@code null} for
     * the fixed-schedule no-rules path). Byte-identical to {@code isSustainable}'s original inline
     * loop when called with {@code (ctx, floors, discretionary, null, false)} — every pre-T24 call
     * path and every T24 call with the toggle off takes exactly this shape.
     */
    // NPathComplexity: the trial-loop body fans out over independent per-year guards; the path
    // count is multiplicative but each branch is a trivial comparison, so it stays readable.
    // (UseVarargs does not apply: the last parameter is boolean, not an array.)
    @SuppressWarnings("PMD.NPathComplexity")
    private TrialBatch runTrials(SearchContext ctx, double[] floors, double[] discretionary,
                                  @Nullable TrialSimulator.GuardrailAdaptation adaptation,
                                  boolean trackYearBalances) {
        int trialCount = ctx.trialCount();
        int years = ctx.years();
        double[][] paths = ctx.paths();
        TaxContext taxCtx = ctx.taxCtx();

        double[] finalBalances = new double[trialCount];
        double[] minBalances = new double[trialCount];
        boolean[] successFlags = new boolean[trialCount];
        double[][] yearBalances = trackYearBalances ? new double[years][trialCount] : null;

        boolean hasPools = taxCtx != null
                && (taxCtx.initTraditional() > 0 || taxCtx.initRoth() > 0);
        double initTraditional = hasPools ? taxCtx.initTraditional() : 0;
        double initRoth = hasPools ? taxCtx.initRoth() : 0;
        String order = hasPools ? taxCtx.withdrawalOrder() : "taxable_first";
        OrdinaryTaxTable[] ordinaryTaxTables = hasPools ? taxCtx.ordinaryTaxTableByYear() : null;
        double[] ordinaryBaseIncomeByYear = hasPools ? taxCtx.ordinaryBaseIncomeByYear() : null;
        // T18a-3: threaded into the LTCG/NIIT bundle's Net Investment Income base.
        double[] rentalIncomeByYear = hasPools ? taxCtx.rentalIncomeByYear() : null;

        for (int t = 0; t < trialCount; t++) {
            // Pool case: fixed starting balances from the tax context. Non-pool case: the whole
            // portfolio sits in the taxable pool, starting balance varies per trial via paths[t][0].
            double initTaxable = hasPools ? taxCtx.initTaxable() : paths[t][0];
            var trialConfig = new TrialSimulator.SimulationConfig(
                    initTaxable, initTraditional, initRoth, order,
                    ordinaryTaxTables, ordinaryBaseIncomeByYear,
                    ctx.conversionByYear(), ctx.conversionTaxByYear(), ctx.retirementAge(),
                    ctx.dsBracketCeilingByYear(), ctx.cashReserveYears(), ctx.cashReturnRate(),
                    trackYearBalances,
                    ctx.taxableReturns()[t], ctx.traditionalReturns()[t], ctx.rothReturns()[t],
                    ctx.rmdStartAge(),
                    ctx.initTaxableBasis(), ctx.ltcgTaxTableByYear(), ctx.dividendYield(), adaptation,
                    rentalIncomeByYear, ctx.interestYield(), ctx.taxableEquityShare(), ctx.household());

            var result = trialSimulator.simulateTrial(ctx.income(), ctx.surplusTax(),
                    floors, discretionary, years, trialConfig);
            finalBalances[t] = result.finalBalance();
            minBalances[t] = result.minBalance();
            successFlags[t] = result.success();
            if (trackYearBalances) {
                for (int y = 0; y < years; y++) {
                    yearBalances[y][t] = result.yearBalances()[y];
                }
            }
        }
        return new TrialBatch(finalBalances, minBalances, successFlags, yearBalances);
    }

    /**
     * T24: builds this candidate schedule's {@link TrialSimulator.GuardrailAdaptation} rule inputs
     * so {@link #isSustainable} can gate on the with-rules success rate. Runs an extra no-adaptation
     * TRACKED reference pass (mirroring {@link GuardrailResponseBuilder}'s headline terminal pass)
     * to get the median-per-year balance the rule's {@code expectedStartBalance} needs, then
     * delegates the corridor/array assembly to the shared {@link GuardrailRuleInputBuilder} so this
     * derivation never forks from the reporting-only with-rules pass's.
     */
    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private TrialSimulator.GuardrailAdaptation buildCandidateAdaptation(SearchContext ctx, double[] floors,
                                                                          double[] discretionary) {
        TrialBatch reference = runTrials(ctx, floors, discretionary, null, true);
        int years = ctx.years();
        double[] medianBalanceByYear = new double[years];
        for (int y = 0; y < years; y++) {
            double[] balancesAtYear = reference.yearBalances()[y].clone();
            Arrays.sort(balancesAtYear);
            medianBalanceByYear[y] = PercentileCalculator.percentile(balancesAtYear, 0.50);
        }

        return GuardrailRuleInputBuilder.build(ctx.paths(), ctx.income(), floors, discretionary,
                years, ctx.trialCount(), initialTotalBalance(ctx), medianBalanceByYear,
                ctx.maxAnnualAdjustmentRate());
    }

    /** The initial total portfolio balance (taxable + traditional + roth), mirroring how
     * {@link GuardrailResponseBuilder} derives it from its own pool setup: fixed pool balances when
     * pools exist, else the non-pool scalar every trial's path is seeded from
     * ({@code paths[t][0]}, constant across trials by construction — see
     * {@code PortfolioPathGenerator.generate}). */
    private static double initialTotalBalance(SearchContext ctx) {
        TaxContext taxCtx = ctx.taxCtx();
        boolean hasPools = taxCtx != null && (taxCtx.initTraditional() > 0 || taxCtx.initRoth() > 0);
        return hasPools
                ? taxCtx.initTaxable() + taxCtx.initTraditional() + taxCtx.initRoth()
                : ctx.paths()[0][0];
    }
}
