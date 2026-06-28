package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.wealthview.core.common.CompoundGrowth;
import com.wealthview.core.projection.SpendingOptimizer;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;


// GodClass / CyclomaticComplexity: the Monte Carlo spending+conversion optimizer is a cohesive
// numerical pipeline (context prep, grid search, smoothing, response assembly). Decomposition is
// in progress: the terminal simulation + response assembly was extracted to GuardrailResponseBuilder,
// dropping class-level cyclomatic complexity from 119 to 90. It still exceeds the threshold; the
// remaining over-budget pieces (the joint conversion search and the tax/income context builders)
// are the next extractions. Suppressed until that work lands rather than masking the whole class.
@SuppressWarnings({"PMD.GodClass", "PMD.CyclomaticComplexity"})
@Component
public class MonteCarloSpendingOptimizer implements SpendingOptimizer {

    private static final Logger log = LoggerFactory.getLogger(MonteCarloSpendingOptimizer.class);
    private static final int JOINT_GRID_SIZE = 20;
    private static final double JOINT_REFINE_HALF_WIDTH = 0.1;
    private static final int JOINT_REFINE_ITERATIONS = 10;
    private static final int JOINT_SEARCH_TRIALS = 500;
    /** Reduction factor applied per iteration when a smoothed plan fails sustainability. */
    private static final double SUSTAINABILITY_REDUCTION_FACTOR = 0.95;

    private final FederalTaxCalculator taxCalculator;
    @Nullable
    private final MeterRegistry meterRegistry;
    private final TrialSimulator trialSimulator = new TrialSimulator();
    private final SustainabilitySearch sustainabilitySearch = new SustainabilitySearch(trialSimulator);
    private final GuardrailResponseBuilder responseBuilder = new GuardrailResponseBuilder(trialSimulator);

    /** Test-friendly constructor that omits the optional meter registry. */
    public MonteCarloSpendingOptimizer(@Nullable FederalTaxCalculator taxCalculator) {
        this(taxCalculator, null);
    }

    @Autowired
    public MonteCarloSpendingOptimizer(@Nullable FederalTaxCalculator taxCalculator,
                                        @Nullable MeterRegistry meterRegistry) {
        this.taxCalculator = taxCalculator;
        this.meterRegistry = meterRegistry;
    }

    private record ConversionResult(
            double[] byYear, double[] taxByYear,
            RothConversionOptimizer.RothConversionSchedule schedule
    ) {}

    /** Pre-computed per-year income and tax arrays for the optimization run. */
    private record IncomeArrays(double[] incomeByYear, double[] taxableIncomeByYear,
                                double[] surplusTaxByYear) {}

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
            var ctx = prepareContext(input);
            if (ctx.sim().years() <= 0) {
                return emptyResult(input);
            }

            var conv = optimizeConversions(ctx, input);
            var discretionaryByYear = allocateAndSmooth(ctx, input, conv.byYear(), conv.taxByYear());
            return responseBuilder.build(ctx, input, discretionaryByYear, conv.byYear(), conv.taxByYear(),
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
        double[][] portfolioPaths = PortfolioPathGenerator.generatePaths(
                trialCount, years, initialPortfolio, historicalReturns, rng, inflationRate);

        // Compute deterministic income for each year
        IncomeYearData[] incomeData = computeDeterministicIncome(
                input.incomeSources(), retirementAge, years);
        FilingStatus filingStatus = input.filingStatus() != null
                ? FilingStatus.fromString(input.filingStatus()) : FilingStatus.SINGLE;

        var incomeArrays = computeIncomeArrays(incomeData, years, retirementYear, filingStatus);

        // Compute rental-aware taxable income for marginal rate pre-computation.
        // This adjusts the base taxable income with rental property depreciation,
        // passive loss rules, and carryforward so that MC trial withdrawal tax
        // estimates reflect actual bracket positions.
        double[] rentalAwareTaxableIncome = computeRentalAwareTaxableIncome(
                incomeArrays.taxableIncomeByYear(), input.incomeSources(),
                retirementAge, input.birthYear(), years);

        // Verify essential floor feasibility (inflation-adjusted)
        double[] adjustedFloors = SustainabilitySearch.verifyEssentialFloor(
                portfolioPaths, incomeArrays.incomeByYear(), essentialFloor,
                confidenceLevel, years, trialCount, inflationRate);

        double[] marginalRates = precomputeMarginalRates(
                rentalAwareTaxableIncome, retirementYear, years, filingStatus);
        TaxContext taxCtx = (initTraditional > 0 || initRoth > 0)
                ? new TaxContext(initTaxable, initTraditional, initRoth,
                        withdrawalOrder, marginalRates)
                : null;

        double[] dsBracketCeilingByYear = computeDsBracketCeilings(
                withdrawalOrder, input.dynamicSequencingBracketRate(),
                years, retirementYear, filingStatus, input.inflationRate());

        double portfolioFloor = input.portfolioFloor() != null
                ? input.portfolioFloor().doubleValue() : 0.0;

        return new OptimizationSetup(
                new PortfolioSetup(initTaxable, initTraditional, initRoth,
                        initialPortfolio, withdrawalOrder, cashReserveYears, cashReturnRate,
                        terminalTarget, portfolioFloor),
                new SimulationParameters(retirementYear, retirementAge, endAge, years,
                        trialCount, confidenceLevel, inflationRate, portfolioPaths),
                new TaxIncomeContext(filingStatus, essentialFloor,
                        incomeArrays.incomeByYear(), incomeArrays.taxableIncomeByYear(),
                        incomeArrays.surplusTaxByYear(),
                        incomeData, rentalAwareTaxableIncome, adjustedFloors, marginalRates,
                        taxCtx, dsBracketCeilingByYear));
    }

    private IncomeArrays computeIncomeArrays(IncomeYearData[] incomeData, int years,
                                              int retirementYear, FilingStatus filingStatus) {
        double[] incomeByYear = new double[years];
        double[] taxableIncomeByYear = new double[years];
        double[] surplusTaxByYear = new double[years];
        for (int y = 0; y < years; y++) {
            incomeByYear[y] = incomeData[y].totalIncome();
            taxableIncomeByYear[y] = incomeData[y].taxableIncome();
            surplusTaxByYear[y] = computeSurplusTax(
                    incomeData[y].taxableIncome(), retirementYear + y, filingStatus);
        }
        return new IncomeArrays(incomeByYear, taxableIncomeByYear, surplusTaxByYear);
    }

    // ReturnEmptyCollectionRatherThanNull: the return type is a primitive double[] sentinel, not a
    // Collection — null signals "no dynamic-sequencing ceilings apply" and callers null-check it.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private double[] computeDsBracketCeilings(String withdrawalOrder,
                                               BigDecimal dynamicSequencingBracketRate,
                                               int years, int retirementYear,
                                               FilingStatus filingStatus,
                                               BigDecimal inflationRate) {
        if (!PoolStrategy.WITHDRAWAL_ORDER_DYNAMIC_SEQUENCING.equals(withdrawalOrder)
                || dynamicSequencingBracketRate == null
                || taxCalculator == null) {
            return null;
        }
        double[] ceilings = new double[years];
        for (int y = 0; y < years; y++) {
            ceilings[y] = taxCalculator.computeMaxIncomeForBracket(
                    dynamicSequencingBracketRate, retirementYear + y, filingStatus,
                    inflationRate).doubleValue();
        }
        return ceilings;
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
        double[][] searchPaths = PortfolioPathGenerator.generatePaths(
                searchTrials, ctx.sim().years(), ctx.portfolio().initialPortfolio(), historicalReturns,
                searchRng, ctx.sim().inflationRate());

        double[] searchFloors = SustainabilitySearch.verifyEssentialFloor(
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
     * call sites in {@link #jointSearchConversions} only vary by
     * {@code conversionByYear} / {@code conversionTaxByYear}.
     */
    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private double evalSearchSpending(double[][] searchPaths, OptimizationSetup ctx,
                                       double[] searchFloors, int searchTrials, TaxContext searchTaxCtx,
                                       double[] conversionByYear, double[] conversionTaxByYear) {
        var searchContext = new SustainabilitySearch.SearchContext(
                searchPaths, ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(),
                ctx.portfolio().terminalTarget(), ctx.sim().retirementAge(), ctx.sim().years(),
                searchTrials, ctx.sim().confidenceLevel(), ctx.portfolio().portfolioFloor(),
                ctx.portfolio().cashReserveYears(), ctx.portfolio().cashReturnRate(),
                ctx.sim().inflationRate(), searchTaxCtx, conversionByYear, conversionTaxByYear,
                ctx.taxIncome().dsBracketCeilingByYear());
        return sustainabilitySearch.evaluateSustainableSpending(searchContext, searchFloors);
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
            if (!sustainabilitySearch.isSustainable(searchContext, ctx.taxIncome().adjustedFloors(),
                    discretionaryByYear)) {
                for (int i = 0; i < 10; i++) {
                    for (int y = 0; y < ctx.sim().years(); y++) {
                        discretionaryByYear[y] *= SUSTAINABILITY_REDUCTION_FACTOR;
                    }
                    if (sustainabilitySearch.isSustainable(searchContext,
                            ctx.taxIncome().adjustedFloors(), discretionaryByYear)) {
                        break;
                    }
                }
            }
        }

        return discretionaryByYear;
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
                conversionByYear, conversionTaxByYear, ctx.taxIncome().dsBracketCeilingByYear());
    }

    private IncomeYearData[] computeDeterministicIncome(List<ProjectionIncomeSourceInput> sources,
                                                         int retirementAge, int years) {
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
                if (!ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                    continue;
                }
                double gross = source.annualAmount().doubleValue();

                if (source.inflationRate() != null
                        && source.inflationRate().compareTo(BigDecimal.ZERO) > 0) {
                    gross *= CompoundGrowth.factor(source.inflationRate().doubleValue(), yearsInRetirement - 1);
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

    private static double sumByType(List<? extends ProjectionAccountInput> accounts, String type) {
        return accounts.stream()
                .filter(a -> type.equals(a.accountType()))
                .mapToDouble(a -> a.initialBalance().doubleValue())
                .sum();
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

    /**
     * Pre-computes the marginal tax rate for traditional withdrawals at each
     * retirement year. Uses a representative withdrawal amount ($50K) to find
     * the marginal rate at the income level of (taxableIncome + $50K).
     */
    private double[] precomputeMarginalRates(double[] taxableIncomeByYear,
                                               int retirementYear, int years,
                                               FilingStatus filingStatus) {
        double[] rates = new double[years];
        if (taxCalculator == null) {
            return rates;
        }

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
                if (!ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                    continue;
                }
                var rentalResult = RentalIncomeHelper.computeForSource(
                        source, y, calendarYear, baseOtherIncome,
                        suspendedBySource.get(source), calculator);
                suspendedBySource.put(source, rentalResult.newSuspendedLoss());
                yearAdjustment += rentalResult.netTaxableIncome();
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
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
    }
}
