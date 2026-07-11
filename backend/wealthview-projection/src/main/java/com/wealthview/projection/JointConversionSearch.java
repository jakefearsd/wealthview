package com.wealthview.projection;

import java.util.Arrays;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.RentalLossCalculator;

/**
 * Optimizes Roth conversions for a guardrail run. For the dynamic-sequencing withdrawal order it
 * uses the tax-minimization schedule directly; otherwise it runs a joint search over conversion
 * fractions, scoring each by the sustainable spending the Monte Carlo optimizer can support.
 * Extracted from {@link MonteCarloSpendingOptimizer} to isolate the conversion-search algorithm.
 */
final class JointConversionSearch {

    private static final Logger log = LoggerFactory.getLogger(JointConversionSearch.class);
    private static final int JOINT_GRID_SIZE = 20;
    private static final double JOINT_REFINE_HALF_WIDTH = 0.1;
    private static final int JOINT_REFINE_ITERATIONS = 10;
    private static final int JOINT_SEARCH_TRIALS = 500;

    @Nullable
    private final FederalTaxCalculator taxCalculator;
    private final SustainabilitySearch sustainabilitySearch;

    JointConversionSearch(@Nullable FederalTaxCalculator taxCalculator,
                          SustainabilitySearch sustainabilitySearch) {
        this.taxCalculator = taxCalculator;
        this.sustainabilitySearch = sustainabilitySearch;
    }

    ConversionResult optimize(OptimizationSetup ctx, GuardrailOptimizationInput input,
                              RealReturnMatrix matrix) {
        if (!input.optimizeConversions() || ctx.portfolio().initTraditional() <= 0 || taxCalculator == null) {
            return new ConversionResult(null, null, null);
        }

        var convOptimizer = RothConversionOptimizer.builder()
                .portfolio(ctx.portfolio().initTraditional(), ctx.portfolio().initRoth(), ctx.portfolio().initTaxable())
                .income(
                        Arrays.stream(ctx.taxIncome().incomeData())
                                .mapToDouble(IncomeYearData::totalIncome).toArray(),
                        Arrays.stream(ctx.taxIncome().incomeData())
                                .mapToDouble(IncomeYearData::taxableIncome).toArray())
                .demographics(input.birthYear(), ctx.sim().retirementAge(), ctx.sim().endAge())
                .taxConfig(
                        input.conversionBracketRate() != null
                                ? input.conversionBracketRate().doubleValue() : 0.22,
                        input.rmdTargetBracketRate() != null
                                ? input.rmdTargetBracketRate().doubleValue() : 0.12,
                        input.rmdBracketHeadroom() != null
                                ? input.rmdBracketHeadroom().doubleValue() : 0.10,
                        ctx.taxIncome().filingStatus(), taxCalculator)
                .assumptions(
                        // Audit C4: resolved ONCE in OptimizationContextBuilder.resolveReturnMean
                        // — already a real, fee-adjusted rate in the same constant-real frame as
                        // the simulator's bracket ceilings. Do NOT convert it again here.
                        ctx.sim().returnMean(),
                        ctx.taxIncome().essentialFloor(),
                        input.traditionalExhaustionBuffer(), ctx.portfolio().withdrawalOrder())
                .rentals(input.incomeSources(), new RentalLossCalculator())
                .dynamicSequencingBracketRate(input.dynamicSequencingBracketRate() != null
                        ? input.dynamicSequencingBracketRate().doubleValue() : 0.0)
                .build();

        boolean useDynamicSequencing = PoolStrategy.WITHDRAWAL_ORDER_DYNAMIC_SEQUENCING
                .equals(ctx.portfolio().withdrawalOrder());
        RothConversionOptimizer.RothConversionSchedule convSchedule;

        if (useDynamicSequencing) {
            // When Dynamic Sequencing is active, conversions and DS are complementary
            // strategies — conversions happen first (Phase 1), then DS handles spending
            // withdrawals from whatever Traditional remains. Use Phase 1's tax-minimization
            // schedule directly; the joint search would incorrectly compete the two strategies
            // (DS draws Traditional for spending, making conversions look expensive).
            convSchedule = convOptimizer.optimize();
            log.info("DS mode: using Phase 1 conversion schedule (fraction={})",
                    convSchedule.conversionFraction());
        } else {
            convSchedule = jointSearch(ctx, input, convOptimizer, matrix);
        }

        return new ConversionResult(
                convSchedule.conversionByYear(), convSchedule.conversionTaxByYear(), convSchedule);
    }

    private RothConversionOptimizer.RothConversionSchedule jointSearch(
            OptimizationSetup ctx, GuardrailOptimizationInput input,
            RothConversionOptimizer convOptimizer, RealReturnMatrix matrix) {
        // Joint optimization: search conversion fractions by sustainable spending.
        // Each fraction is scored by how much the MC optimizer can sustain.
        int searchTrials = Math.min(JOINT_SEARCH_TRIALS, ctx.sim().trialCount());
        Random searchRng = input.seed() != null ? new Random(input.seed() + 1) : new Random();
        PoolReturnModel searchModel = PoolReturnModel.from(input.accounts(), ctx.sim().inflationRate());
        PortfolioReturnPaths searchPaths = PortfolioPathGenerator.generate(
                searchTrials, ctx.sim().years(), searchModel, matrix, searchRng, ctx.sim().feeRate());

        double[] searchFloors = SustainabilitySearch.verifyEssentialFloor(
                searchPaths.portfolioPaths(), ctx.taxIncome().incomeByYear(), ctx.taxIncome().essentialFloor(),
                ctx.sim().confidenceLevel(), ctx.sim().years(), searchTrials);

        double[] searchMarginalRates = MarginalRateCalculator.compute(taxCalculator,
                ctx.taxIncome().rentalAwareTaxableIncome(), ctx.sim().retirementYear(), ctx.sim().years(),
                ctx.taxIncome().filingStatus(), input.birthYear());
        TaxContext searchTaxCtx = new TaxContext(ctx.portfolio().initTaxable(), ctx.portfolio().initTraditional(),
                ctx.portfolio().initRoth(), ctx.portfolio().withdrawalOrder(), searchMarginalRates);

        int gridSize = JOINT_GRID_SIZE;
        double bestFraction = 0.0;
        double bestSpending = 0.0;
        RothConversionOptimizer.RothConversionSchedule bestSchedule =
                convOptimizer.baselineSchedule();

        for (int i = 0; i <= gridSize; i++) {
            double fraction = (double) i / gridSize;
            var schedule = convOptimizer.scheduleForFraction(fraction);

            double spending = evalSearchSpending(searchPaths, ctx, searchFloors, searchTrials, searchTaxCtx,
                    schedule.conversionByYear(), schedule.conversionTaxByYear());

            if (spending > bestSpending) {
                bestSpending = spending;
                bestFraction = fraction;
                bestSchedule = schedule;
            }
        }

        double lo = Math.max(0.0, bestFraction - JOINT_REFINE_HALF_WIDTH);
        double hi = Math.min(1.0, bestFraction + JOINT_REFINE_HALF_WIDTH);
        for (int iter = 0; iter < JOINT_REFINE_ITERATIONS; iter++) {
            double m1 = lo + (hi - lo) / 3.0;
            double m2 = hi - (hi - lo) / 3.0;

            var s1 = convOptimizer.scheduleForFraction(m1);
            var s2 = convOptimizer.scheduleForFraction(m2);

            double sp1 = evalSearchSpending(searchPaths, ctx, searchFloors, searchTrials, searchTaxCtx,
                    s1.conversionByYear(), s1.conversionTaxByYear());

            double sp2 = evalSearchSpending(searchPaths, ctx, searchFloors, searchTrials, searchTaxCtx,
                    s2.conversionByYear(), s2.conversionTaxByYear());

            if (sp1 > sp2) {
                hi = m2;
            } else {
                lo = m1;
            }

            if (sp1 > bestSpending) {
                bestSpending = sp1;
                bestFraction = m1;
                bestSchedule = s1;
            }
            if (sp2 > bestSpending) {
                bestSpending = sp2;
                bestFraction = m2;
                bestSchedule = s2;
            }
        }

        log.info("Joint optimization: best fraction={}, sustainable spending={}",
                bestFraction, bestSpending);
        return bestSchedule;
    }

    /**
     * Convenience wrapper around {@link SustainabilitySearch#evaluateSustainableSpending} that
     * captures the fixed search-phase args ({@code searchPaths}, the {@link OptimizationSetup}
     * fields, {@code searchFloors}, {@code searchTrials}, and {@code searchTaxCtx}) so the repeated
     * call sites in {@link #jointSearch} only vary by
     * {@code conversionByYear} / {@code conversionTaxByYear}.
     */
    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private double evalSearchSpending(PortfolioReturnPaths searchPaths, OptimizationSetup ctx,
                                      double[] searchFloors, int searchTrials, TaxContext searchTaxCtx,
                                      double[] conversionByYear, double[] conversionTaxByYear) {
        var searchContext = new SustainabilitySearch.SearchContext(
                searchPaths.portfolioPaths(), ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(),
                ctx.portfolio().terminalTarget(), ctx.sim().retirementAge(), ctx.sim().years(),
                searchTrials, ctx.sim().confidenceLevel(), ctx.portfolio().portfolioFloor(),
                ctx.portfolio().cashReserveYears(), ctx.portfolio().cashReturnRate(),
                searchTaxCtx, conversionByYear, conversionTaxByYear,
                ctx.taxIncome().dsBracketCeilingByYear(),
                searchPaths.taxableReturns(), searchPaths.traditionalReturns(), searchPaths.rothReturns(),
                ctx.sim().rmdStartAge(),
                ctx.portfolio().initTaxableBasis(), ctx.taxIncome().ltcgRateByYear(),
                ctx.sim().dividendYield());
        return sustainabilitySearch.evaluateSustainableSpending(searchContext, searchFloors);
    }
}
