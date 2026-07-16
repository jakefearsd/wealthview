package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.SpendingOptimizer;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;

/**
 * Orchestrates the Monte Carlo retirement spending optimization. The pipeline is composed from
 * focused collaborators: {@link OptimizationContextBuilder} prepares the run context (pool
 * balances, portfolio paths, income, tax arrays), {@link JointConversionSearch} optimizes Roth
 * conversions, the discretionary spending is allocated and smoothed here, and
 * {@link GuardrailResponseBuilder} runs the terminal simulation and assembles the response.
 */
@Component
public class MonteCarloSpendingOptimizer implements SpendingOptimizer {

    private static final Logger log = LoggerFactory.getLogger(MonteCarloSpendingOptimizer.class);
    /** Reduction factor applied per iteration when a smoothed plan fails sustainability. */
    private static final double SUSTAINABILITY_REDUCTION_FACTOR = 0.95;
    /**
     * Default longevity-conditional age used when {@link
     * GuardrailOptimizationInput#longevityConditionalAge()} is {@code null} (sub-project B, task 7)
     * -- per that field's own javadoc.
     */
    private static final int DEFAULT_LONGEVITY_CONDITIONAL_AGE = 95;

    private final FederalTaxCalculator taxCalculator;
    @Nullable
    private final MeterRegistry meterRegistry;
    @Nullable
    private final CapitalMarketAssumptionsProvider capitalMarketAssumptions;
    @Nullable
    private final RealReturnMatrix presetMatrix;
    private final TrialSimulator trialSimulator = new TrialSimulator();
    private final SustainabilitySearch sustainabilitySearch = new SustainabilitySearch(trialSimulator);
    private final GuardrailResponseBuilder responseBuilder = new GuardrailResponseBuilder(trialSimulator);
    // Sub-project B (stochastic mortality), task 6: the separate longevity-aware evaluation pass.
    private final StochasticMortalityEvaluator stochasticEvaluator =
            new StochasticMortalityEvaluator(trialSimulator);
    private final JointConversionSearch jointConversionSearch;
    private final OptimizationContextBuilder contextBuilder;

    /**
     * Test-friendly constructor: supplies the capital-market {@link RealReturnMatrix} directly so
     * unit tests need no Spring context or database. Production uses the {@link Autowired}
     * constructor and resolves the matrix lazily from the injected provider.
     */
    public MonteCarloSpendingOptimizer(@Nullable FederalTaxCalculator taxCalculator,
                                        RealReturnMatrix matrix) {
        this(taxCalculator, null, matrix, null, null);
    }

    @Autowired
    public MonteCarloSpendingOptimizer(@Nullable FederalTaxCalculator taxCalculator,
                                        CapitalMarketAssumptionsProvider capitalMarketAssumptions,
                                        @Nullable MeterRegistry meterRegistry,
                                        @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator) {
        this(taxCalculator, capitalMarketAssumptions, null, meterRegistry, capitalGainsTaxCalculator);
    }

    private MonteCarloSpendingOptimizer(@Nullable FederalTaxCalculator taxCalculator,
                                        @Nullable CapitalMarketAssumptionsProvider capitalMarketAssumptions,
                                        @Nullable RealReturnMatrix presetMatrix,
                                        @Nullable MeterRegistry meterRegistry,
                                        @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator) {
        this.taxCalculator = taxCalculator;
        this.capitalMarketAssumptions = capitalMarketAssumptions;
        this.presetMatrix = presetMatrix;
        this.meterRegistry = meterRegistry;
        this.jointConversionSearch = new JointConversionSearch(taxCalculator, sustainabilitySearch);
        this.contextBuilder = new OptimizationContextBuilder(taxCalculator, capitalGainsTaxCalculator);
    }

    /**
     * Resolves the capital-market matrix — a preset (tests) or the provider's cached matrix (prod).
     *
     * @param includeDepressionYears audit C10: selects the provider's window when resolving from
     *         the production path. A test-supplied {@code presetMatrix} is returned as-is
     *         regardless of this flag (the preset already IS the intended matrix for that test).
     */
    private RealReturnMatrix matrix(boolean includeDepressionYears) {
        if (presetMatrix != null) {
            return presetMatrix;
        }
        // Exactly one of presetMatrix / capitalMarketAssumptions is non-null by construction; the
        // provider path (production) requires the capital-market assumptions to be wired.
        return Objects.requireNonNull(capitalMarketAssumptions,
                "capitalMarketAssumptions required when no preset matrix is supplied")
                .matrix(includeDepressionYears);
    }

    @Timed(value = "wealthview.mc.optimize", histogram = true)
    @Observed(name = "wealthview.mc.optimize",
              contextualName = "monte-carlo-optimize",
              lowCardinalityKeyValues = {"component", "projection"})
    @Override
    public GuardrailProfileResponse optimize(GuardrailOptimizationInput input) {
        return optimizeInternal(input).response();
    }

    /**
     * Sub-project B (stochastic mortality), task 7: the real {@link #optimize} implementation,
     * returning the public {@link GuardrailProfileResponse} PLUS the optional {@link
     * StochasticMortalitySummary} from ONE pass. Folds what task 6 left as a separate {@code
     * evaluateStochasticMortality} re-run into this flow: {@code ctx}/{@code conv}/{@code
     * discretionaryByYear} are computed ONCE and reused for both the recommendation and the
     * stochastic evaluation, instead of re-running the (expensive) context build + Roth-conversion
     * search + allocation a second time to get the longevity number beside it.
     *
     * <p>The fold is strictly READ-ONLY over the recommendation: {@code response} is built from
     * {@code recommendedDiscretionary} (a clone, scaled at the FIXED-death transition index --
     * unchanged from pre-fold); {@link #summarizeStochasticMortality} only READS {@code ctx}/
     * {@code discretionaryByYear} (see {@link StochasticMortalityEvaluator#evaluate}, which never
     * mutates its arguments), so it cannot feed back into {@code response} regardless of call order
     * -- task 8 runs it FIRST so the resulting {@code stochasticMortality} can be embedded directly
     * in {@link GuardrailResponseBuilder#build}'s constructor call (mirrors how the {@code
     * Disclosure} fields are threaded in), rather than copy-constructing a second response
     * afterward. Toggle off (or single-person / degenerate horizon) ⇒ {@code stochasticMortality} is
     * {@code null} and this method's observable output is byte-identical to the pre-fold {@code
     * optimize()} (the anchor).
     *
     * <p>Package-private: {@link #optimize} (the {@link SpendingOptimizer} contract) is the only
     * production entry point and only ever returns {@link OptimizeResult#response}; this is exposed
     * so task 7/8 can consume the summary directly without building the API response DTO here.
     */
    OptimizeResult optimizeInternal(GuardrailOptimizationInput input) {
        MDC.put("operation", "mc-optimize");
        if (meterRegistry != null) {
            meterRegistry.counter("wealthview.projection.runs", "type", "monte_carlo").increment();
        }
        try {
            RealReturnMatrix matrix = matrix(input.includeDepressionYears());
            var ctx = contextBuilder.build(input, matrix);
            if (ctx.sim().years() <= 0) {
                return new OptimizeResult(emptyResult(input), null);
            }

            var conv = jointConversionSearch.optimize(ctx, input, matrix);
            // allocateAndSmooth now returns the discretionary schedule UNSCALED by the survivor factor
            // (sub-project B, task 6); the recommendation scales it at the FIXED-death transition index
            // exactly as before (byte-identical -- scaleFromTransition is a no-op for single-person /
            // no in-window transition, so most runs are untouched). The unscaled schedule is what the
            // stochastic evaluation pass re-splices per trial (task 7: reused directly below -- no
            // second context-build/conversion-search/allocation re-run).
            var discretionaryByYear = allocateAndSmooth(ctx, input, conv.byYear(), conv.taxByYear());
            double[] recommendedDiscretionary = discretionaryByYear.clone();
            HouseholdMcResolver.scaleFromTransition(recommendedDiscretionary, ctx.sim().household(),
                    ctx.sim().survivorSpendingFactor(), ctx.sim().years());

            // Task 8: computed BEFORE responseBuilder.build so it can be embedded directly in the
            // GuardrailProfileResponse constructor call (mirrors how the Disclosure fields are
            // threaded in) instead of copy-constructing a second response afterward. Safe to reorder
            // relative to the pre-task-8 sequencing: summarization only READS ctx/discretionaryByYear/
            // conv (see summarizeStochasticMortality's own javadoc) and every per-trial return/mortality
            // draw the two passes consume was already generated once, up front, in contextBuilder.build
            // -- there is no shared mutable RNG state for call order to perturb, so this is still the
            // byte-identical anchor (toggle on vs off) task 7 pinned.
            StochasticMortalitySummary stochasticMortality =
                    summarizeStochasticMortality(ctx, discretionaryByYear, conv, input);

            GuardrailProfileResponse response = responseBuilder.build(ctx, input, recommendedDiscretionary,
                    conv.byYear(), conv.taxByYear(), conv.schedule(), stochasticMortality);

            return new OptimizeResult(response, stochasticMortality);
        } finally {
            MDC.remove("operation");
        }
    }

    /**
     * Sub-project B (stochastic mortality), task 7: internal carrier pairing the public {@link
     * GuardrailProfileResponse} with the optional stochastic-mortality summary computed in the SAME
     * {@link #optimizeInternal} pass. {@code stochasticMortality} is {@code null} whenever the run
     * did not opt into stochastic mortality — the byte-identical anchor: {@link #optimize} (the
     * {@link SpendingOptimizer} contract) only ever returns {@link #response}, so a toggle-off run's
     * PUBLIC behavior is completely untouched by this type's existence. Task 8 wired the same summary
     * onto {@link GuardrailProfileResponse#stochasticMortality()} too (via {@link
     * GuardrailResponseBuilder#build}), so this record's own {@code stochasticMortality} component is
     * now a convenience duplicate — retained so tests (and any other internal caller) can reach it
     * without unwrapping the response DTO.
     */
    record OptimizeResult(GuardrailProfileResponse response, @Nullable StochasticMortalitySummary stochasticMortality) {
    }

    /**
     * Sub-project B (stochastic mortality), task 7: aggregates the stochastic evaluation into the
     * user-facing {@link StochasticMortalitySummary}, reusing the SAME {@code ctx}/{@code
     * jointDiscretionary}/{@code conv} the recommendation was just built from in {@link
     * #optimizeInternal} — no second context-build / conversion-search / allocation (the task-6
     * reviewer's flagged inefficiency). Returns {@code null} when the run did not opt into
     * stochastic mortality ({@code ctx.sim().stochasticEval() == null}), the byte-identical anchor.
     */
    @Nullable
    private StochasticMortalitySummary summarizeStochasticMortality(OptimizationSetup ctx,
            double[] jointDiscretionary, ConversionResult conv,
            GuardrailOptimizationInput input) {
        StochasticMortalityEvaluation eval =
                runStochasticEvaluation(ctx, jointDiscretionary, conv.byYear(), conv.taxByYear());
        if (eval == null) {
            return null;
        }
        Integer requestedLongevityAge = input.longevityConditionalAge();
        int longevityAge = requestedLongevityAge != null
                ? requestedLongevityAge : DEFAULT_LONGEVITY_CONDITIONAL_AGE;
        return StochasticMortalitySummary.from(
                eval.success(), eval.firstDeathAge(), eval.secondDeathAge(), longevityAge);
    }

    /**
     * Shared evaluation-pass entry point for both {@link #summarizeStochasticMortality} (folded into
     * {@link #optimizeInternal}) and the standalone {@link #evaluateStochasticMortality} (retained
     * for direct low-level testing of {@link StochasticMortalityEvaluator} — see {@code
     * StochasticMortalityEvaluatorTest}). Returns {@code null} when the run did not opt into
     * stochastic mortality, otherwise runs the trial loop exactly once.
     */
    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    @Nullable
    private StochasticMortalityEvaluation runStochasticEvaluation(OptimizationSetup ctx,
            double[] jointDiscretionary, double[] conversionByYear, double[] conversionTaxByYear) {
        if (ctx.sim().stochasticEval() == null) {
            return null;
        }
        return stochasticEvaluator.evaluate(ctx, jointDiscretionary, conversionByYear, conversionTaxByYear);
    }

    /**
     * Sub-project B (stochastic mortality), task 6: STANDALONE longevity-aware evaluation pass —
     * rebuilds its own context / conversion schedule / allocation from {@code input} rather than
     * reusing an already-optimized run's. Retained for direct low-level testing of {@link
     * StochasticMortalityEvaluator} (see {@code StochasticMortalityEvaluatorTest}'s determinism,
     * index-0-transition, and fixed-death cross-check pins); production no longer goes through this
     * path — task 7 folded the SAME evaluation into {@link #optimizeInternal}, which reuses its own
     * already-built {@code ctx}/{@code conv}/{@code discretionaryByYear} instead of re-deriving them
     * here, so a caller wanting both the recommendation and the stochastic summary now gets both
     * from ONE {@link #optimizeInternal} call.
     */
    @Nullable
    StochasticMortalityEvaluation evaluateStochasticMortality(GuardrailOptimizationInput input) {
        RealReturnMatrix matrix = matrix(input.includeDepressionYears());
        var ctx = contextBuilder.build(input, matrix);
        if (ctx.sim().years() <= 0) {
            return null;
        }
        var conv = jointConversionSearch.optimize(ctx, input, matrix);
        double[] jointDiscretionary = allocateAndSmooth(ctx, input, conv.byYear(), conv.taxByYear());
        return runStochasticEvaluation(ctx, jointDiscretionary, conv.byYear(), conv.taxByYear());
    }

    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private double[] allocateAndSmooth(OptimizationSetup ctx, GuardrailOptimizationInput input,
                                       double[] conversionByYear, double[] conversionTaxByYear) {
        // Priority-weighted discretionary allocation
        var searchContext = searchContextFor(ctx, input, conversionByYear, conversionTaxByYear);
        double[] discretionaryByYear = sustainabilitySearch.allocateSpending(
                searchContext, ctx.taxIncome().adjustedFloors(), input.phases());

        // Post-processing — phase blending and YoY smoothing
        int phaseBlendYears = input.phaseBlendYears();
        if (phaseBlendYears > 0 && input.phases() != null && input.phases().size() > 1) {
            SpendingSmoother.applyPhaseBlending(discretionaryByYear, ctx.taxIncome().adjustedFloors(),
                    input.phases(), ctx.sim().retirementAge(), ctx.sim().years(), phaseBlendYears);
        }

        Double maxAdjRate = input.maxAnnualAdjustmentRate() != null
                ? input.maxAnnualAdjustmentRate().doubleValue() : null;
        if (maxAdjRate != null && maxAdjRate > 0) {
            SpendingSmoother.applyYearOverYearSmoothing(discretionaryByYear,
                    ctx.taxIncome().adjustedFloors(), maxAdjRate,
                    ctx.sim().years(), input.phases(), ctx.sim().retirementAge());

            // Re-verify sustainability of smoothed plan; reduce if broken
            reduceUntilSustainable(discretionaryByYear, searchContext, ctx);
        }

        // Sub-project B (stochastic mortality), task 6: this method now returns the discretionary
        // schedule UNSCALED by the survivor factor. HP2's single survivor-scaling seam moved UP to the
        // caller (optimize scales a clone for the recommendation at the FIXED-death transition index --
        // byte-identical to before), so this unscaled schedule can also feed the stochastic evaluation
        // pass, which re-applies the ×factor per trial from each trial's OWN sampled first-death index
        // (the fixed index is meaningless once death is stochastic).
        return discretionaryByYear;
    }

    /**
     * Scales discretionary spending down by {@link #SUSTAINABILITY_REDUCTION_FACTOR} each
     * iteration until the smoothed plan is sustainable again, up to a fixed iteration cap.
     * No-op when the plan is already sustainable. Mutates {@code discretionaryByYear} in place.
     */
    private void reduceUntilSustainable(double[] discretionaryByYear,
                                        SustainabilitySearch.SearchContext searchContext,
                                        OptimizationSetup ctx) {
        double[] floors = ctx.taxIncome().adjustedFloors();
        if (sustainabilitySearch.isSustainable(searchContext, floors, discretionaryByYear)) {
            return;
        }
        for (int i = 0; i < 10; i++) {
            for (int y = 0; y < ctx.sim().years(); y++) {
                discretionaryByYear[y] *= SUSTAINABILITY_REDUCTION_FACTOR;
            }
            if (sustainabilitySearch.isSustainable(searchContext, floors, discretionaryByYear)) {
                return;
            }
        }
    }

    /**
     * Builds the {@link SustainabilitySearch.SearchContext} for the main optimization run
     * from the pre-computed {@link OptimizationSetup} and the chosen conversion schedule.
     */
    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private SustainabilitySearch.SearchContext searchContextFor(OptimizationSetup ctx,
                                                                 GuardrailOptimizationInput input,
                                                                 double[] conversionByYear,
                                                                 double[] conversionTaxByYear) {
        double maxAdjRate = input.maxAnnualAdjustmentRate() != null
                ? input.maxAnnualAdjustmentRate().doubleValue() : 0.0;
        return new SustainabilitySearch.SearchContext(
                ctx.sim().portfolioPaths(), ctx.taxIncome().incomeByYear(),
                ctx.taxIncome().surplusTaxByYear(), ctx.portfolio().terminalTarget(),
                ctx.sim().retirementAge(), ctx.sim().years(), ctx.sim().trialCount(),
                ctx.sim().confidenceLevel(), ctx.portfolio().portfolioFloor(),
                ctx.portfolio().cashReserveYears(), ctx.portfolio().cashReturnRate(),
                ctx.taxIncome().taxCtx(),
                conversionByYear, conversionTaxByYear, ctx.taxIncome().dsBracketCeilingByYear(),
                ctx.sim().taxableReturns(), ctx.sim().traditionalReturns(), ctx.sim().rothReturns(),
                ctx.sim().rmdStartAge(),
                ctx.portfolio().initTaxableBasis(), ctx.taxIncome().ltcgTaxTableByYear(),
                ctx.sim().dividendYield(), ctx.sim().interestYield(), ctx.sim().taxableEquityShare(),
                // T24: the search gate honors the profile's toggle. T26: JointConversionSearch's own
                // conversion-fraction scoring search resolves the SAME toggle independently (see its
                // SearchContext construction), so both stages score/gate under one objective.
                input.gateOnAdaptiveRules(), maxAdjRate,
                // Household task 6: the search runs the same household economics as the terminal pass.
                ctx.sim().household());
    }

    /**
     * Split a withdrawal need across three pools using the specified ordering.
     * Delegates to {@link TrialSimulator#splitWithdrawal}; retained as a thin static
     * facade so existing unit tests can exercise the splitting logic directly.
     */
    static PoolWithdrawal splitWithdrawal(double taxable, double traditional, double roth,
                                           double need, String order, boolean preAge595,
                                           double dsBracketCeiling, double otherIncome,
                                           double conversionAmount, double rmdAmount) {
        return TrialSimulator.splitWithdrawal(taxable, traditional, roth, need, order, preAge595,
                dsBracketCeiling, otherIncome, conversionAmount, rmdAmount);
    }

    private GuardrailProfileResponse emptyResult(GuardrailOptimizationInput input) {
        return new GuardrailProfileResponse(
                null, null, "Optimized",
                input.essentialFloor(), input.terminalBalanceTarget(),
                // Degenerate zero-year run: nothing was simulated, so there is no resolved
                // effective rate to echo — pass the raw request value (null when omitted, the
                // normal case). GuardrailProfileService falls back to ZERO for the NOT NULL column.
                input.returnMean(),
                input.trialCount(), input.confidenceLevel(),
                input.phases(), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                BigDecimal.ZERO,
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                input.portfolioFloor(), input.maxAnnualAdjustmentRate(),
                input.phaseBlendYears(), null,
                input.cashReserveYears(), input.cashReturnRate(), null,
                new GuardrailProfileResponse.Disclosure(null, false, null, null,
                        // T24: no search ran (degenerate zero-year input), but the derivation is
                        // still the accurate statement of what WOULD have gated had one run.
                        GuardrailProfileResponse.resolveGatedOn(
                                input.gateOnAdaptiveRules(), input.maxAnnualAdjustmentRate())));
    }
}
