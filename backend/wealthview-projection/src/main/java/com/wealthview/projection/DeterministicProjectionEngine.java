package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
import com.wealthview.core.projection.ProjectionEngine;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionPropertyInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.SpendingPlan;
import com.wealthview.core.projection.strategy.WithdrawalStrategy;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import com.wealthview.core.projection.tax.SelfEmploymentTaxCalculator;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;
import com.wealthview.core.projection.tax.StateTaxCalculatorFactory;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;

/**
 * Deterministic year-by-year retirement projection engine. It orchestrates a set of focused
 * collaborators — parameter parsing, pool strategy, income-source and contribution processing,
 * retirement withdrawals, tax annotation, feasibility analysis — and delegates self-contained
 * concerns to {@link WithdrawalStrategyFactory}, {@link PropertyEquityCalculator}, and
 * {@link IncomeSourceFieldMapper}.
 */
@Component
public class DeterministicProjectionEngine implements ProjectionEngine {

    private static final Logger log = LoggerFactory.getLogger(DeterministicProjectionEngine.class);
    private static final BigDecimal DEFAULT_WITHDRAWAL_RATE = new BigDecimal("0.04");

    /**
     * Fallback inflation assumption used to convert a user's NOMINAL expected-return override into a
     * REAL return via {@code (1+nominal)/(1+inflationRate)-1} when the scenario itself has no
     * inflation rate. The whole projection runs in real (today's-dollars) terms, and overrides must be
     * deflated at the SAME rate as income/Social Security/spending — the scenario's own inflation rate
     * — so that all cash flows share one real-dollar frame. {@link #resolveProjectionParams} already
     * defaults a missing scenario rate to this same value, so in practice this constant is only ever
     * used as that upstream default flowing through, never as an independent override (allocation
     * accounts already grow at real geometric means and are unaffected by this constant either way).
     */
    private static final BigDecimal CMA_INFLATION_RATE = new BigDecimal("0.025");

    private final ScenarioParamsParser paramsParser = new ScenarioParamsParser();
    private final SpendingFeasibilityAnalyzer feasibilityAnalyzer = new SpendingFeasibilityAnalyzer();
    private final RetirementWithdrawalProcessor retirementWithdrawalProcessor = new RetirementWithdrawalProcessor();
    private final RetirementTaxAnnotator retirementTaxAnnotator = new RetirementTaxAnnotator();
    private final TaxStrategyFactory taxStrategyFactory;
    private final IncomeSourceProcessor incomeSourceProcessor;
    private final IncomeContributionCalculator incomeContributionCalculator;
    /**
     * Retained separately from {@link #taxStrategyFactory} (which wraps the same instance inside a
     * {@code TaxCalculationStrategy}) so {@link #buildPoolStrategy} can thread the raw standard-
     * deduction source into {@code PoolConfig} for the LTCG stacking-floor fix -- MultiPool needs the
     * federal standard deduction even when isolating LTCG computation from the ordinary tax strategy.
     */
    @Nullable
    private final FederalTaxCalculator federalTaxCalculator;
    @Nullable
    private final CapitalGainsTaxCalculator capitalGainsTaxCalculator;
    @Nullable
    private final MeterRegistry meterRegistry;
    @Nullable
    private final CapitalMarketAssumptionsProvider capitalMarketAssumptions;

    /** Test-friendly constructor that omits capital-gains taxation, the meter registry and CMA. */
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory) {
        this(taxCalculator, stateTaxCalculatorFactory, null, null, null);
    }

    /** Test-friendly constructor that wires capital-gains taxation but omits meter registry and CMA. */
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory,
                                          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator) {
        this(taxCalculator, stateTaxCalculatorFactory, capitalGainsTaxCalculator, null, null);
    }

    @Autowired
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory,
                                          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                          @Nullable MeterRegistry meterRegistry,
                                          @Nullable CapitalMarketAssumptionsProvider capitalMarketAssumptions) {
        this.taxStrategyFactory = new TaxStrategyFactory(taxCalculator, stateTaxCalculatorFactory);
        this.federalTaxCalculator = taxCalculator;
        this.capitalGainsTaxCalculator = capitalGainsTaxCalculator;
        this.meterRegistry = meterRegistry;
        this.capitalMarketAssumptions = capitalMarketAssumptions;
        var rentalLossCalculator = new RentalLossCalculator();
        var ssTaxCalculator = new SocialSecurityTaxCalculator();
        var seTaxCalculator = new SelfEmploymentTaxCalculator();
        this.incomeSourceProcessor = new IncomeSourceProcessor(
                rentalLossCalculator, ssTaxCalculator, seTaxCalculator);
        this.incomeContributionCalculator = new IncomeContributionCalculator();
    }

    @Timed("wealthview.projection.run")
    @Observed(name = "wealthview.projection.run",
              contextualName = "deterministic-projection",
              lowCardinalityKeyValues = {"component", "projection"})
    @Override
    public ProjectionResultResponse run(ProjectionInput input) {
        MDC.put("operation", "projection");
        MDC.put("scenarioName", input.scenarioName() != null ? input.scenarioName() : "unnamed");
        try {
            var result = runInternal(input);
            if (meterRegistry != null) {
                meterRegistry.counter("wealthview.projection.runs", "type", "deterministic").increment();
            }
            return result;
        } finally {
            MDC.remove("operation");
            MDC.remove("scenarioName");
        }
    }

    private record ResolvedParams(
            int currentYear, int birthYear, int retirementYear, int endYear,
            BigDecimal withdrawalRate, BigDecimal inflationRate,
            WithdrawalStrategy strategy, SpendingPlan spendingPlan,
            List<ProjectionIncomeSourceInput> incomeSources,
            List<ProjectionPropertyInput> properties) {
    }

    /** Bundles all immutable per-run inputs so {@code runProjection} has a single context param. */
    private record ProjectionRunContext(
            ProjectionInput input,
            PoolStrategy pool,
            WithdrawalStrategy strategy,
            int currentYear,
            int birthYear,
            int retirementYear,
            int endYear,
            BigDecimal inflationRate,
            SpendingPlan spendingPlan,
            List<ProjectionIncomeSourceInput> incomeSources,
            List<ProjectionPropertyInput> properties,
            TaxCalculationStrategy taxStrategy) {
    }

    private ProjectionResultResponse runInternal(ProjectionInput input) {
        var accounts = input.accounts();
        var params = paramsParser.parseParams(input.paramsJson());

        log.info("Starting projection for scenario '{}': {} accounts, retirement year {}, end age {}",
                input.scenarioName(), accounts.size(),
                input.retirementDate() != null ? input.retirementDate().getYear() : "default",
                input.endAge() != null ? input.endAge() : 90);

        var resolved = resolveProjectionParams(input, params);
        var taxStrategy = taxStrategyFactory.buildTaxStrategy(params);
        var pool = buildPoolStrategy(accounts, params, taxStrategy, resolved.inflationRate(),
                resolved.currentYear());

        var ctx = new ProjectionRunContext(input, pool, resolved.strategy(),
                resolved.currentYear(), resolved.birthYear(), resolved.retirementYear(), resolved.endYear(),
                resolved.inflationRate(), resolved.spendingPlan(), resolved.incomeSources(),
                resolved.properties(), taxStrategy);
        return runProjection(ctx);
    }

    // NPathComplexity: this method resolves many independent parameters, each via a small
    // null-coalescing default (`x != null ? x : default`). The path count is multiplicative
    // across those defaults but every branch is trivial straight-line resolution.
    @SuppressWarnings("PMD.NPathComplexity")
    private ResolvedParams resolveProjectionParams(ProjectionInput input, ScenarioParams params) {
        int currentYear = input.referenceYear() != null ? input.referenceYear() : LocalDate.now().getYear();
        int birthYear = params.birthYear() != null ? params.birthYear() : currentYear - 35;
        int retirementYear = input.retirementDate() != null
                ? input.retirementDate().getYear()
                : currentYear + 30;
        int endAge = input.endAge() != null ? input.endAge() : 90;
        int endYear = birthYear + endAge;

        BigDecimal withdrawalRate = params.withdrawalRate() != null
                ? params.withdrawalRate()
                : DEFAULT_WITHDRAWAL_RATE;
        BigDecimal inflationRate = input.inflationRate() != null
                ? input.inflationRate()
                : new BigDecimal("0.025");

        WithdrawalStrategy strategy = WithdrawalStrategyFactory.create(params, withdrawalRate);
        SpendingPlan spendingPlan = null;
        if (input.guardrailSpending() != null) {
            spendingPlan = input.guardrailSpending();
        } else if (input.spendingProfile() != null) {
            spendingPlan = paramsParser.parseTierBasedPlan(input.spendingProfile());
        }

        var incomeSources = input.incomeSources() != null
                ? input.incomeSources() : List.<ProjectionIncomeSourceInput>of();
        var properties = input.properties() != null ? input.properties() : List.<ProjectionPropertyInput>of();

        return new ResolvedParams(currentYear, birthYear, retirementYear, endYear,
                withdrawalRate, inflationRate, strategy, spendingPlan, incomeSources, properties);
    }

    private PoolStrategy buildPoolStrategy(List<ProjectionAccountInput> accounts,
                                              ScenarioParams params,
                                              TaxCalculationStrategy taxStrategy,
                                              BigDecimal scenarioInflationRate,
                                              int baseYear) {
        Map<AssetClass, Double> geoMeans = capitalMarketAssumptions != null
                ? capitalMarketAssumptions.geometricMeans()
                : Map.of();
        // Override accounts must deflate at the SAME inflation rate income/SS/spending use — the
        // scenario's own rate — not a fixed CMA constant, or a nominal-override account would compound
        // in a different real-dollar frame than the rest of the projection. Fall back to the CMA
        // constant only if the scenario itself has no rate (resolveProjectionParams already defaults
        // it, so this fallback is effectively unreachable in practice).
        BigDecimal inflationRate = scenarioInflationRate != null ? scenarioInflationRate : CMA_INFLATION_RATE;
        var config = new PoolStrategy.PoolConfig(
                params.filingStatus() != null ? FilingStatus.fromString(params.filingStatus()) : FilingStatus.SINGLE,
                params.otherIncome() != null ? params.otherIncome() : BigDecimal.ZERO,
                params.annualRothConversion() != null ? params.annualRothConversion() : BigDecimal.ZERO,
                params.rothConversionStrategy(), params.targetBracketRate(),
                params.rothConversionStartYear(), params.resolvedWithdrawalOrder(), taxStrategy,
                params.dynamicSequencingBracketRate(),
                geoMeans, inflationRate,
                capitalGainsTaxCalculator, paramsParser.dividendYield(params), paramsParser.feeRate(params),
                baseYear, federalTaxCalculator);
        return PoolStrategy.create(accounts, config);
    }

    /** Carry-forward state threaded between successive year iterations. */
    private record YearAccumulator(int yearsInRetirement, BigDecimal previousWithdrawal, BigDecimal suspendedLoss) {
        static final YearAccumulator INITIAL = new YearAccumulator(0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private record YearStepResult(ProjectionYearDto yearDto, YearAccumulator nextAccumulator) {}

    private ProjectionResultResponse runProjection(ProjectionRunContext ctx) {
        var yearlyData = new ArrayList<ProjectionYearDto>();
        var acc = YearAccumulator.INITIAL;

        for (int year = ctx.currentYear(); year < ctx.endYear(); year++) {
            var step = processYear(ctx, year, acc);
            yearlyData.add(step.yearDto());
            acc = step.nextAccumulator();
        }

        BigDecimal finalBalance = yearlyData.isEmpty()
                ? ctx.pool().getTotal()
                : yearlyData.getLast().endBalance();

        log.info("{} for scenario '{}': {} years, final balance {}",
                ctx.pool().logTag(), ctx.input().scenarioName(), yearlyData.size(), finalBalance);

        var feasibility = feasibilityAnalyzer.computeFeasibility(yearlyData, ctx.spendingPlan());
        BigDecimal finalNetWorth = yearlyData.isEmpty() ? null : yearlyData.getLast().totalNetWorth();
        return new ProjectionResultResponse(ctx.input().scenarioId(), yearlyData, finalBalance,
                acc.yearsInRetirement(), feasibility, finalNetWorth);
    }

    private YearStepResult processYear(ProjectionRunContext ctx, int year, YearAccumulator acc) {
        var pool = ctx.pool();
        int age = year - ctx.birthYear();
        boolean retired = year >= ctx.retirementYear();
        BigDecimal startBalance = pool.getTotal();

        BigDecimal contributions = BigDecimal.ZERO;
        int yearsInRetirement = retired ? acc.yearsInRetirement() + 1 : acc.yearsInRetirement();
        if (!retired) {
            contributions = pool.applyContributions();
        }

        // Snapshot BEFORE applyGrowth(): the RMD for this year is computed off the prior year-end
        // traditional balance (IRS Pub. 590-B), not this year's growth.
        BigDecimal priorYearEndTraditional = pool.getTraditional();
        var growthResult = pool.applyGrowth();
        BigDecimal totalGrowth = growthResult.total();

        BigDecimal rmdAmount = BigDecimal.ZERO;
        if (retired && age >= RmdCalculator.rmdStartAge(ctx.birthYear())) {
            double divisor = RmdCalculator.distributionPeriod(age);
            if (divisor > 0) {
                rmdAmount = priorYearEndTraditional.divide(BigDecimal.valueOf(divisor), 4, RoundingMode.HALF_UP);
            }
        }

        var comp = resolveYearFinances(ctx, year, age, retired, yearsInRetirement, startBalance, acc, rmdAmount);

        BigDecimal suspendedLoss = comp.suspendedLoss();
        BigDecimal conversionAmount = comp.conversionAmount();
        BigDecimal taxLiability = comp.taxLiability();
        BigDecimal withdrawals = comp.withdrawals();
        BigDecimal surplusReinvested = comp.surplusReinvested();
        BigDecimal wdFromTaxable = comp.wdFromTaxable();
        BigDecimal wdFromTraditional = comp.wdFromTraditional();
        BigDecimal wdFromRoth = comp.wdFromRoth();
        BigDecimal previousWithdrawal = comp.previousWithdrawal();
        BigDecimal ltcgTax = comp.ltcgTax();
        var combinedTaxSource = comp.combinedTaxSource();

        pool.floorAtZero();

        int yearsElapsed = year - ctx.currentYear();
        BigDecimal propertyEquity = PropertyEquityCalculator.compute(ctx.properties(), yearsElapsed);

        var yearDto = pool.buildYearDto(new PoolStrategy.YearDtoContext(
                year, age, startBalance, contributions,
                totalGrowth, withdrawals, retired, conversionAmount, taxLiability,
                growthResult, wdFromTaxable, wdFromTraditional, wdFromRoth, combinedTaxSource,
                rmdAmount, ltcgTax));
        yearDto = PropertyEquityCalculator.apply(yearDto, propertyEquity);
        yearDto = feasibilityAnalyzer.applyViability(yearDto, ctx.spendingPlan(), year, age, yearsInRetirement,
                ctx.inflationRate(), comp.totalActiveIncome());
        yearDto = IncomeSourceFieldMapper.apply(yearDto, comp.isResult());
        yearDto = yearDto.withSurplusReinvested(surplusReinvested);
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(retired, age, year,
                wdFromTraditional, conversionAmount, comp.effectiveOtherIncome(),
                taxLiability, pool, ctx.taxStrategy(), ltcgTax);
        yearDto = retirementTaxAnnotator.annotate(yearDto, annCtx);

        return new YearStepResult(yearDto, new YearAccumulator(yearsInRetirement, previousWithdrawal, suspendedLoss));
    }

    /** Maximum fixed-point passes over the year's Social Security provisional-income convergence. */
    private static final int MAX_SS_CONVERGENCE_ITERATIONS = 4;
    /** Convergence tolerance (dollars) for the Social Security provisional-income fixed point. */
    private static final BigDecimal SS_CONVERGENCE_TOLERANCE = BigDecimal.ONE;

    /**
     * Resolves the year's income, Roth conversion, and retirement withdrawal — the pool-mutating
     * core of a projection year — with the audit-B2 Social Security provisional-income convergence.
     *
     * <p>Taxable Social Security depends on the year's realized ORDINARY income (traditional
     * withdrawals + RMD excess + Roth conversion + realized LTCG/dividends), but those are only known
     * AFTER the conversion/withdrawal steps run, and they in turn can depend (under bracket-fill
     * conversion and dynamic-sequencing withdrawal) on how much Social Security is taxable. The loop
     * resolves this circular dependency as a fixed point: it snapshots the post-growth pool, runs the
     * year with an estimate of the realized ordinary income folded into the Social Security
     * provisional base, then re-runs from the snapshot with the actual realized figure until the two
     * agree within {@link #SS_CONVERGENCE_TOLERANCE} or {@link #MAX_SS_CONVERGENCE_ITERATIONS} passes.
     * Taxable Social Security is monotone piecewise-linear in ordinary income and capped at 85%, and
     * the withdrawal-feedback map is a contraction (|slope| ≤ 0.85), so it converges quickly; in the
     * common ordered-withdrawal case the realized ordinary income is independent of Social Security
     * taxability, giving the exact fixed point in a single re-run.
     *
     * <p>When no Social Security source is active this year the loop is skipped entirely, so the
     * behavior is byte-identical to a single pass with zero extra provisional income.
     */
    private YearComputation resolveYearFinances(ProjectionRunContext ctx, int year, int age, boolean retired,
                                                int yearsInRetirement, BigDecimal startBalance,
                                                YearAccumulator acc, BigDecimal rmdAmount) {
        var pool = ctx.pool();
        boolean converge = hasActiveSocialSecurity(ctx.incomeSources(), age);
        PoolStrategy.Memento snapshot = converge ? pool.snapshot() : null;

        BigDecimal additionalProvisional = BigDecimal.ZERO;
        var comp = computeIncomeConversionWithdrawal(
                ctx, year, age, retired, yearsInRetirement, startBalance, acc, rmdAmount, additionalProvisional);

        if (converge) {
            int iterations = 1;
            while (comp.realizedPortfolioTaxable().subtract(additionalProvisional).abs()
                            .compareTo(SS_CONVERGENCE_TOLERANCE) >= 0
                    && iterations < MAX_SS_CONVERGENCE_ITERATIONS) {
                iterations++;
                additionalProvisional = comp.realizedPortfolioTaxable();
                pool.restore(snapshot);
                comp = computeIncomeConversionWithdrawal(
                        ctx, year, age, retired, yearsInRetirement, startBalance, acc, rmdAmount,
                        additionalProvisional);
            }
        }
        return comp;
    }

    private boolean hasActiveSocialSecurity(List<ProjectionIncomeSourceInput> incomeSources, int age) {
        for (var source : incomeSources) {
            if (source.incomeType() == IncomeSourceType.SOCIAL_SECURITY
                    && ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                return true;
            }
        }
        return false;
    }

    /** Everything a resolved projection year yields, plus the realized ordinary income for convergence. */
    private record YearComputation(
            IncomeSourceProcessor.IncomeSourceYearResult isResult,
            BigDecimal totalActiveIncome,
            BigDecimal effectiveOtherIncome,
            BigDecimal conversionAmount,
            BigDecimal taxLiability,
            BigDecimal suspendedLoss,
            BigDecimal withdrawals,
            BigDecimal previousWithdrawal,
            BigDecimal surplusReinvested,
            BigDecimal wdFromTaxable,
            BigDecimal wdFromTraditional,
            BigDecimal wdFromRoth,
            BigDecimal ltcgTax,
            PoolStrategy.TaxSourceResult combinedTaxSource,
            BigDecimal realizedPortfolioTaxable) {
    }

    private YearComputation computeIncomeConversionWithdrawal(
            ProjectionRunContext ctx, int year, int age, boolean retired, int yearsInRetirement,
            BigDecimal startBalance, YearAccumulator acc, BigDecimal rmdAmount,
            BigDecimal additionalProvisionalIncome) {
        var pool = ctx.pool();
        var incomeResult = processIncomeAndConversions(
                pool, ctx.incomeSources(), age, yearsInRetirement, year, acc.suspendedLoss(),
                resolveConversionOverride(ctx.spendingPlan(), year), ctx.inflationRate(), ctx.currentYear(),
                rmdAmount, additionalProvisionalIncome);
        BigDecimal suspendedLoss = incomeResult.suspendedLoss();
        BigDecimal conversionAmount = incomeResult.conversionAmount();
        BigDecimal taxLiability = incomeResult.taxLiability();

        BigDecimal withdrawals = BigDecimal.ZERO;
        BigDecimal surplusReinvested = null;
        BigDecimal wdFromTaxable = BigDecimal.ZERO;
        BigDecimal wdFromTraditional = BigDecimal.ZERO;
        BigDecimal wdFromRoth = BigDecimal.ZERO;
        BigDecimal previousWithdrawal = acc.previousWithdrawal();
        PoolStrategy.TaxSourceResult withdrawalTaxSource = PoolStrategy.TaxSourceResult.ZERO;
        BigDecimal ltcgTax = BigDecimal.ZERO;
        BigDecimal realizedLtcgIncome = BigDecimal.ZERO;
        if (retired) {
            var rwCtx = new RetirementWithdrawalProcessor.RetirementWithdrawalContext(
                    pool, ctx.strategy(), ctx.spendingPlan(), age, yearsInRetirement, year,
                    ctx.inflationRate(), incomeResult.totalActiveIncome(), startBalance,
                    previousWithdrawal, incomeResult.effectiveOtherIncome(), conversionAmount,
                    incomeResult.isResult(), ctx.taxStrategy(), rmdAmount);
            var retirementResult = retirementWithdrawalProcessor.process(rwCtx);
            withdrawals = retirementResult.withdrawals();
            taxLiability = taxLiability.add(retirementResult.taxLiability());
            previousWithdrawal = retirementResult.previousWithdrawal();
            surplusReinvested = retirementResult.surplusReinvested();
            wdFromTaxable = retirementResult.withdrawalFromTaxable();
            wdFromTraditional = retirementResult.withdrawalFromTraditional();
            wdFromRoth = retirementResult.withdrawalFromRoth();
            withdrawalTaxSource = retirementResult.withdrawalTaxSource();
            ltcgTax = retirementResult.ltcgTax();
            realizedLtcgIncome = retirementResult.realizedLtcgIncome();
        }

        var combinedTaxSource = incomeResult.conversionTaxSource().add(withdrawalTaxSource);

        // Realized ordinary income for the Social Security provisional-income fixed point: taxable
        // traditional distributions (spend draw + RMD force-out) + Roth conversion + realized
        // LTCG/dividend income. Matches the IRS worksheet's AGI-ex-SS additions.
        BigDecimal realizedPortfolioTaxable = wdFromTraditional.add(conversionAmount).add(realizedLtcgIncome);

        return new YearComputation(incomeResult.isResult(), incomeResult.totalActiveIncome(),
                incomeResult.effectiveOtherIncome(), conversionAmount, taxLiability, suspendedLoss,
                withdrawals, previousWithdrawal, surplusReinvested, wdFromTaxable, wdFromTraditional,
                wdFromRoth, ltcgTax, combinedTaxSource, realizedPortfolioTaxable);
    }

    private BigDecimal resolveConversionOverride(SpendingPlan spendingPlan, int year) {
        if (spendingPlan == null) {
            return null;
        }
        return spendingPlan.conversionSchedule()
                .map(schedule -> schedule.getOrDefault(year, BigDecimal.ZERO))
                .orElse(null);
    }

    private record IncomeAndConversionResult(
            IncomeSourceProcessor.IncomeSourceYearResult isResult,
            BigDecimal totalActiveIncome,
            BigDecimal effectiveOtherIncome,
            BigDecimal conversionAmount,
            BigDecimal taxLiability,
            BigDecimal suspendedLoss,
            PoolStrategy.TaxSourceResult conversionTaxSource) {
    }

    private IncomeAndConversionResult processIncomeAndConversions(
            PoolStrategy pool, List<ProjectionIncomeSourceInput> incomeSources,
            int age, int yearsInRetirement, int year, BigDecimal suspendedLoss,
            BigDecimal conversionOverride, BigDecimal inflationRate, int baseYear, BigDecimal rmdAmount,
            BigDecimal additionalProvisionalIncome) {

        IncomeSourceProcessor.IncomeSourceYearResult incomeSourceResult = null;
        BigDecimal totalActiveIncome;
        BigDecimal taxableActiveIncome;

        if (pool.processIncomeSourcesEveryYear() || yearsInRetirement > 0) {
            incomeSourceResult = incomeSourceProcessor.process(incomeSources, age, yearsInRetirement,
                    year, pool.getMagi(), pool.getFilingStatus(), suspendedLoss, inflationRate, baseYear,
                    additionalProvisionalIncome);
            suspendedLoss = incomeSourceResult.suspendedLossCarryforward();
            totalActiveIncome = incomeSourceResult.totalCashInflow();
            taxableActiveIncome = incomeSourceResult.totalTaxableIncome();
        } else {
            totalActiveIncome = incomeContributionCalculator.compute(
                    incomeSources, age, yearsInRetirement, inflationRate);
            taxableActiveIncome = totalActiveIncome;
        }

        BigDecimal effectiveOtherIncome = pool.computeEffectiveOtherIncome(taxableActiveIncome, BigDecimal.ZERO);

        PoolStrategy.ConversionResult conversion;
        if (conversionOverride != null && conversionOverride.compareTo(BigDecimal.ZERO) > 0) {
            conversion = pool.executeRothConversionOverride(
                    year, effectiveOtherIncome, conversionOverride, rmdAmount);
        } else if (conversionOverride != null) {
            // Override is present but zero → no conversion this year
            conversion = new PoolStrategy.ConversionResult(
                    BigDecimal.ZERO, BigDecimal.ZERO, PoolStrategy.TaxSourceResult.ZERO);
        } else {
            conversion = pool.executeRothConversion(year, effectiveOtherIncome, rmdAmount);
        }

        return new IncomeAndConversionResult(incomeSourceResult, totalActiveIncome, effectiveOtherIncome,
                conversion.amountConverted(), conversion.taxLiability(), suspendedLoss, conversion.taxSource());
    }

}
