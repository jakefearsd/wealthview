package com.wealthview.projection;

import java.math.BigDecimal;
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
import com.wealthview.core.projection.household.PersonId;
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
     * Household task 5: the survivor spending factor assumed when a household scenario omits it (spec
     * §1 default). Only ever consulted once a first-death transition fires, so single-person and
     * both-alive years are unaffected.
     */
    private static final BigDecimal DEFAULT_SURVIVOR_SPENDING_FACTOR = new BigDecimal("0.75");

    // HP3 Part B-2: ScenarioCrudService.validateSurvivorSpendingFactor enforces this SAME [0.5, 1.0]
    // range at write time, but a params_json that bypasses that check (a stale persisted value, a
    // future write path) previously reached the engine's survivor-factor scaling raw -- clamp
    // defensively here too, mirroring the same guard on the MC seam (HouseholdMcResolver).
    private static final BigDecimal MIN_SURVIVOR_SPENDING_FACTOR = new BigDecimal("0.5");
    private static final BigDecimal MAX_SURVIVOR_SPENDING_FACTOR = BigDecimal.ONE;

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
            TaxCalculationStrategy taxStrategy,
            BigDecimal survivorSpendingFactor,
            boolean communityProperty) {
    }

    private ProjectionResultResponse runInternal(ProjectionInput input) {
        var accounts = input.accounts();
        var params = paramsParser.parseParams(input.paramsJson());

        log.info("Starting projection for scenario '{}': {} accounts, retirement year {}, end age {}",
                input.scenarioName(), accounts.size(),
                input.retirementDate() != null ? input.retirementDate().getYear() : "default",
                input.endAge() != null ? input.endAge() : 90);

        var resolved = resolveProjectionParams(input, params);
        var taxStrategy = taxStrategyFactory.buildTaxStrategy(params, input.household());
        var pool = buildPoolStrategy(accounts, params, taxStrategy, resolved.inflationRate(),
                resolved.currentYear());

        // Household task 5: the survivor spending factor (spec default 0.75 when a household scenario
        // omits it) and the community-property flag drive the first-death transition. Both are inert
        // for a single-person context (no transition ever fires), so the defaults never move a
        // pre-household path.
        BigDecimal survivorSpendingFactor = params.survivorSpendingFactor() != null
                ? clampSurvivorSpendingFactor(params.survivorSpendingFactor()) : DEFAULT_SURVIVOR_SPENDING_FACTOR;
        boolean communityProperty = Boolean.TRUE.equals(params.communityProperty());

        var ctx = new ProjectionRunContext(input, pool, resolved.strategy(),
                resolved.currentYear(), resolved.birthYear(), resolved.retirementYear(), resolved.endYear(),
                resolved.inflationRate(), resolved.spendingPlan(), resolved.incomeSources(),
                resolved.properties(), taxStrategy, survivorSpendingFactor, communityProperty);
        return runProjection(ctx);
    }

    /** HP3 Part B-2: clamps an explicit override to the documented [0.5, 1.0] range -- see the
     * field comment above. A no-op for every value {@code ScenarioCrudService} would have accepted
     * (the byte-identical anchor for the in-range/default paths). */
    private static BigDecimal clampSurvivorSpendingFactor(BigDecimal factor) {
        return factor.max(MIN_SURVIVOR_SPENDING_FACTOR).min(MAX_SURVIVOR_SPENDING_FACTOR);
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
        var config = PoolStrategy.PoolConfig.builder(
                params.filingStatus() != null ? FilingStatus.fromString(params.filingStatus()) : FilingStatus.SINGLE,
                params.otherIncome() != null ? params.otherIncome() : BigDecimal.ZERO,
                params.annualRothConversion() != null ? params.annualRothConversion() : BigDecimal.ZERO,
                params.rothConversionStrategy(), params.targetBracketRate(),
                params.rothConversionStartYear(), params.resolvedWithdrawalOrder(), taxStrategy,
                params.dynamicSequencingBracketRate())
                .capitalMarketAssumptions(geoMeans, inflationRate)
                .capitalGainsTaxCalculator(capitalGainsTaxCalculator)
                .dividendYield(paramsParser.dividendYield(params))
                .interestYield(paramsParser.interestYield(params))
                .feeRate(paramsParser.feeRate(params))
                .baseYear(baseYear)
                .federalTaxCalculator(federalTaxCalculator)
                .build();
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

        // Household task 5 (transition step 6 — truncation): when the SECOND (survivor's) death falls
        // inside the horizon, the projection ends in that year — the final row's balance is the
        // bequest. HouseholdContext only reports secondDeathYear when it is within the configured
        // horizon, so the min() here is belt-and-suspenders; single-person/both-beyond-horizon
        // contexts report empty and the loop runs the full horizon unchanged (the byte-identical
        // anchor).
        int loopEndYear = ctx.endYear();
        var household = ctx.input().household();
        if (household != null && household.secondDeathYear().isPresent()) {
            loopEndYear = Math.min(loopEndYear, household.secondDeathYear().get() + 1);
        }

        for (int year = ctx.currentYear(); year < loopEndYear; year++) {
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

        // Snapshot BEFORE applyGrowth(): each owner's RMD is computed off THAT owner's prior year-end
        // traditional balance (IRS Pub. 590-B), not this year's growth. Household task 4: per-owner
        // snapshots so an age-gap couple runs one independent RMD stream per spouse.
        Map<PersonId, BigDecimal> priorTraditionalByOwner = pool.getTraditionalByOwner();
        // Audit C8: `retired` (already resolved above) gates the taxable pool's yield-distribution
        // split -- see PoolStrategy.MultiPool#applyGrowth(boolean)'s javadoc.
        var growthResult = pool.applyGrowth(retired);
        BigDecimal totalGrowth = growthResult.total();

        // T18a-2 + household task 4: RMDs are gated on AGE alone, not `retired` -- IRS rules require a
        // traditional (IRA-type) account owner to take RMDs starting at the SECURE-2.0 age regardless
        // of whether they are still working. One stream per owner, each keyed to that owner's own
        // birth-year start age and prior-year balance, so the spouse's IRA no longer wrongly RMDs at
        // the primary's age. The physical force-out happens HERE, right after growth and before the
        // Social Security convergence snapshot inside YearFinanceResolver#resolve (IRS ordering: the
        // RMD is distributed before any Roth conversion). rmdAmount is the summed REQUESTED figure
        // (the DTO's rmd_amount); rmdForced is the summed amount actually distributed (capped per
        // owner at that owner's balance), threaded into the resolver for tax attribution.
        var rmdStreams = RmdStreamCalculator.computeAndForce(
                ctx.input().household(), ctx.birthYear(), year, priorTraditionalByOwner, pool);
        BigDecimal rmdAmount = rmdStreams.requested();
        BigDecimal rmdForced = rmdStreams.forced();

        // Household task 5: fire the first-death transition (once, at the boundary) and resolve this
        // year's survivor-mode income sources + spending factor — see HouseholdTransition.
        var survivorYear = HouseholdTransition.resolveYear(ctx.input().household(), pool, year,
                ctx.incomeSources(), ctx.currentYear(), ctx.inflationRate(),
                ctx.survivorSpendingFactor(), ctx.communityProperty());
        List<ProjectionIncomeSourceInput> yearIncomeSources = survivorYear.incomeSources();
        BigDecimal yearSurvivorSpendingFactor = survivorYear.spendingFactor();

        // Wave-4 IRMAA: this year's Medicare premium surcharge, keyed on MAGI from TWO CALENDAR
        // YEARS PRIOR (the statutory lookback -- see YearAccumulator's javadoc for how the window
        // rolls forward). Gated on `retired` like RMDs (a documented simplification: working-past-
        // Medicare-age scenarios are out of scope, mirroring the existing RMD/`retired` gate). The
        // first two simulated years of any projection have no in-horizon 2-years-prior MAGI to look
        // back to (that data predates the projection's start) -- SKIPPED (surcharge stays zero)
        // rather than backfilled with a same-year estimate, since a pre-retirement or
        // just-retired-year MAGI figure is not a reliable stand-in for whatever the retiree's
        // ACTUAL income was two years before this projection began. Deterministic engine ONLY --
        // the Monte Carlo engine (TrialSimulator et al.) does not model IRMAA at all (no site to
        // extend for household task 7 there).
        //
        // Household task 7 (spec §4 step 6): the surcharge applies PER Medicare-enrolled household
        // member -- `medicareQualifyingCount` is the number of currently-alive members 65+ in `year`
        // (0, 1, or 2 while both are alive; at most 1 once only the survivor remains, post-
        // transition). Single-person / no-household reproduces the pre-task-7 `age >= MEDICARE_AGE`
        // gate exactly (count is 0 or 1 from the primary alone).
        var household = ctx.input().household();
        int medicareQualifyingCount = household != null
                ? household.age65QualifyingCount(year)
                : age >= MEDICARE_AGE ? 1 : 0;
        BigDecimal irmaaSurcharge = BigDecimal.ZERO;
        if (retired && medicareQualifyingCount > 0 && acc.magiYearMinus2() != null
                && irmaaSurchargeCalculator != null) {
            irmaaSurcharge = irmaaSurchargeCalculator.computeAnnualSurcharge(
                            acc.magiYearMinus2(), year, pool.getFilingStatus())
                    .multiply(BigDecimal.valueOf(medicareQualifyingCount));
        }

        var comp = yearFinanceResolver.resolve(new YearFinanceResolver.YearContext(
                pool, yearIncomeSources, ctx.spendingPlan(), ctx.strategy(), ctx.taxStrategy(),
                ctx.inflationRate(), year, age, retired, yearsInRetirement, startBalance,
                ctx.currentYear(), acc.previousWithdrawal(), acc.suspendedLoss(), rmdForced, irmaaSurcharge,
                yearSurvivorSpendingFactor, household));

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
        BigDecimal earlyWithdrawalPenalty = comp.earlyWithdrawalPenalty();
        var combinedTaxSource = comp.combinedTaxSource();

        pool.floorAtZero();

        int yearsElapsed = year - ctx.currentYear();
        BigDecimal propertyEquity = PropertyEquityCalculator.compute(ctx.properties(), yearsElapsed);

        var yearDto = pool.buildYearDto(new PoolStrategy.YearDtoContext(
                year, age, startBalance, contributions,
                totalGrowth, withdrawals, retired, conversionAmount, taxLiability,
                growthResult, wdFromTaxable, wdFromTraditional, wdFromRoth, combinedTaxSource,
                rmdAmount, ltcgTax, earlyWithdrawalPenalty));
        yearDto = PropertyEquityCalculator.apply(yearDto, propertyEquity);
        // Task 8 follow-up (T10 blocker): the viability DISCLOSURES scale by the same per-year
        // survivor factor as the actual draw (RetirementWithdrawalProcessor), so spendingSurplus /
        // computeFeasibility compare like with like post-transition.
        yearDto = feasibilityAnalyzer.applyViability(yearDto, ctx.spendingPlan(), year, age, yearsInRetirement,
                ctx.inflationRate(), comp.totalActiveIncome(), yearSurvivorSpendingFactor);
        yearDto = IncomeSourceFieldMapper.apply(yearDto, comp.isResult());
        yearDto = yearDto.withSurplusReinvested(surplusReinvested);
        // T18a-5b: threaded into the annotator so its federal/state recompute can fold both
        // already-charged additive obligations into the federal component -- see
        // RetirementTaxAnnotator.AnnotationContext's javadoc.
        BigDecimal selfEmploymentTax = comp.isResult() != null
                ? comp.isResult().selfEmploymentTax() : BigDecimal.ZERO;
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(retired, age, year,
                wdFromTraditional, conversionAmount, comp.effectiveOtherIncome(),
                taxLiability, pool, ctx.taxStrategy(), ltcgTax,
                comp.realizedLtcgIncome(), comp.socialSecurityTaxable(), irmaaSurcharge,
                selfEmploymentTax, earlyWithdrawalPenalty, comp.ordinaryInterestIncome());
        yearDto = retirementTaxAnnotator.annotate(yearDto, annCtx);

        // Wave-4 IRMAA: roll the 2-year MAGI lookback window forward -- see YearAccumulator's
        // javadoc. This year's MAGI proxy mirrors the composition already used for the audit-B2
        // Social Security provisional-income convergence (wdFromTraditional + conversionAmount +
        // realizedLtcgIncome + ordinaryInterestIncome, i.e. realizedPortfolioTaxable) plus
        // effectiveOtherIncome (which itself already folds in the taxable portion of Social
        // Security via that same convergence) -- the same depth of MAGI modeling already
        // established elsewhere in this engine. It is NOT full IRS MAGI (no tax-exempt-interest
        // add-back), a documented simplification.
        BigDecimal magiThisYear = comp.effectiveOtherIncome().add(conversionAmount)
                .add(wdFromTraditional).add(comp.realizedLtcgIncome()).add(comp.ordinaryInterestIncome());

        return new YearStepResult(yearDto, new YearAccumulator(yearsInRetirement, previousWithdrawal, suspendedLoss,
                magiThisYear, acc.magiYearMinus1()));
    }

}
