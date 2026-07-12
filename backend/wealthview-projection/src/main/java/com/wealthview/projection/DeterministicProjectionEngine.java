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
import com.wealthview.core.projection.tax.IrmaaSurchargeCalculator;
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
    /** Medicare (and thus IRMAA) eligibility age -- Wave-4 IRMAA item. */
    private static final int MEDICARE_AGE = 65;

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
    private final RetirementTaxAnnotator retirementTaxAnnotator = new RetirementTaxAnnotator();
    private final TaxStrategyFactory taxStrategyFactory;
    private final YearFinanceResolver yearFinanceResolver;
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
    /** Wave-4 IRMAA item: null omits the surcharge entirely (irmaaSurcharge stays zero every year). */
    @Nullable
    private final IrmaaSurchargeCalculator irmaaSurchargeCalculator;
    @Nullable
    private final MeterRegistry meterRegistry;
    @Nullable
    private final CapitalMarketAssumptionsProvider capitalMarketAssumptions;

    /**
     * Test-friendly constructor that omits capital-gains taxation, IRMAA, the meter registry
     * and CMA.
     */
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory) {
        this(taxCalculator, stateTaxCalculatorFactory, null, null, null, null);
    }

    /**
     * Test-friendly constructor that wires capital-gains taxation but omits IRMAA, the meter
     * registry and CMA.
     */
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory,
                                          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator) {
        this(taxCalculator, stateTaxCalculatorFactory, capitalGainsTaxCalculator, null, null, null);
    }

    /**
     * Test-friendly constructor that wires capital-gains taxation and IRMAA but omits the meter
     * registry and CMA.
     */
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory,
                                          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                          @Nullable IrmaaSurchargeCalculator irmaaSurchargeCalculator) {
        this(taxCalculator, stateTaxCalculatorFactory, capitalGainsTaxCalculator, irmaaSurchargeCalculator,
                null, null);
    }

    @Autowired
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory,
                                          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                          @Nullable IrmaaSurchargeCalculator irmaaSurchargeCalculator,
                                          @Nullable MeterRegistry meterRegistry,
                                          @Nullable CapitalMarketAssumptionsProvider capitalMarketAssumptions) {
        this.taxStrategyFactory = new TaxStrategyFactory(taxCalculator, stateTaxCalculatorFactory);
        this.federalTaxCalculator = taxCalculator;
        this.capitalGainsTaxCalculator = capitalGainsTaxCalculator;
        this.irmaaSurchargeCalculator = irmaaSurchargeCalculator;
        this.meterRegistry = meterRegistry;
        this.capitalMarketAssumptions = capitalMarketAssumptions;
        var rentalLossCalculator = new RentalLossCalculator();
        var ssTaxCalculator = new SocialSecurityTaxCalculator();
        var seTaxCalculator = new SelfEmploymentTaxCalculator();
        var incomeSourceProcessor = new IncomeSourceProcessor(
                rentalLossCalculator, ssTaxCalculator, seTaxCalculator);
        this.yearFinanceResolver = new YearFinanceResolver(
                incomeSourceProcessor, new IncomeContributionCalculator(),
                new RetirementWithdrawalProcessor());
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
        // Audit C10: geometricMeans(false) (the unchanged default) unless the scenario opts into
        // the extended 1928-2025 Depression-era window via params_json.include_depression_years.
        Map<AssetClass, Double> geoMeans = capitalMarketAssumptions != null
                ? capitalMarketAssumptions.geometricMeans(paramsParser.includeDepressionYears(params))
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

    /**
     * Carry-forward state threaded between successive year iterations. {@code magiYearMinus1} /
     * {@code magiYearMinus2} are the Wave-4 IRMAA item's 2-year MAGI lookback window (year Y's
     * surcharge is keyed on year Y-2's MAGI, the statutory lookback) -- {@code null} until that
     * many years have actually been simulated within THIS projection (the first two years have no
     * in-horizon prior-year data to look back to; see {@link #processYear}'s IRMAA block).
     */
    private record YearAccumulator(int yearsInRetirement, BigDecimal previousWithdrawal, BigDecimal suspendedLoss,
                                    @Nullable BigDecimal magiYearMinus1, @Nullable BigDecimal magiYearMinus2) {
        static final YearAccumulator INITIAL = new YearAccumulator(0, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
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

        // Wave-4 IRMAA: this year's Medicare premium surcharge, keyed on MAGI from TWO CALENDAR
        // YEARS PRIOR (the statutory lookback -- see YearAccumulator's javadoc for how the window
        // rolls forward). Gated on `retired` like RMDs (a documented simplification: working-past-
        // Medicare-age scenarios are out of scope, mirroring the existing RMD/`retired` gate). The
        // first two simulated years of any projection have no in-horizon 2-years-prior MAGI to look
        // back to (that data predates the projection's start) -- SKIPPED (surcharge stays zero)
        // rather than backfilled with a same-year estimate, since a pre-retirement or
        // just-retired-year MAGI figure is not a reliable stand-in for whatever the retiree's
        // ACTUAL income was two years before this projection began. Deterministic engine ONLY --
        // the Monte Carlo engine (TrialSimulator et al.) is untouched by this item; MC trials do
        // not model the IRMAA surcharge.
        BigDecimal irmaaSurcharge = BigDecimal.ZERO;
        if (retired && age >= MEDICARE_AGE && acc.magiYearMinus2() != null && irmaaSurchargeCalculator != null) {
            irmaaSurcharge = irmaaSurchargeCalculator.computeAnnualSurcharge(
                    acc.magiYearMinus2(), year, pool.getFilingStatus());
        }

        var comp = yearFinanceResolver.resolve(new YearFinanceResolver.YearContext(
                pool, ctx.incomeSources(), ctx.spendingPlan(), ctx.strategy(), ctx.taxStrategy(),
                ctx.inflationRate(), year, age, retired, yearsInRetirement, startBalance,
                ctx.currentYear(), acc.previousWithdrawal(), acc.suspendedLoss(), rmdAmount, irmaaSurcharge));

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
                taxLiability, pool, ctx.taxStrategy(), ltcgTax,
                comp.realizedLtcgIncome(), comp.socialSecurityTaxable(), irmaaSurcharge);
        yearDto = retirementTaxAnnotator.annotate(yearDto, annCtx);

        // Wave-4 IRMAA: roll the 2-year MAGI lookback window forward -- see YearAccumulator's
        // javadoc. This year's MAGI proxy mirrors the composition already used for the audit-B2
        // Social Security provisional-income convergence (wdFromTraditional + conversionAmount +
        // realizedLtcgIncome, i.e. realizedPortfolioTaxable) plus effectiveOtherIncome (which itself
        // already folds in the taxable portion of Social Security via that same convergence) --
        // the same depth of MAGI modeling already established elsewhere in this engine. It is NOT
        // full IRS MAGI (no tax-exempt-interest add-back), a documented simplification.
        BigDecimal magiThisYear = comp.effectiveOtherIncome().add(conversionAmount)
                .add(wdFromTraditional).add(comp.realizedLtcgIncome());

        return new YearStepResult(yearDto, new YearAccumulator(yearsInRetirement, previousWithdrawal, suspendedLoss,
                magiThisYear, acc.magiYearMinus1()));
    }

}
