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
    private final JointConversionSearch jointConversionSearch;
    private final OptimizationContextBuilder contextBuilder;

    /**
     * Test-friendly constructor: supplies the capital-market {@link RealReturnMatrix} directly so
     * unit tests need no Spring context or database. Production uses the {@link Autowired}
     * constructor and resolves the matrix lazily from the injected provider.
     */
    public MonteCarloSpendingOptimizer(@Nullable FederalTaxCalculator taxCalculator,
                                        RealReturnMatrix matrix) {
        this(taxCalculator, null, matrix, null);
    }

    @Autowired
    public MonteCarloSpendingOptimizer(@Nullable FederalTaxCalculator taxCalculator,
                                        CapitalMarketAssumptionsProvider capitalMarketAssumptions,
                                        @Nullable MeterRegistry meterRegistry) {
        this(taxCalculator, capitalMarketAssumptions, null, meterRegistry);
    }

    private MonteCarloSpendingOptimizer(@Nullable FederalTaxCalculator taxCalculator,
                                        @Nullable CapitalMarketAssumptionsProvider capitalMarketAssumptions,
                                        @Nullable RealReturnMatrix presetMatrix,
                                        @Nullable MeterRegistry meterRegistry) {
        this.taxCalculator = taxCalculator;
        this.capitalMarketAssumptions = capitalMarketAssumptions;
        this.presetMatrix = presetMatrix;
        this.meterRegistry = meterRegistry;
        this.jointConversionSearch = new JointConversionSearch(taxCalculator, sustainabilitySearch);
        this.contextBuilder = new OptimizationContextBuilder(taxCalculator);
    }

    /** Resolves the capital-market matrix — a preset (tests) or the provider's cached matrix (prod). */
    private RealReturnMatrix matrix() {
        if (presetMatrix != null) {
            return presetMatrix;
        }
        // Exactly one of presetMatrix / capitalMarketAssumptions is non-null by construction; the
        // provider path (production) requires the capital-market assumptions to be wired.
        return Objects.requireNonNull(capitalMarketAssumptions,
                "capitalMarketAssumptions required when no preset matrix is supplied").matrix();
    }

    @Timed(value = "wealthview.mc.optimize", histogram = true)
    @Observed(name = "wealthview.mc.optimize",
              contextualName = "monte-carlo-optimize",
              lowCardinalityKeyValues = {"component", "projection"})
    @Override
    public GuardrailProfileResponse optimize(GuardrailOptimizationInput input) {
        MDC.put("operation", "mc-optimize");
        if (meterRegistry != null) {
            meterRegistry.counter("wealthview.projection.runs", "type", "monte_carlo").increment();
        }
        try {
            RealReturnMatrix matrix = matrix();
            var ctx = contextBuilder.build(input, matrix);
            if (ctx.sim().years() <= 0) {
                return emptyResult(input);
            }

            var conv = jointConversionSearch.optimize(ctx, input, matrix);
            var discretionaryByYear = allocateAndSmooth(ctx, input, conv.byYear(), conv.taxByYear());
            return responseBuilder.build(ctx, input, discretionaryByYear, conv.byYear(), conv.taxByYear(),
                    conv.schedule());
        } finally {
            MDC.remove("operation");
        }
    }

    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private double[] allocateAndSmooth(OptimizationSetup ctx, GuardrailOptimizationInput input,
                                       double[] conversionByYear, double[] conversionTaxByYear) {
        // Priority-weighted discretionary allocation
        var searchContext = searchContextFor(ctx, conversionByYear, conversionTaxByYear);
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
                                                                 double[] conversionByYear,
                                                                 double[] conversionTaxByYear) {
        return new SustainabilitySearch.SearchContext(
                ctx.sim().portfolioPaths(), ctx.taxIncome().incomeByYear(),
                ctx.taxIncome().surplusTaxByYear(), ctx.portfolio().terminalTarget(),
                ctx.sim().retirementAge(), ctx.sim().years(), ctx.sim().trialCount(),
                ctx.sim().confidenceLevel(), ctx.portfolio().portfolioFloor(),
                ctx.portfolio().cashReserveYears(), ctx.portfolio().cashReturnRate(),
                ctx.sim().inflationRate(), ctx.taxIncome().taxCtx(),
                conversionByYear, conversionTaxByYear, ctx.taxIncome().dsBracketCeilingByYear(),
                ctx.sim().taxableReturns(), ctx.sim().traditionalReturns(), ctx.sim().rothReturns());
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
                input.returnMean(),
                input.trialCount(), input.confidenceLevel(),
                input.phases(), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO,
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                input.portfolioFloor(), input.maxAnnualAdjustmentRate(),
                input.phaseBlendYears(), null,
                input.cashReserveYears(), input.cashReturnRate(), null);
    }
}
