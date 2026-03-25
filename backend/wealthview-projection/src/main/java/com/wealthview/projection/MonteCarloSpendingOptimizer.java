package com.wealthview.projection;

import com.wealthview.core.projection.SpendingOptimizer;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.wealthview.core.projection.dto.ConversionYearDetail;
import com.wealthview.core.projection.dto.RothConversionScheduleResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

@Component
public class MonteCarloSpendingOptimizer implements SpendingOptimizer {

    private static final Logger log = LoggerFactory.getLogger(MonteCarloSpendingOptimizer.class);
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final double DEFAULT_BLOCK_LENGTH = 5.0;
    private static final int JOINT_GRID_SIZE = 20;
    private static final double JOINT_REFINE_HALF_WIDTH = 0.1;
    private static final int JOINT_REFINE_ITERATIONS = 10;
    private static final int JOINT_SEARCH_TRIALS = 500;
    private static final double MAX_SPENDING_CEILING = 500_000;
    /** Binary search iterations used in {@link #evaluateSustainableSpending}. */
    private static final int SPENDING_BINARY_SEARCH_ITERATIONS = 30;
    /** Fraction of equity portfolio used each year to replenish the cash reserve bucket. */
    private static final double CASH_REPLENISHMENT_RATE = 0.10;
    /** Reduction factor applied per iteration when a smoothed plan fails sustainability. */
    private static final double SUSTAINABILITY_REDUCTION_FACTOR = 0.95;
    /** Proxy for age 59.5 — the minimum age for penalty-free retirement account withdrawals. */
    private static final int EARLY_WITHDRAWAL_AGE = 60;

    private final FederalTaxCalculator taxCalculator;

    public MonteCarloSpendingOptimizer(@Nullable FederalTaxCalculator taxCalculator) {
        this.taxCalculator = taxCalculator;
    }

    /** Portfolio configuration and pool balances passed into the optimizer. */
    private record PortfolioSetup(
            double initTaxable, double initTraditional, double initRoth,
            double initialPortfolio, String withdrawalOrder,
            int cashReserveYears, double cashReturnRate,
            double terminalTarget, double portfolioFloor
    ) {}

    /** MC simulation run parameters — how many trials, over what time horizon, with what returns. */
    private record SimulationParameters(
            int retirementYear, int retirementAge, int endAge, int years,
            int trialCount, double confidenceLevel, double inflationRate,
            double[][] portfolioPaths
    ) {}

    /** Pre-computed per-year tax and income data, derived from deterministic income projections. */
    private record TaxIncomeContext(
            FilingStatus filingStatus, double essentialFloor,
            double[] incomeByYear, double[] taxableIncomeByYear, double[] surplusTaxByYear,
            IncomeYearData[] incomeData, double[] rentalAwareTaxableIncome,
            double[] adjustedFloors, double[] marginalRates, TaxContext taxCtx,
            double[] dsBracketCeilingByYear
    ) {}

    /**
     * All pre-computed inputs to a single optimization run, grouped by concern.
     * Renamed from OptimizationContext to distinguish the data container from the optimizer class.
     */
    private record OptimizationSetup(
            PortfolioSetup portfolio,
            SimulationParameters sim,
            TaxIncomeContext taxIncome
    ) {}

    private record ConversionResult(
            double[] byYear, double[] taxByYear,
            RothConversionOptimizer.RothConversionSchedule schedule
    ) {}

    @Override
    public GuardrailProfileResponse optimize(GuardrailOptimizationInput input) {
        MDC.put("operation", "mc-optimize");
        try {
            var ctx = prepareContext(input);
            if (ctx.sim().years() <= 0) {
                return emptyResult(input);
            }

            var conv = optimizeConversions(ctx, input);
            var discretionaryByYear = allocateAndSmooth(ctx, input, conv.byYear(), conv.taxByYear());
            return buildResponse(ctx, input, discretionaryByYear, conv.byYear(), conv.taxByYear(),
                    conv.schedule());
        } finally {
            MDC.remove("operation");
        }
    }

    private OptimizationSetup prepareContext(GuardrailOptimizationInput input) {
        int retirementYear = input.retirementDate().getYear();
        int retirementAge = retirementYear - input.birthYear();
        int endAge = input.endAge();
        int years = endAge - retirementAge;

        if (years <= 0) {
            return new OptimizationSetup(
                    new PortfolioSetup(0, 0, 0, 0, null, 0, 0, 0, 0),
                    new SimulationParameters(retirementYear, retirementAge, endAge, years, 0, 0, 0, null),
                    new TaxIncomeContext(null, 0, null, null, null, null, null, null, null, null, null));
        }

        int trialCount = input.trialCount();
        double initialPortfolio = totalPortfolio(input.accounts());
        double initTaxable = sumByType(input.accounts(), PoolStrategy.POOL_TAXABLE);
        double initTraditional = sumByType(input.accounts(), PoolStrategy.POOL_TRADITIONAL);
        double initRoth = sumByType(input.accounts(), PoolStrategy.POOL_ROTH);
        String withdrawalOrder = input.withdrawalOrder() != null ? input.withdrawalOrder() : "taxable_first";
        double essentialFloor = input.essentialFloor().doubleValue();
        double terminalTarget = input.terminalBalanceTarget().doubleValue();
        double confidenceLevel = input.confidenceLevel().doubleValue();

        int cashReserveYears = input.cashReserveYears();
        double cashReturnRate = input.cashReturnRate() != null
                ? input.cashReturnRate().doubleValue() : 0.0;

        Random rng = input.seed() != null ? new Random(input.seed()) : new Random();

        double[] historicalReturns = HistoricalReturns.getReturns();

        double inflationRate = input.inflationRate() != null
                ? input.inflationRate().doubleValue() : 0.0;

        // Run MC trials (no withdrawals) to get portfolio trajectories using bootstrap.
        // Bootstrap returns are real (CPI-adjusted); convert to nominal via Fisher equation
        // so portfolio growth matches the nominal spending/income model.
        double[][] portfolioPaths = runMonteCarloTrials(
                trialCount, years, initialPortfolio, historicalReturns, rng, inflationRate);

        // Compute deterministic income for each year
        IncomeYearData[] incomeData = computeDeterministicIncome(
                input.incomeSources(), retirementAge, years, input.birthYear());
        FilingStatus filingStatus = input.filingStatus() != null
                ? FilingStatus.fromString(input.filingStatus()) : FilingStatus.SINGLE;

        double[] incomeByYear = new double[years];
        double[] taxableIncomeByYear = new double[years];
        // Pre-compute surplus tax once per year to avoid calling the tax calculator inside hot loops.
        double[] surplusTaxByYear = new double[years];
        for (int y = 0; y < years; y++) {
            incomeByYear[y] = incomeData[y].totalIncome();
            taxableIncomeByYear[y] = incomeData[y].taxableIncome();
            surplusTaxByYear[y] = computeSurplusTax(
                    incomeData[y].taxableIncome(), retirementYear + y, filingStatus);
        }

        // Compute rental-aware taxable income for marginal rate pre-computation.
        // This adjusts the base taxable income with rental property depreciation,
        // passive loss rules, and carryforward so that MC trial withdrawal tax
        // estimates reflect actual bracket positions.
        double[] rentalAwareTaxableIncome = computeRentalAwareTaxableIncome(
                taxableIncomeByYear, input.incomeSources(),
                incomeData, retirementAge, input.birthYear(), years);

        // Verify essential floor feasibility (inflation-adjusted)
        double[] adjustedFloors = verifyEssentialFloor(
                portfolioPaths, incomeByYear, essentialFloor,
                confidenceLevel, years, trialCount, inflationRate);

        double[] marginalRates = precomputeMarginalRates(
                rentalAwareTaxableIncome, retirementYear, years, filingStatus);
        TaxContext taxCtx = (initTraditional > 0 || initRoth > 0)
                ? new TaxContext(initTaxable, initTraditional, initRoth,
                        withdrawalOrder, marginalRates)
                : null;

        // Pre-compute DS bracket ceilings per year for dynamic_sequencing withdrawal order
        double[] dsBracketCeilingByYear = null;
        if (PoolStrategy.WITHDRAWAL_ORDER_DYNAMIC_SEQUENCING.equals(withdrawalOrder)
                && input.dynamicSequencingBracketRate() != null
                && taxCalculator != null) {
            dsBracketCeilingByYear = new double[years];
            for (int y = 0; y < years; y++) {
                dsBracketCeilingByYear[y] = taxCalculator.computeMaxIncomeForBracket(
                        input.dynamicSequencingBracketRate(), retirementYear + y, filingStatus,
                        input.inflationRate()).doubleValue();
            }
        }

        double portfolioFloor = input.portfolioFloor() != null
                ? input.portfolioFloor().doubleValue() : 0.0;

        return new OptimizationSetup(
                new PortfolioSetup(initTaxable, initTraditional, initRoth,
                        initialPortfolio, withdrawalOrder, cashReserveYears, cashReturnRate,
                        terminalTarget, portfolioFloor),
                new SimulationParameters(retirementYear, retirementAge, endAge, years,
                        trialCount, confidenceLevel, inflationRate, portfolioPaths),
                new TaxIncomeContext(filingStatus, essentialFloor,
                        incomeByYear, taxableIncomeByYear, surplusTaxByYear,
                        incomeData, rentalAwareTaxableIncome, adjustedFloors, marginalRates,
                        taxCtx, dsBracketCeilingByYear));
    }

    private ConversionResult optimizeConversions(OptimizationSetup ctx,
                                                 GuardrailOptimizationInput input) {
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
                        input.returnMean() != null
                                ? input.returnMean().doubleValue() : 0.10,
                        ctx.taxIncome().essentialFloor(), ctx.sim().inflationRate(),
                        input.traditionalExhaustionBuffer(), ctx.portfolio().withdrawalOrder())
                .rentals(input.incomeSources(), new RentalLossCalculator())
                .dynamicSequencingBracketRate(input.dynamicSequencingBracketRate() != null
                        ? input.dynamicSequencingBracketRate().doubleValue() : 0.0)
                .build();

        boolean useDynamicSequencing = PoolStrategy.WITHDRAWAL_ORDER_DYNAMIC_SEQUENCING.equals(ctx.portfolio().withdrawalOrder());
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
            convSchedule = jointSearchConversions(ctx, input, convOptimizer);
        }

        return new ConversionResult(
                convSchedule.conversionByYear(), convSchedule.conversionTaxByYear(), convSchedule);
    }

    private RothConversionOptimizer.RothConversionSchedule jointSearchConversions(
            OptimizationSetup ctx, GuardrailOptimizationInput input,
            RothConversionOptimizer convOptimizer) {
        // Joint optimization: search conversion fractions by sustainable spending.
        // Each fraction is scored by how much the MC optimizer can sustain.
        int searchTrials = Math.min(JOINT_SEARCH_TRIALS, ctx.sim().trialCount());
        Random searchRng = input.seed() != null ? new Random(input.seed() + 1) : new Random();
        double[] historicalReturns = HistoricalReturns.getReturns();
        double[][] searchPaths = runMonteCarloTrials(
                searchTrials, ctx.sim().years(), ctx.portfolio().initialPortfolio(), historicalReturns,
                searchRng, ctx.sim().inflationRate());

        double[] searchFloors = verifyEssentialFloor(
                searchPaths, ctx.taxIncome().incomeByYear(), ctx.taxIncome().essentialFloor(),
                ctx.sim().confidenceLevel(), ctx.sim().years(), searchTrials, ctx.sim().inflationRate());

        double[] searchMarginalRates = precomputeMarginalRates(
                ctx.taxIncome().rentalAwareTaxableIncome(), ctx.sim().retirementYear(), ctx.sim().years(),
                ctx.taxIncome().filingStatus());
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

            double spending = evaluateSustainableSpending(
                    searchPaths, ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(), searchFloors,
                    ctx.portfolio().terminalTarget(), input.phases(), ctx.sim().retirementAge(), ctx.sim().years(),
                    searchTrials, ctx.sim().confidenceLevel(), ctx.portfolio().portfolioFloor(),
                    ctx.portfolio().cashReserveYears(), ctx.portfolio().cashReturnRate(), ctx.sim().inflationRate(),
                    searchTaxCtx,
                    schedule.conversionByYear(), schedule.conversionTaxByYear(),
                    input.birthYear(), ctx.taxIncome().dsBracketCeilingByYear());

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

            double sp1 = evaluateSustainableSpending(
                    searchPaths, ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(), searchFloors,
                    ctx.portfolio().terminalTarget(), input.phases(), ctx.sim().retirementAge(), ctx.sim().years(),
                    searchTrials, ctx.sim().confidenceLevel(), ctx.portfolio().portfolioFloor(),
                    ctx.portfolio().cashReserveYears(), ctx.portfolio().cashReturnRate(), ctx.sim().inflationRate(),
                    searchTaxCtx,
                    s1.conversionByYear(), s1.conversionTaxByYear(), input.birthYear(),
                    ctx.taxIncome().dsBracketCeilingByYear());

            double sp2 = evaluateSustainableSpending(
                    searchPaths, ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(), searchFloors,
                    ctx.portfolio().terminalTarget(), input.phases(), ctx.sim().retirementAge(), ctx.sim().years(),
                    searchTrials, ctx.sim().confidenceLevel(), ctx.portfolio().portfolioFloor(),
                    ctx.portfolio().cashReserveYears(), ctx.portfolio().cashReturnRate(), ctx.sim().inflationRate(),
                    searchTaxCtx,
                    s2.conversionByYear(), s2.conversionTaxByYear(), input.birthYear(),
                    ctx.taxIncome().dsBracketCeilingByYear());

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

    private double[] allocateAndSmooth(OptimizationSetup ctx, GuardrailOptimizationInput input,
                                       double[] conversionByYear, double[] conversionTaxByYear) {
        // Priority-weighted discretionary allocation
        double[] discretionaryByYear = allocateSpending(
                ctx.sim().portfolioPaths(), ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(),
                ctx.taxIncome().adjustedFloors(), ctx.portfolio().terminalTarget(),
                input.phases(), ctx.sim().retirementAge(), ctx.sim().years(), ctx.sim().trialCount(),
                ctx.sim().confidenceLevel(), ctx.portfolio().portfolioFloor(), ctx.portfolio().cashReserveYears(),
                ctx.portfolio().cashReturnRate(), ctx.sim().inflationRate(), ctx.taxIncome().taxCtx(),
                conversionByYear, conversionTaxByYear, input.birthYear(),
                ctx.taxIncome().dsBracketCeilingByYear());

        // Post-processing — phase blending and YoY smoothing
        int phaseBlendYears = input.phaseBlendYears();
        if (phaseBlendYears > 0 && input.phases() != null && input.phases().size() > 1) {
            applyPhaseBlending(discretionaryByYear, ctx.taxIncome().adjustedFloors(), input.phases(),
                    ctx.sim().retirementAge(), ctx.sim().years(), phaseBlendYears);
        }

        Double maxAdjRate = input.maxAnnualAdjustmentRate() != null
                ? input.maxAnnualAdjustmentRate().doubleValue() : null;
        if (maxAdjRate != null && maxAdjRate > 0) {
            applyYearOverYearSmoothing(discretionaryByYear, ctx.taxIncome().adjustedFloors(), maxAdjRate,
                    ctx.sim().years(), input.phases(), ctx.sim().retirementAge());

            // Re-verify sustainability of smoothed plan; reduce if broken
            if (!isSustainable(ctx.sim().portfolioPaths(), ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(),
                    ctx.taxIncome().adjustedFloors(), discretionaryByYear, ctx.portfolio().terminalTarget(), ctx.sim().years(),
                    ctx.sim().trialCount(), ctx.sim().confidenceLevel(), ctx.portfolio().portfolioFloor(),
                    ctx.portfolio().cashReserveYears(), ctx.portfolio().cashReturnRate(), ctx.taxIncome().taxCtx(),
                    conversionByYear, conversionTaxByYear, ctx.sim().retirementAge(), input.birthYear(),
                    ctx.taxIncome().dsBracketCeilingByYear())) {
                for (int i = 0; i < 10; i++) {
                    for (int y = 0; y < ctx.sim().years(); y++) {
                        discretionaryByYear[y] *= SUSTAINABILITY_REDUCTION_FACTOR;
                    }
                    if (isSustainable(ctx.sim().portfolioPaths(), ctx.taxIncome().incomeByYear(),
                            ctx.taxIncome().surplusTaxByYear(), ctx.taxIncome().adjustedFloors(), discretionaryByYear,
                            ctx.portfolio().terminalTarget(), ctx.sim().years(), ctx.sim().trialCount(),
                            ctx.sim().confidenceLevel(), ctx.portfolio().portfolioFloor(), ctx.portfolio().cashReserveYears(),
                            ctx.portfolio().cashReturnRate(), ctx.taxIncome().taxCtx(),
                            conversionByYear, conversionTaxByYear, ctx.sim().retirementAge(),
                            input.birthYear(), ctx.taxIncome().dsBracketCeilingByYear())) {
                        break;
                    }
                }
            }
        }

        return discretionaryByYear;
    }

    private GuardrailProfileResponse buildResponse(OptimizationSetup ctx,
                                                    GuardrailOptimizationInput input,
                                                    double[] discretionaryByYear,
                                                    double[] conversionByYear,
                                                    double[] conversionTaxByYear,
                                                    RothConversionOptimizer.RothConversionSchedule convSchedule) {
        // Compute corridors + corridor smoothing
        double[][] corridors = computeCorridors(
                ctx.sim().portfolioPaths(), ctx.taxIncome().incomeByYear(), ctx.taxIncome().adjustedFloors(),
                discretionaryByYear, ctx.portfolio().terminalTarget(), ctx.sim().years(), ctx.sim().trialCount());
        smoothCorridors(corridors[0], corridors[1], ctx.sim().years());

        // Clamp corridors to bracket recommended spending (smoothing can overshoot at phase boundaries)
        for (int y = 0; y < ctx.sim().years(); y++) {
            double recommended = ctx.taxIncome().adjustedFloors()[y] + discretionaryByYear[y];
            corridors[0][y] = Math.min(corridors[0][y], recommended);
            corridors[1][y] = Math.max(corridors[1][y], recommended);
        }

        // Simulate with withdrawals to get final balances and per-year median balances
        boolean simPools = conversionByYear != null
                || (ctx.portfolio().initTraditional() > 0 || ctx.portfolio().initRoth() > 0);
        double[] historicalReturns = HistoricalReturns.getReturns();
        var rng2 = input.seed() != null ? new Random(input.seed()) : new Random();
        double[][] yearBalances = new double[ctx.sim().years()][ctx.sim().trialCount()];
        double[] finalBalances = new double[ctx.sim().trialCount()];
        int tradExhaustedCount = 0;
        int exhaustionDeadlineAge = convSchedule != null
                ? convSchedule.exhaustionAge() : ctx.sim().endAge();
        for (int t = 0; t < ctx.sim().trialCount(); t++) {
            var generator = new BlockBootstrapReturnGenerator(
                    historicalReturns, DEFAULT_BLOCK_LENGTH, rng2);
            double[] returnSequence = generator.generateReturnSequence(ctx.sim().years());

            double pTaxable = simPools ? ctx.portfolio().initTaxable() : ctx.portfolio().initialPortfolio();
            double pTraditional = simPools ? ctx.portfolio().initTraditional() : 0;
            double pRoth = simPools ? ctx.portfolio().initRoth() : 0;
            String order = simPools && ctx.portfolio().withdrawalOrder() != null
                    ? ctx.portfolio().withdrawalOrder() : "taxable_first";

            double cashBalance = 0;
            if (ctx.portfolio().cashReserveYears() > 0) {
                double annualSpending = ctx.taxIncome().adjustedFloors()[0] + discretionaryByYear[0];
                cashBalance = annualSpending * ctx.portfolio().cashReserveYears();
                double cashFromTaxable = Math.min(cashBalance, pTaxable);
                pTaxable -= cashFromTaxable;
                double remaining = cashBalance - cashFromTaxable;
                if (remaining > 0) {
                    double fromTrad = Math.min(remaining, pTraditional);
                    pTraditional -= fromTrad;
                    remaining -= fromTrad;
                    pRoth -= remaining;
                    pRoth = Math.max(0, pRoth);
                }
            }

            double priorYearEndTraditional = pTraditional;

            for (int y = 0; y < ctx.sim().years(); y++) {
                double realReturn = returnSequence[y];
                double nominalReturn = toNominal(realReturn, ctx.sim().inflationRate());
                double growthFactor = 1 + nominalReturn;

                pTaxable *= growthFactor;
                pTraditional *= growthFactor;
                pRoth *= growthFactor;
                cashBalance *= (1 + ctx.portfolio().cashReturnRate());

                int age = ctx.sim().retirementAge() + y;

                // --- Roth conversion execution ---
                if (conversionByYear != null && conversionByYear[y] > 0 && pTraditional > 0) {
                    double actualConv = Math.min(conversionByYear[y], pTraditional);
                    pTraditional -= actualConv;
                    pRoth += actualConv;
                    double actualTax = (actualConv < conversionByYear[y])
                            ? conversionTaxByYear[y] * (actualConv / conversionByYear[y])
                            : conversionTaxByYear[y];
                    if (age < EARLY_WITHDRAWAL_AGE) {
                        pTaxable -= Math.min(actualTax, Math.max(0, pTaxable));
                    } else {
                        double taxRem = actualTax;
                        double t1 = Math.min(taxRem, Math.max(0, pTaxable));
                        pTaxable -= t1; taxRem -= t1;
                        double t2 = Math.min(taxRem, Math.max(0, pTraditional));
                        pTraditional -= t2; taxRem -= t2;
                        pRoth -= taxRem;
                    }
                }

                double spending = ctx.taxIncome().adjustedFloors()[y] + discretionaryByYear[y];
                double withdrawal = Math.max(0, spending - ctx.taxIncome().incomeByYear()[y]);

                boolean preAge595 = conversionByYear != null && age < EARLY_WITHDRAWAL_AGE;
                double dsCeiling = ctx.taxIncome().dsBracketCeilingByYear() != null
                        ? ctx.taxIncome().dsBracketCeilingByYear()[y] : 0;
                double dsConvAmt = conversionByYear != null ? conversionByYear[y] : 0;
                var drawn = splitWithdrawal(pTaxable, pTraditional, pRoth,
                        withdrawal, order, preAge595,
                        dsCeiling, ctx.taxIncome().incomeByYear()[y], dsConvAmt, 0);

                if (ctx.portfolio().cashReserveYears() > 0) {
                    if (nominalReturn < 0) {
                        double totalDraw = withdrawal;
                        double cashDraw = Math.min(totalDraw, cashBalance);
                        double equityDraw = totalDraw - cashDraw;
                        double drawnTotal = drawn.total();
                        if (drawnTotal > 0 && equityDraw > 0) {
                            double scale = equityDraw / Math.max(drawnTotal, equityDraw);
                            pTaxable -= drawn.taxable() * scale;
                            pTraditional -= drawn.traditional() * scale;
                            pRoth -= drawn.roth() * scale;
                        }
                        cashBalance -= cashDraw;
                    } else {
                        pTaxable -= drawn.taxable();
                        pTraditional -= drawn.traditional();
                        pRoth -= drawn.roth();
                        double targetCash = spending * ctx.portfolio().cashReserveYears();
                        double replenishment = Math.min(
                                Math.max(0, targetCash - cashBalance),
                                Math.max(0, pTaxable + pTraditional + pRoth) * CASH_REPLENISHMENT_RATE);
                        pTaxable -= replenishment;
                        if (pTaxable < 0) { pTraditional += pTaxable; pTaxable = 0; }
                        cashBalance += replenishment;
                    }
                } else {
                    pTaxable -= drawn.taxable();
                    pTraditional -= drawn.traditional();
                    pRoth -= drawn.roth();
                }

                // Surplus: income exceeds spending — deposit after-tax surplus to taxable
                if (ctx.taxIncome().incomeByYear()[y] > spending) {
                    double grossSurplus = ctx.taxIncome().incomeByYear()[y] - spending;
                    pTaxable += Math.max(0, grossSurplus - ctx.taxIncome().surplusTaxByYear()[y]);
                }

                pTaxable = Math.max(0, pTaxable);
                pTraditional = Math.max(0, pTraditional);
                pRoth = Math.max(0, pRoth);
                cashBalance = Math.max(0, cashBalance);

                double totalBalance = pTaxable + pTraditional + pRoth + cashBalance;
                yearBalances[y][t] = totalBalance;
                priorYearEndTraditional = pTraditional;
            }
            finalBalances[t] = Math.max(0, pTaxable + pTraditional + pRoth + cashBalance);

            // Track traditional exhaustion for mcExhaustionPct
            if (conversionByYear != null && pTraditional <= 0) {
                tradExhaustedCount++;
            }
        }
        double mcExhaustionPct = conversionByYear != null
                ? (double) tradExhaustedCount / ctx.sim().trialCount() : 0;

        double[] medianBalanceByYear = new double[ctx.sim().years()];
        double[] p10BalanceByYear = new double[ctx.sim().years()];
        double[] p25BalanceByYear = new double[ctx.sim().years()];
        double[] p55BalanceByYear = new double[ctx.sim().years()];
        for (int y = 0; y < ctx.sim().years(); y++) {
            Arrays.sort(yearBalances[y]);
            p10BalanceByYear[y] = percentile(yearBalances[y], 0.10);
            p25BalanceByYear[y] = percentile(yearBalances[y], 0.25);
            medianBalanceByYear[y] = percentile(yearBalances[y], 0.50);
            p55BalanceByYear[y] = percentile(yearBalances[y], 0.55);
        }

        Arrays.sort(finalBalances);
        double medianFinal = percentile(finalBalances, 0.50);
        double p10Final = percentile(finalBalances, 0.10);
        double p55Final = percentile(finalBalances, 0.55);
        long failures = Arrays.stream(finalBalances).filter(b -> b <= 0).count();
        double failureRate = (double) failures / ctx.sim().trialCount();

        // Compute contingent spending for each year at P25, Median, P55 portfolio levels
        double[] contingentP25 = new double[ctx.sim().years()];
        double[] contingentMedian = new double[ctx.sim().years()];
        double[] contingentP55 = new double[ctx.sim().years()];
        computeContingentSpending(
                contingentP25, contingentMedian, contingentP55,
                p25BalanceByYear, medianBalanceByYear, p55BalanceByYear,
                ctx, input);

        // Build yearly spending records
        var yearlySpending = new ArrayList<GuardrailYearlySpending>();
        for (int y = 0; y < ctx.sim().years(); y++) {
            int age = ctx.sim().retirementAge() + y;
            int calendarYear = ctx.sim().retirementYear() + y;
            double floor = ctx.taxIncome().adjustedFloors()[y];
            double disc = discretionaryByYear[y];
            double recommended = floor + disc;
            double income = ctx.taxIncome().incomeByYear()[y];
            double withdrawal = Math.max(0, recommended - income);
            String phaseName = findPhaseName(input.phases(), age);

            yearlySpending.add(new GuardrailYearlySpending(
                    calendarYear, age,
                    toBD(recommended), toBD(corridors[0][y]), toBD(corridors[1][y]),
                    toBD(floor), toBD(disc), toBD(income), toBD(withdrawal), phaseName,
                    toBD(medianBalanceByYear[y]),
                    toBD(p10BalanceByYear[y]), toBD(p25BalanceByYear[y]),
                    toBD(p55BalanceByYear[y]),
                    toBD(contingentP25[y]), toBD(contingentMedian[y]),
                    toBD(contingentP55[y])));
        }

        log.info("MC optimization complete: {} trials, {} years, median final balance {}",
                ctx.sim().trialCount(), ctx.sim().years(), toBD(medianFinal));

        // Build conversion schedule response if conversions were optimized
        RothConversionScheduleResponse convScheduleResponse = null;
        if (convSchedule != null) {
            var convYears = new ArrayList<ConversionYearDetail>();
            for (int y = 0; y < ctx.sim().years(); y++) {
                int age = ctx.sim().retirementAge() + y;
                int calendarYear = ctx.sim().retirementYear() + y;
                if (convSchedule.conversionByYear()[y] > 0) {
                    convYears.add(new ConversionYearDetail(
                            calendarYear, age,
                            toBD(convSchedule.conversionByYear()[y]),
                            toBD(convSchedule.conversionTaxByYear()[y]),
                            toBD(convSchedule.traditionalBalance()[y]),
                            toBD(convSchedule.rothBalance()[y]),
                            toBD(convSchedule.projectedRmd()[y]),
                            toBD(ctx.taxIncome().incomeByYear()[y]),
                            toBD(ctx.taxIncome().taxableIncomeByYear()[y]
                                    + convSchedule.conversionByYear()[y]),
                            null));
                }
            }
            convScheduleResponse = new RothConversionScheduleResponse(
                    toBD(convSchedule.lifetimeTaxWith()),
                    toBD(convSchedule.lifetimeTaxWithout()),
                    toBD(convSchedule.lifetimeTaxWithout() - convSchedule.lifetimeTaxWith()),
                    convSchedule.exhaustionAge(),
                    convSchedule.exhaustionTargetMet(),
                    input.conversionBracketRate(),
                    input.rmdTargetBracketRate(),
                    input.traditionalExhaustionBuffer(),
                    toBD(mcExhaustionPct),
                    toBD(convSchedule.targetTraditionalBalance()),
                    input.rmdBracketHeadroom() != null
                            ? input.rmdBracketHeadroom() : new BigDecimal("0.10"),
                    convYears);
        }

        return new GuardrailProfileResponse(
                null, null, "Optimized",
                input.essentialFloor(), input.terminalBalanceTarget(),
                input.returnMean(), input.returnStddev(),
                ctx.sim().trialCount(), input.confidenceLevel(),
                input.phases(), yearlySpending,
                toBD(medianFinal), toBD(failureRate),
                toBD(p10Final), toBD(p55Final),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"),
                convScheduleResponse);
    }

    private double[][] runMonteCarloTrials(int trialCount, int years,
                                            double initialPortfolio,
                                            double[] historicalReturns, Random rng,
                                            double inflationRate) {
        double[][] paths = new double[trialCount][years + 1];
        for (int t = 0; t < trialCount; t++) {
            var generator = new BlockBootstrapReturnGenerator(historicalReturns, DEFAULT_BLOCK_LENGTH, rng);
            double[] returnSequence = generator.generateReturnSequence(years);
            paths[t][0] = initialPortfolio;
            for (int y = 0; y < years; y++) {
                double nominalReturn = toNominal(returnSequence[y], inflationRate);
                double growthFactor = 1 + nominalReturn;
                paths[t][y + 1] = paths[t][y] * growthFactor;
            }
        }
        return paths;
    }

    private static double toNominal(double realReturn, double inflationRate) {
        return (1 + realReturn) * (1 + inflationRate) - 1;
    }

    private record IncomeYearData(double totalIncome, double taxableIncome) {}

    private IncomeYearData[] computeDeterministicIncome(List<ProjectionIncomeSourceInput> sources,
                                                         int retirementAge, int years, int birthYear) {
        IncomeYearData[] result = new IncomeYearData[years];
        for (int y = 0; y < years; y++) {
            result[y] = new IncomeYearData(0, 0);
        }
        if (sources == null || sources.isEmpty()) {
            return result;
        }

        for (int y = 0; y < years; y++) {
            int age = retirementAge + y;
            int yearsInRetirement = y + 1;
            double totalIncome = 0;
            double taxableIncome = 0;
            for (var source : sources) {
                if (!isActiveForAge(source, age)) {
                    continue;
                }
                double gross = source.annualAmount().doubleValue();

                if (source.inflationRate() != null
                        && source.inflationRate().compareTo(BigDecimal.ZERO) > 0) {
                    gross *= Math.pow(1 + source.inflationRate().doubleValue(), yearsInRetirement - 1);
                }

                double amount = gross;

                // For rental properties, subtract all cash outflows to get net cash flow,
                // matching IncomeSourceProcessor: operating expenses, mortgage interest,
                // property tax, AND mortgage principal (principal reduces available cash even
                // though it is not tax-deductible).
                if (source.incomeType() == IncomeSourceType.RENTAL_PROPERTY) {
                    amount -= nullSafe(source.annualOperatingExpenses());
                    amount -= nullSafe(source.annualMortgageInterest());
                    amount -= nullSafe(source.annualPropertyTax());
                    amount -= nullSafe(source.annualMortgagePrincipal());
                    amount = Math.max(0, amount);
                }

                // Apply boundary multiplier (0.5 at startAge/endAge) for recurring sources only.
                // One-time sources pay their full amount at startAge, matching ICC and ISP.
                if (!source.oneTime()
                        && (age == source.startAge()
                                || (source.endAge() != null && age == source.endAge()))) {
                    amount *= 0.5;
                }
                totalIncome += amount;

                // All non-rental income is treated as taxable for MC purposes.
                // Rental net cash is excluded (complex passive-loss rules not applicable here).
                if (source.incomeType() != IncomeSourceType.RENTAL_PROPERTY) {
                    taxableIncome += amount;
                }
            }
            result[y] = new IncomeYearData(totalIncome, taxableIncome);
        }
        return result;
    }

    private boolean isActiveForAge(ProjectionIncomeSourceInput source, int age) {
        if (age < source.startAge()) {
            return false;
        }
        if (source.endAge() != null && age > source.endAge()) {
            return false;
        }
        if (source.oneTime() && age != source.startAge()) {
            return false;
        }
        return true;
    }

    private double[] verifyEssentialFloor(double[][] paths, double[] income,
                                           double essentialFloor,
                                           double confidenceLevel, int years, int trialCount,
                                           double inflationRate) {
        double[] floors = new double[years];
        int confidenceIndex = (int) Math.ceil((1 - confidenceLevel) * trialCount) - 1;
        confidenceIndex = Math.max(0, Math.min(confidenceIndex, trialCount - 1));

        for (int y = 0; y < years; y++) {
            double inflatedFloor = essentialFloor * Math.pow(1 + inflationRate, y);

            double[] balancesAtYear = new double[trialCount];
            for (int t = 0; t < trialCount; t++) {
                // Simulate year-by-year balance with floor withdrawals and compounded growth.
                // Using raw cumulative subtraction from the unconstrained path would overestimate
                // the remaining balance because withdrawn dollars would have compounded if left.
                double balance = paths[t][0];
                for (int py = 0; py <= y; py++) {
                    double growthFactor = paths[t][py + 1] / paths[t][py];
                    balance *= growthFactor;
                    double floorAtPy = essentialFloor * Math.pow(1 + inflationRate, py);
                    balance -= Math.max(0, floorAtPy - income[py]);
                    balance = Math.max(0, balance);
                }
                balancesAtYear[t] = balance;
            }
            Arrays.sort(balancesAtYear);

            double availableAtConfidence = balancesAtYear[confidenceIndex];
            // Capacity = portfolio remaining after cumulative floor withdrawals + this year's income.
            // Terminal target is NOT subtracted here — essential spending is essential.
            // The terminal target constrains discretionary spending via isSustainable().
            double capacityForFloor = availableAtConfidence + income[y];

            if (capacityForFloor >= inflatedFloor) {
                floors[y] = inflatedFloor;
            } else {
                floors[y] = Math.max(0, Math.min(inflatedFloor, capacityForFloor));
            }
        }
        return floors;
    }

    private double[] allocateSpending(double[][] paths, double[] income, double[] surplusTax,
                                       double[] floors, double terminalTarget,
                                       List<GuardrailPhaseInput> phases,
                                       int retirementAge, int years, int trialCount,
                                       double confidenceLevel, double portfolioFloor,
                                       int cashReserveYears, double cashReturnRate,
                                       double inflationRate, TaxContext taxCtx,
                                       double[] conversionByYear,
                                       double[] conversionTaxByYear, int birthYear,
                                       double[] dsBracketCeilingByYear) {
        double[] discretionary = new double[years];

        if (phases == null || phases.isEmpty()) {
            double maxDisc = binarySearchDiscretionary(
                    paths, income, surplusTax, floors, discretionary, terminalTarget,
                    0, years - 1, years, trialCount, confidenceLevel, portfolioFloor,
                    cashReserveYears, cashReturnRate, taxCtx,
                    conversionByYear, conversionTaxByYear, retirementAge, birthYear,
                    dsBracketCeilingByYear);
            Arrays.fill(discretionary, maxDisc);
            return discretionary;
        }

        // Check if any phase has target spending — use target-based allocation
        boolean hasTargets = phases.stream()
                .anyMatch(p -> p.targetSpending() != null
                        && p.targetSpending().compareTo(BigDecimal.ZERO) > 0);

        if (hasTargets) {
            return allocateByTargets(paths, income, surplusTax, floors, terminalTarget, phases,
                    retirementAge, years, trialCount, confidenceLevel, portfolioFloor,
                    cashReserveYears, cashReturnRate, inflationRate, taxCtx,
                    conversionByYear, conversionTaxByYear, birthYear,
                    dsBracketCeilingByYear);
        }

        // Legacy: sort phases by priority weight (highest first)
        var sortedPhases = phases.stream()
                .sorted(Comparator.comparingInt(GuardrailPhaseInput::priorityWeight).reversed())
                .toList();

        for (var phase : sortedPhases) {
            int phaseStart = phase.startAge() - retirementAge;
            int phaseEnd = phase.endAge() != null
                    ? Math.min(phase.endAge() - retirementAge, years - 1)
                    : years - 1;
            phaseStart = Math.max(0, phaseStart);
            phaseEnd = Math.min(phaseEnd, years - 1);

            if (phaseStart > phaseEnd) {
                continue;
            }

            double maxDisc = binarySearchDiscretionary(
                    paths, income, surplusTax, floors, discretionary, terminalTarget,
                    phaseStart, phaseEnd, years, trialCount, confidenceLevel, portfolioFloor,
                    cashReserveYears, cashReturnRate, taxCtx,
                    conversionByYear, conversionTaxByYear, retirementAge, birthYear,
                    dsBracketCeilingByYear);

            for (int y = phaseStart; y <= phaseEnd; y++) {
                discretionary[y] = maxDisc;
            }
        }

        return discretionary;
    }

    private double[] allocateByTargets(double[][] paths, double[] income, double[] surplusTax,
                                        double[] floors, double terminalTarget,
                                        List<GuardrailPhaseInput> phases,
                                        int retirementAge, int years, int trialCount,
                                        double confidenceLevel, double portfolioFloor,
                                        int cashReserveYears, double cashReturnRate,
                                        double inflationRate, TaxContext taxCtx,
                                        double[] conversionByYear,
                                        double[] conversionTaxByYear, int birthYear,
                                        double[] dsBracketCeilingByYear) {
        double[] discretionary = new double[years];

        for (var phase : phases) {
            int phaseStart = phase.startAge() - retirementAge;
            int phaseEnd = phase.endAge() != null
                    ? Math.min(phase.endAge() - retirementAge, years - 1)
                    : years - 1;
            phaseStart = Math.max(0, phaseStart);
            phaseEnd = Math.min(phaseEnd, years - 1);

            if (phaseStart > phaseEnd) {
                continue;
            }

            double found = binarySearchDiscretionary(
                    paths, income, surplusTax, floors, discretionary, terminalTarget,
                    phaseStart, phaseEnd, years, trialCount, confidenceLevel, portfolioFloor,
                    cashReserveYears, cashReturnRate, taxCtx,
                    conversionByYear, conversionTaxByYear, retirementAge, birthYear,
                    dsBracketCeilingByYear);

            double capped;
            if (phase.targetSpending() != null
                    && phase.targetSpending().compareTo(java.math.BigDecimal.ZERO) > 0) {
                double avgFloor = 0;
                double avgInflatedTarget = 0;
                int count = 0;
                double nominalTarget = phase.targetSpending().doubleValue();
                for (int y = phaseStart; y <= phaseEnd; y++) {
                    avgFloor += floors[y];
                    avgInflatedTarget += nominalTarget * Math.pow(1 + inflationRate, y);
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
    private double evaluateSustainableSpending(
            double[][] paths, double[] income, double[] surplusTax,
            double[] floors, double terminalTarget,
            List<GuardrailPhaseInput> phases, int retirementAge, int years,
            int trialCount, double confidenceLevel, double portfolioFloor,
            int cashReserveYears, double cashReturnRate, double inflationRate,
            TaxContext taxCtx,
            double[] conversionByYear, double[] conversionTaxByYear, int birthYear,
            double[] dsBracketCeilingByYear) {

        double low = 0;
        double high = MAX_SPENDING_CEILING;
        double[] testDiscretionary = new double[years];

        for (int iter = 0; iter < SPENDING_BINARY_SEARCH_ITERATIONS; iter++) {
            double mid = (low + high) / 2;
            Arrays.fill(testDiscretionary, mid);

            if (isSustainable(paths, income, surplusTax, floors, testDiscretionary,
                    terminalTarget, years, trialCount, confidenceLevel, portfolioFloor,
                    cashReserveYears, cashReturnRate, taxCtx,
                    conversionByYear, conversionTaxByYear, retirementAge, birthYear,
                    dsBracketCeilingByYear)) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return floors[0] + low;
    }

    private double binarySearchDiscretionary(double[][] paths, double[] income, double[] surplusTax,
                                              double[] floors, double[] currentDiscretionary,
                                              double terminalTarget,
                                              int phaseStart, int phaseEnd,
                                              int years, int trialCount,
                                              double confidenceLevel, double portfolioFloor,
                                              int cashReserveYears, double cashReturnRate,
                                              TaxContext taxCtx,
                                              double[] conversionByYear,
                                              double[] conversionTaxByYear,
                                              int retirementAge, int birthYear,
                                              double[] dsBracketCeilingByYear) {
        double low = 0;
        double high = MAX_SPENDING_CEILING;

        for (int iter = 0; iter < 40; iter++) {
            double mid = (low + high) / 2;

            double[] testDiscretionary = currentDiscretionary.clone();
            for (int y = phaseStart; y <= phaseEnd; y++) {
                testDiscretionary[y] = mid;
            }

            if (isSustainable(paths, income, surplusTax, floors, testDiscretionary,
                    terminalTarget, years, trialCount, confidenceLevel, portfolioFloor,
                    cashReserveYears, cashReturnRate, taxCtx,
                    conversionByYear, conversionTaxByYear, retirementAge, birthYear,
                    dsBracketCeilingByYear)) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return low;
    }

    private boolean isSustainable(double[][] paths, double[] income, double[] surplusTax,
                                   double[] floors, double[] discretionary,
                                   double terminalTarget, int years, int trialCount,
                                   double confidenceLevel, double portfolioFloor,
                                   int cashReserveYears, double cashReturnRate,
                                   TaxContext taxCtx,
                                   double[] conversionByYear,
                                   double[] conversionTaxByYear,
                                   int retirementAge, int birthYear,
                                   double[] dsBracketCeilingByYear) {
        double[] finalBalances = new double[trialCount];
        double[] minBalances = new double[trialCount];

        boolean hasPools = taxCtx != null && (taxCtx.initTraditional > 0 || taxCtx.initRoth > 0);
        boolean hasConversions = conversionByYear != null;

        for (int t = 0; t < trialCount; t++) {
            double initialBalance = paths[t][0];

            // Pool tracking for tax-aware withdrawals
            double pTaxable = hasPools ? taxCtx.initTaxable : initialBalance;
            double pTraditional = hasPools ? taxCtx.initTraditional : 0;
            double pRoth = hasPools ? taxCtx.initRoth : 0;
            String order = hasPools ? taxCtx.withdrawalOrder : "taxable_first";

            double cashBalance = 0;
            if (cashReserveYears > 0) {
                double annualSpending = floors[0] + discretionary[0];
                cashBalance = annualSpending * cashReserveYears;
                double cashFromTaxable = Math.min(cashBalance, pTaxable);
                pTaxable -= cashFromTaxable;
                double remaining = cashBalance - cashFromTaxable;
                if (remaining > 0) {
                    double fromTrad = Math.min(remaining, pTraditional);
                    pTraditional -= fromTrad;
                    remaining -= fromTrad;
                    pRoth -= remaining;
                    pRoth = Math.max(0, pRoth);
                }
            }

            double minBalance = pTaxable + pTraditional + pRoth + cashBalance;
            double priorYearEndTraditional = pTraditional;

            for (int y = 0; y < years; y++) {
                double growthFactor = paths[t][y + 1] / paths[t][y];
                double equityReturn = growthFactor - 1.0;

                pTaxable *= growthFactor;
                pTraditional *= growthFactor;
                pRoth *= growthFactor;
                cashBalance *= (1 + cashReturnRate);

                int age = retirementAge + y;

                // --- Roth conversion execution ---
                if (hasConversions && conversionByYear[y] > 0 && pTraditional > 0) {
                    double actualConv = Math.min(conversionByYear[y], pTraditional);
                    pTraditional -= actualConv;
                    pRoth += actualConv;
                    double actualTax = (actualConv < conversionByYear[y])
                            ? conversionTaxByYear[y] * (actualConv / conversionByYear[y])
                            : conversionTaxByYear[y];
                    if (age < EARLY_WITHDRAWAL_AGE) {
                        pTaxable -= Math.min(actualTax, Math.max(0, pTaxable));
                    } else {
                        double taxRem = actualTax;
                        double t1 = Math.min(taxRem, Math.max(0, pTaxable));
                        pTaxable -= t1; taxRem -= t1;
                        double t2 = Math.min(taxRem, Math.max(0, pTraditional));
                        pTraditional -= t2; taxRem -= t2;
                        pRoth -= taxRem;
                    }
                }

                double spending = floors[y] + discretionary[y];
                double withdrawal = Math.max(0, spending - income[y]);

                // Split withdrawal across pools (59.5 rule: taxable only before age 60)
                boolean preAge595 = hasConversions && age < EARLY_WITHDRAWAL_AGE;
                double dsCeiling = dsBracketCeilingByYear != null
                        ? dsBracketCeilingByYear[y] : 0;
                double dsConvAmt = conversionByYear != null ? conversionByYear[y] : 0;
                var drawn = splitWithdrawal(pTaxable, pTraditional, pRoth,
                        withdrawal, order, preAge595,
                        dsCeiling, income[y], dsConvAmt, 0);

                // Estimate tax on traditional withdrawal using pre-computed marginal rate
                double withdrawalTax = 0;
                if (hasPools && drawn.traditional() > 0) {
                    withdrawalTax = estimateWithdrawalTax(drawn.traditional(), taxCtx.marginalRateByYear[y]);
                }

                double totalDraw = withdrawal + withdrawalTax;

                if (cashReserveYears > 0) {
                    if (equityReturn < 0) {
                        double cashDraw = Math.min(totalDraw, cashBalance);
                        double equityDraw = totalDraw - cashDraw;
                        double drawnTotal = drawn.total();
                        if (drawnTotal > 0 && equityDraw > 0) {
                            double scale = equityDraw / Math.max(drawnTotal, equityDraw);
                            pTaxable -= drawn.taxable() * scale
                                    + withdrawalTax * Math.min(pTaxable, withdrawalTax)
                                    / Math.max(1, pTaxable + pTraditional + pRoth);
                        } else {
                            pTaxable -= drawn.taxable();
                            pTraditional -= drawn.traditional();
                            pRoth -= drawn.roth();
                        }
                        cashBalance -= cashDraw;
                    } else {
                        pTaxable -= drawn.taxable();
                        pTraditional -= drawn.traditional();
                        pRoth -= drawn.roth();
                        double taxRem = withdrawalTax;
                        double taxFromTax = Math.min(taxRem, Math.max(0, pTaxable));
                        pTaxable -= taxFromTax; taxRem -= taxFromTax;
                        double taxFromTrad = Math.min(taxRem, Math.max(0, pTraditional));
                        pTraditional -= taxFromTrad; taxRem -= taxFromTrad;
                        pRoth -= taxRem;

                        double targetCash = spending * cashReserveYears;
                        double replenishment = Math.min(
                                Math.max(0, targetCash - cashBalance),
                                Math.max(0, pTaxable + pTraditional + pRoth) * CASH_REPLENISHMENT_RATE);
                        pTaxable -= replenishment;
                        if (pTaxable < 0) { pTraditional += pTaxable; pTaxable = 0; }
                        cashBalance += replenishment;
                    }
                } else {
                    pTaxable -= drawn.taxable();
                    pTraditional -= drawn.traditional();
                    pRoth -= drawn.roth();
                    double taxRem = withdrawalTax;
                    double taxFromTax = Math.min(taxRem, Math.max(0, pTaxable));
                    pTaxable -= taxFromTax; taxRem -= taxFromTax;
                    double taxFromTrad = Math.min(taxRem, Math.max(0, pTraditional));
                    pTraditional -= taxFromTrad; taxRem -= taxFromTrad;
                    pRoth -= taxRem;
                }

                // Surplus: income exceeds spending — deposit after-tax surplus to taxable
                if (income[y] > spending) {
                    double grossSurplus = income[y] - spending;
                    pTaxable += Math.max(0, grossSurplus - surplusTax[y]);
                }

                pTaxable = Math.max(0, pTaxable);
                pTraditional = Math.max(0, pTraditional);
                pRoth = Math.max(0, pRoth);
                cashBalance = Math.max(0, cashBalance);

                double totalBalance = pTaxable + pTraditional + pRoth + cashBalance;
                minBalance = Math.min(minBalance, totalBalance);
                priorYearEndTraditional = pTraditional;
            }
            finalBalances[t] = pTaxable + pTraditional + pRoth + cashBalance;
            minBalances[t] = minBalance;
        }

        Arrays.sort(finalBalances);
        double balanceAtConfidence = percentile(finalBalances, 1.0 - confidenceLevel);
        if (balanceAtConfidence < terminalTarget) {
            return false;
        }

        if (portfolioFloor > 0) {
            Arrays.sort(minBalances);
            double minAtConfidence = percentile(minBalances, 1.0 - confidenceLevel);
            if (minAtConfidence < portfolioFloor) {
                return false;
            }
        }

        return true;
    }

    private double[][] computeCorridors(double[][] paths, double[] income,
                                         double[] floors, double[] discretionary,
                                         double terminalTarget, int years, int trialCount) {
        double[] corridorLow = new double[years];
        double[] corridorHigh = new double[years];

        for (int y = 0; y < years; y++) {
            double baseSpending = floors[y] + discretionary[y];

            double[] sustainableAtYear = new double[trialCount];
            for (int t = 0; t < trialCount; t++) {
                double balance = paths[t][0];
                for (int py = 0; py < y; py++) {
                    double gf = paths[t][py + 1] / paths[t][py];
                    double spending = floors[py] + discretionary[py];
                    double withdrawal = Math.max(0, spending - income[py]);
                    balance = Math.max(0, balance * gf - withdrawal);
                }
                double gf = paths[t][y + 1] / paths[t][y];
                double availableThisYear = balance * gf + income[y];
                sustainableAtYear[t] = availableThisYear;
            }
            Arrays.sort(sustainableAtYear);

            double p10 = percentile(sustainableAtYear, 0.10);
            double p90 = percentile(sustainableAtYear, 0.90);

            corridorLow[y] = Math.max(floors[y], Math.min(p10, baseSpending));
            corridorHigh[y] = Math.max(baseSpending, Math.min(p90, baseSpending * 3));
        }

        return new double[][]{corridorLow, corridorHigh};
    }

    private void applyPhaseBlending(double[] discretionary, double[] floors,
                                     List<GuardrailPhaseInput> phases,
                                     int retirementAge, int years, int blendYears) {
        double[] totalSpending = new double[years];
        for (int y = 0; y < years; y++) {
            totalSpending[y] = floors[y] + discretionary[y];
        }

        for (int p = 1; p < phases.size(); p++) {
            int boundaryAge = phases.get(p).startAge();
            int boundaryYear = boundaryAge - retirementAge;
            if (boundaryYear <= 0 || boundaryYear >= years) {
                continue;
            }

            int windowStart = Math.max(0, boundaryYear - blendYears);
            int windowEnd = Math.min(years - 1, boundaryYear + blendYears - 1);
            int windowLen = windowEnd - windowStart + 1;
            if (windowLen <= 1) {
                continue;
            }

            double startSpend = totalSpending[windowStart];
            double endSpend = totalSpending[windowEnd];

            for (int y = windowStart; y <= windowEnd; y++) {
                double t = (double) (y - windowStart) / (windowLen - 1);
                double blended = startSpend + t * (endSpend - startSpend);
                totalSpending[y] = blended;
                discretionary[y] = Math.max(0, blended - floors[y]);
            }
        }
    }

    private void applyYearOverYearSmoothing(double[] discretionary, double[] floors,
                                             double maxRate, int years,
                                             List<GuardrailPhaseInput> phases,
                                             int retirementAge) {
        // Build set of year indices where a new phase starts (skip first phase since
        // there's no prior year to smooth from)
        var phaseStartYears = new java.util.HashSet<Integer>();
        if (phases != null && phases.size() > 1) {
            for (int i = 1; i < phases.size(); i++) {
                int yearIdx = phases.get(i).startAge() - retirementAge;
                if (yearIdx > 0 && yearIdx < years) {
                    phaseStartYears.add(yearIdx);
                }
            }
        }

        double[] totalSpending = new double[years];
        for (int y = 0; y < years; y++) {
            totalSpending[y] = floors[y] + discretionary[y];
        }

        for (int y = 1; y < years; y++) {
            // At phase boundaries, allow spending to jump to the phase's allocated level
            if (phaseStartYears.contains(y)) {
                continue;
            }
            double maxUp = totalSpending[y - 1] * (1 + maxRate);
            double maxDown = totalSpending[y - 1] * (1 - maxRate);
            totalSpending[y] = Math.max(maxDown, Math.min(maxUp, totalSpending[y]));
            discretionary[y] = Math.max(0, totalSpending[y] - floors[y]);
        }
    }

    private void smoothCorridors(double[] corridorLow, double[] corridorHigh, int years) {
        if (years < 3) {
            return;
        }
        double[] smoothLow = new double[years];
        double[] smoothHigh = new double[years];

        smoothLow[0] = corridorLow[0];
        smoothHigh[0] = corridorHigh[0];
        smoothLow[years - 1] = corridorLow[years - 1];
        smoothHigh[years - 1] = corridorHigh[years - 1];

        for (int y = 1; y < years - 1; y++) {
            smoothLow[y] = (corridorLow[y - 1] + corridorLow[y] + corridorLow[y + 1]) / 3.0;
            smoothHigh[y] = (corridorHigh[y - 1] + corridorHigh[y] + corridorHigh[y + 1]) / 3.0;
        }

        System.arraycopy(smoothLow, 0, corridorLow, 0, years);
        System.arraycopy(smoothHigh, 0, corridorHigh, 0, years);

        for (int y = 0; y < years; y++) {
            if (corridorLow[y] > corridorHigh[y]) {
                double avg = (corridorLow[y] + corridorHigh[y]) / 2.0;
                corridorLow[y] = avg;
                corridorHigh[y] = avg;
            }
        }
    }

    // --- Pool-aware withdrawal tax context ---

    private record TaxContext(
            double initTaxable, double initTraditional, double initRoth,
            String withdrawalOrder, double[] marginalRateByYear) {}

    // --- Pool-aware withdrawal helpers for tax modeling ---

    private static double sumByType(List<? extends ProjectionAccountInput> accounts, String type) {
        return accounts.stream()
                .filter(a -> type.equals(a.accountType()))
                .mapToDouble(a -> a.initialBalance().doubleValue())
                .sum();
    }

    /**
     * Split a withdrawal need across three pools using the specified ordering.
     * Returns a {@link PoolWithdrawal} describing how much comes from each pool.
     * When preAge595 is true, only the taxable pool is available (59.5 early withdrawal rule).
     */
    static PoolWithdrawal splitWithdrawal(double taxable, double traditional, double roth,
                                           double need, String order, boolean preAge595,
                                           double dsBracketCeiling, double otherIncome,
                                           double conversionAmount, double rmdAmount) {
        if (need <= 0) return new PoolWithdrawal(0, 0, 0);
        if (preAge595) {
            double drawn = Math.min(need, Math.max(0, taxable));
            return new PoolWithdrawal(drawn, 0, 0);
        }

        // Dynamic Sequencing: Traditional first up to bracket space, then taxable, then Roth
        if (PoolStrategy.WITHDRAWAL_ORDER_DYNAMIC_SEQUENCING.equals(order)) {
            double bracketSpace = Math.max(0,
                    dsBracketCeiling - otherIncome - conversionAmount - rmdAmount);
            double fromTrad = Math.min(bracketSpace, Math.min(Math.max(0, traditional), need));
            double remaining = need - fromTrad;
            double fromTax = Math.min(remaining, Math.max(0, taxable));
            remaining -= fromTax;
            double fromRoth = Math.min(remaining, Math.max(0, roth));
            return new PoolWithdrawal(fromTax, fromTrad, fromRoth);
        }

        double[] pools;
        int[] mapping; // maps pool index -> result index (0=taxable, 1=traditional, 2=roth)

        if ("traditional_first".equals(order)) {
            pools = new double[]{traditional, taxable, roth};
            mapping = new int[]{1, 0, 2};
        } else if ("roth_first".equals(order)) {
            pools = new double[]{roth, taxable, traditional};
            mapping = new int[]{2, 0, 1};
        } else { // taxable_first (default)
            pools = new double[]{taxable, traditional, roth};
            mapping = new int[]{0, 1, 2};
        }

        double[] amounts = new double[3];
        double remaining = need;
        for (int i = 0; i < 3; i++) {
            double drawn = Math.min(remaining, Math.max(0, pools[i]));
            amounts[mapping[i]] = drawn;
            remaining -= drawn;
            if (remaining <= 0) break;
        }
        return new PoolWithdrawal(amounts[0], amounts[1], amounts[2]);
    }

    /**
     * Estimates marginal tax on a traditional withdrawal using the pre-computed
     * marginal rate for the year. This avoids calling computeTax() (which uses
     * BigDecimal) inside the hot MC trial loop — with 10,000 trials × 28 years
     * × 40 binary search iterations, the BigDecimal overhead is prohibitive.
     */
    private static double estimateWithdrawalTax(double traditionalWithdrawal,
                                                  double marginalRate) {
        if (traditionalWithdrawal <= 0) return 0;
        return traditionalWithdrawal * marginalRate;
    }

    /**
     * Pre-computes the marginal tax rate for traditional withdrawals at each
     * retirement year. Uses a representative withdrawal amount ($50K) to find
     * the marginal rate at the income level of (taxableIncome + $50K).
     */
    private double[] precomputeMarginalRates(double[] taxableIncomeByYear,
                                               int retirementYear, int years,
                                               FilingStatus filingStatus) {
        double[] rates = new double[years];
        if (taxCalculator == null) return rates;

        double probeAmount = 50_000;
        for (int y = 0; y < years; y++) {
            int taxYear = retirementYear + y;
            double baseIncome = taxableIncomeByYear[y];
            double baseTax = baseIncome > 0
                    ? taxCalculator.computeTax(BigDecimal.valueOf(baseIncome), taxYear, filingStatus).doubleValue()
                    : 0;
            double totalTax = taxCalculator.computeTax(
                    BigDecimal.valueOf(baseIncome + probeAmount), taxYear, filingStatus).doubleValue();
            rates[y] = (totalTax - baseTax) / probeAmount;
        }
        return rates;
    }

    private double computeSurplusTax(double taxableIncome, int taxYear, FilingStatus filingStatus) {
        if (taxCalculator == null || taxableIncome <= 0) {
            return 0.0;
        }
        BigDecimal tax = taxCalculator.computeTax(
                BigDecimal.valueOf(taxableIncome), taxYear, filingStatus);
        return tax.doubleValue();
    }

    private static double nullSafe(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }

    /**
     * Enhances taxableIncomeByYear with rental property effects: depreciation
     * deductions, passive loss rules, and suspended loss carryforward. This gives
     * the MC trial withdrawal-tax estimates a more accurate baseline income.
     */
    private double[] computeRentalAwareTaxableIncome(double[] baseTaxableIncome,
                                                      List<ProjectionIncomeSourceInput> sources,
                                                      IncomeYearData[] incomeData,
                                                      int retirementAge, int birthYear, int years) {
        double[] result = Arrays.copyOf(baseTaxableIncome, years);
        if (sources == null || sources.isEmpty()) {
            return result;
        }

        var rentalSources = sources.stream()
                .filter(s -> s.incomeType() == IncomeSourceType.RENTAL_PROPERTY)
                .toList();
        if (rentalSources.isEmpty()) {
            return result;
        }

        var calculator = new RentalLossCalculator();
        var suspendedBySource = new java.util.HashMap<ProjectionIncomeSourceInput, BigDecimal>();
        for (var source : rentalSources) {
            suspendedBySource.put(source, BigDecimal.ZERO);
        }

        for (int y = 0; y < years; y++) {
            int age = retirementAge + y;
            int calendarYear = birthYear + age;
            double baseOtherIncome = y < baseTaxableIncome.length ? baseTaxableIncome[y] : 0;
            double yearAdjustment = 0;

            for (var source : rentalSources) {
                if (!isActiveForAge(source, age)) {
                    continue;
                }

                double gross = source.annualAmount().doubleValue();
                if (source.inflationRate() != null
                        && source.inflationRate().compareTo(BigDecimal.ZERO) > 0) {
                    gross *= Math.pow(1 + source.inflationRate().doubleValue(), y);
                }

                double expenses = nullSafe(source.annualOperatingExpenses())
                        + nullSafe(source.annualPropertyTax());
                double depreciation = 0;
                if (source.depreciationByYear() != null) {
                    var depBd = source.depreciationByYear().get(calendarYear);
                    if (depBd != null) {
                        depreciation = depBd.doubleValue();
                    }
                }
                double mortgageInterest = nullSafe(source.annualMortgageInterest());
                double netRentalIncome = gross - expenses - mortgageInterest - depreciation;

                var priorSuspended = suspendedBySource.get(source);
                var lossResult = calculator.applyLossRules(
                        BigDecimal.valueOf(netRentalIncome),
                        source.taxTreatment(),
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(Math.max(0, baseOtherIncome)),
                        priorSuspended);

                suspendedBySource.put(source, lossResult.lossSuspended());
                yearAdjustment += lossResult.netTaxableIncome().doubleValue();
            }

            result[y] = Math.max(0, result[y] + yearAdjustment);
        }

        return result;
    }

    private double totalPortfolio(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .mapToDouble(a -> a.initialBalance().doubleValue())
                .sum();
    }

    private String findPhaseName(List<GuardrailPhaseInput> phases, int age) {
        if (phases == null || phases.isEmpty()) {
            return "Retirement";
        }
        for (var phase : phases) {
            if (age >= phase.startAge()
                    && (phase.endAge() == null || age <= phase.endAge())) {
                return phase.name();
            }
        }
        return "Retirement";
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        double index = p * (sorted.length - 1);
        int lower = (int) Math.floor(index);
        int upper = Math.min(lower + 1, sorted.length - 1);
        double fraction = index - lower;
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower]);
    }

    private static BigDecimal toBD(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, ROUNDING);
    }

    /**
     * Computes contingent spending for each retirement year at P25, Median, and P55
     * portfolio levels. For each balance level, runs a binary search to find the
     * maximum uniform annual spending sustainable from that year to endAge.
     */
    private void computeContingentSpending(
            double[] contingentP25, double[] contingentMedian, double[] contingentP55,
            double[] p25BalanceByYear, double[] medianBalanceByYear, double[] p55BalanceByYear,
            OptimizationSetup ctx, GuardrailOptimizationInput input) {
        double[] historicalReturns = HistoricalReturns.getReturns();
        double inflationRate = ctx.sim().inflationRate();
        double terminalTarget = ctx.portfolio().terminalTarget();
        double confidenceLevel = ctx.sim().confidenceLevel();
        int contingentTrials = 200;
        Random rng = input.seed() != null ? new Random(input.seed() + 99) : new Random();

        for (int y = 0; y < ctx.sim().years(); y++) {
            int remainingYears = ctx.sim().years() - y;
            if (remainingYears <= 0) {
                contingentP25[y] = 0;
                contingentMedian[y] = 0;
                contingentP55[y] = 0;
                continue;
            }

            contingentP25[y] = findSustainableSpendingFromYear(
                    p25BalanceByYear[y], y, ctx.sim().years(), historicalReturns,
                    inflationRate, terminalTarget, confidenceLevel,
                    contingentTrials, rng);

            contingentMedian[y] = findSustainableSpendingFromYear(
                    medianBalanceByYear[y], y, ctx.sim().years(), historicalReturns,
                    inflationRate, terminalTarget, confidenceLevel,
                    contingentTrials, rng);

            contingentP55[y] = findSustainableSpendingFromYear(
                    p55BalanceByYear[y], y, ctx.sim().years(), historicalReturns,
                    inflationRate, terminalTarget, confidenceLevel,
                    contingentTrials, rng);
        }
    }

    private double findSustainableSpendingFromYear(
            double startingBalance, int fromYear, int toYear,
            double[] historicalReturns, double inflationRate,
            double terminalTarget, double confidenceLevel,
            int trialCount, Random rng) {
        double low = 0;
        double high = startingBalance * 0.15;

        for (int iter = 0; iter < 25; iter++) {
            double mid = (low + high) / 2;
            if (canSustainSpending(startingBalance, mid, fromYear, toYear,
                    historicalReturns, inflationRate, terminalTarget,
                    confidenceLevel, trialCount, rng)) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private boolean canSustainSpending(
            double balance, double annualSpending, int fromYear, int toYear,
            double[] historicalReturns, double inflationRate,
            double terminalTarget, double confidenceLevel,
            int trialCount, Random rng) {
        int successes = 0;
        int remainingYears = toYear - fromYear;
        for (int t = 0; t < trialCount; t++) {
            double bal = balance;
            var generator = new BlockBootstrapReturnGenerator(
                    historicalReturns, DEFAULT_BLOCK_LENGTH, rng);
            double[] returns = generator.generateReturnSequence(remainingYears);
            for (int y = 0; y < remainingYears; y++) {
                double nominalReturn = toNominal(returns[y], inflationRate);
                bal *= (1 + nominalReturn);
                double inflatedSpending = annualSpending * Math.pow(1 + inflationRate, y);
                bal -= inflatedSpending;
                if (bal <= 0) break;
            }
            if (bal >= terminalTarget) successes++;
        }
        return (double) successes / trialCount >= confidenceLevel;
    }

    private GuardrailProfileResponse emptyResult(GuardrailOptimizationInput input) {
        return new GuardrailProfileResponse(
                null, null, "Optimized",
                input.essentialFloor(), input.terminalBalanceTarget(),
                input.returnMean(), input.returnStddev(),
                input.trialCount(), input.confidenceLevel(),
                input.phases(), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
    }
}
