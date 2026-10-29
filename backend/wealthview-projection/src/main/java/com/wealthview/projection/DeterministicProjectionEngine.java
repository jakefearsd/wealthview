package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.wealthview.core.projection.BondTentAssumptions;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
import com.wealthview.core.projection.ProjectionEngine;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentReplayOutcome;
import com.wealthview.core.projection.dto.BondTentSizing;
import com.wealthview.core.projection.dto.BondTentYear;
import com.wealthview.core.projection.dto.HistoricalReturnPath;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionPropertyInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionRunDetail;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.SpendingPlan;
import com.wealthview.core.projection.dto.YearTaxPicture;
import com.wealthview.core.projection.household.PersonId;
import com.wealthview.core.projection.strategy.WithdrawalStrategy;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.IrmaaSurchargeCalculator;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import com.wealthview.core.projection.tax.SelfEmploymentTaxCalculator;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;
import com.wealthview.core.projection.tax.StateTaxCalculatorFactory;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;
import com.wealthview.core.projection.tax.TaxSpaceCalculator;
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
    static final int MEDICARE_AGE = 65;
    private final ScenarioParamsParser paramsParser = new ScenarioParamsParser();
    private final SpendingFeasibilityAnalyzer feasibilityAnalyzer = new SpendingFeasibilityAnalyzer();
    private final RetirementTaxAnnotator retirementTaxAnnotator = new RetirementTaxAnnotator();
    private final TaxStrategyFactory taxStrategyFactory;
    private final YearFinanceResolver yearFinanceResolver;
    private final PoolStrategyAssembler poolAssembler;
    /** Wave-4 IRMAA item: null omits the surcharge entirely (irmaaSurcharge stays zero every year). */
    @Nullable
    private final IrmaaSurchargeCalculator irmaaSurchargeCalculator;
    @Nullable
    private final MeterRegistry meterRegistry;
    /** Phase 1a: null omits tax-space computation (taxSpace stays empty; pictures still emitted). */
    @Nullable
    private final TaxSpaceCalculator taxSpaceCalculator;
    /** Null leaves bond tents unsupported: a run that configures one throws rather than silently ignoring it. */
    @Nullable
    private final BondTentAssumptions bondTentAssumptions;

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

    /** Constructor without the tax-space calculator: {@code runDetailed().taxSpace()} stays empty. */
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory,
                                          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                          @Nullable IrmaaSurchargeCalculator irmaaSurchargeCalculator,
                                          @Nullable MeterRegistry meterRegistry,
                                          @Nullable CapitalMarketAssumptionsProvider capitalMarketAssumptions) {
        this(taxCalculator, stateTaxCalculatorFactory, capitalGainsTaxCalculator, irmaaSurchargeCalculator,
                meterRegistry, capitalMarketAssumptions, null);
    }

    /** Constructor without bond-tent assumptions: a scenario that configures a tent is rejected at run time. */
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory,
                                          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                          @Nullable IrmaaSurchargeCalculator irmaaSurchargeCalculator,
                                          @Nullable MeterRegistry meterRegistry,
                                          @Nullable CapitalMarketAssumptionsProvider capitalMarketAssumptions,
                                          @Nullable TaxSpaceCalculator taxSpaceCalculator) {
        this(taxCalculator, stateTaxCalculatorFactory, capitalGainsTaxCalculator, irmaaSurchargeCalculator,
                meterRegistry, capitalMarketAssumptions, taxSpaceCalculator, null);
    }

    @Autowired
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory,
                                          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                          @Nullable IrmaaSurchargeCalculator irmaaSurchargeCalculator,
                                          @Nullable MeterRegistry meterRegistry,
                                          @Nullable CapitalMarketAssumptionsProvider capitalMarketAssumptions,
                                          @Nullable TaxSpaceCalculator taxSpaceCalculator,
                                          @Nullable BondTentAssumptions bondTentAssumptions) {
        this.taxStrategyFactory = new TaxStrategyFactory(taxCalculator, stateTaxCalculatorFactory);
        this.poolAssembler = new PoolStrategyAssembler(taxCalculator, capitalGainsTaxCalculator,
                capitalMarketAssumptions);
        this.irmaaSurchargeCalculator = irmaaSurchargeCalculator;
        this.meterRegistry = meterRegistry;
        this.taxSpaceCalculator = taxSpaceCalculator;
        this.bondTentAssumptions = bondTentAssumptions;
        var rentalLossCalculator = new RentalLossCalculator();
        var ssTaxCalculator = new SocialSecurityTaxCalculator();
        var seTaxCalculator = new SelfEmploymentTaxCalculator();
        var incomeSourceProcessor = new IncomeSourceProcessor(
                rentalLossCalculator, ssTaxCalculator, seTaxCalculator);
        this.yearFinanceResolver = new YearFinanceResolver(
                incomeSourceProcessor, new IncomeContributionCalculator(),
                new RetirementWithdrawalProcessor());
    }

    // No @Timed here — see the note in MonteCarloSpendingOptimizer#optimize. This name did not
    // crash the scrape (neither timer asked for buckets, so both rendered as Summary), but the two
    // annotations still recorded every run TWICE into one metric, doubling its count and sum.
    @Observed(name = "wealthview.projection.run",
              contextualName = "deterministic-projection",
              lowCardinalityKeyValues = {"component", "projection"})
    @Override
    public ProjectionResultResponse run(ProjectionInput input) {
        // Self-invocation: runDetailed's own @Observed does not fire again (no proxy hop), and the
        // runs counter increments exactly once, inside runDetailed.
        return runDetailed(input).result();
    }

    @Observed(name = "wealthview.projection.run",
              contextualName = "deterministic-projection",
              lowCardinalityKeyValues = {"component", "projection"})
    @Override
    public ProjectionRunDetail runDetailed(ProjectionInput input) {
        MDC.put("operation", "projection");
        MDC.put("scenarioName", input.scenarioName() != null ? input.scenarioName() : "unnamed");
        try {
            var detail = runInternal(input);
            if (meterRegistry != null) {
                meterRegistry.counter("wealthview.projection.runs", "type", "deterministic").increment();
            }
            return detail;
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
            boolean communityProperty,
            BigDecimal heirTaxRate,
            @Nullable BondTentPlanner.TentRun tent,
            boolean sizingPass,
            @Nullable CrashReplayer replay) {
    }

    private ProjectionRunDetail runInternal(ProjectionInput input) {
        var params = paramsParser.parseParams(input.paramsJson());

        log.info("Starting projection for scenario '{}': {} accounts, retirement year {}, end age {}",
                input.scenarioName(), input.accounts().size(),
                input.retirementDate() != null ? input.retirementDate().getYear() : "default",
                input.endAge() != null ? input.endAge() : 90);

        var resolved = resolveProjectionParams(input, params);
        var tentConfig = BondTentPlanner.activeConfig(params, resolved.spendingPlan());
        if (tentConfig.isEmpty()) {
            // No tent, or nothing to size it from (a tent needs a spending plan): one pass, unchanged.
            return runPass(input, params, resolved, null, false, null);
        }
        var frame = frameOf(resolved, params);
        return BondTentPlanner.twoPass(tent -> runPass(input, params, resolved, tent, true, null), tentConfig.get(),
                bondTentAssumptions, frame);
    }

    /**
     * Sizes every mix of {@code config} from a tent-free run of {@code input}: pass one of the two-pass tent
     * projection, on its own. The configuration only supplies the mixes and yields; the run itself is tent-free.
     */
    @Override
    public BondTentSizing sizeBondTent(ProjectionInput input, BondTentConfig config) {
        var params = paramsParser.parseParams(input.paramsJson());
        var resolved = resolveProjectionParams(input, params);
        var passOne = runPass(input, params, resolved, null, true, null);
        return BondTentPlanner.size(passOne.result().yearlyData(), config, resolved.inflationRate()).sizing();
    }

    /**
     * Replays {@code input} through {@code path}: once without the tent and once per mix, each year growing at
     * that year's returns. See {@link BondTentReplays}.
     */
    @Observed(name = "wealthview.projection.bond_tent_replay",
              contextualName = "bond-tent-replay",
              lowCardinalityKeyValues = {"component", "projection"})
    @Override
    public List<BondTentReplayOutcome> replayBondTent(ProjectionInput input, HistoricalReturnPath path) {
        var params = paramsParser.parseParams(input.paramsJson());
        var resolved = resolveProjectionParams(input, params);
        var config = BondTentPlanner.activeConfig(params, resolved.spendingPlan()).orElseThrow(
                () -> new IllegalArgumentException("A bond tent analysis needs a spending plan and a bond tent"));
        return BondTentReplays.replay(
                (tent, replayer) -> runPass(input, params, resolved, tent, true, replayer),
                config, bondTentAssumptions, frameOf(resolved, params),
                new BondTentReplays.Subject(input.accounts(), resolved.birthYear(), path));
    }

    private BondTentPlanner.Frame frameOf(ResolvedParams resolved, ScenarioParams params) {
        return new BondTentPlanner.Frame(resolved.currentYear(), resolved.retirementYear(),
                resolved.inflationRate(), paramsParser.feeRate(params), params.resolvedWithdrawalOrder());
    }

    /**
     * One full year loop. {@code tent} (null for a tent-free pass) is configured on the fresh pools;
     * {@code tentActive} marks either pass of a tent run, which projects on the default return window;
     * {@code replay} (null outside a crash replay) feeds the pools a historical year's returns.
     */
    private ProjectionRunDetail runPass(ProjectionInput input, ScenarioParams params, ResolvedParams resolved,
                                        @Nullable BondTentPlanner.TentRun tent, boolean tentActive,
                                        @Nullable CrashReplayer replay) {
        var taxStrategy = taxStrategyFactory.buildTaxStrategy(params, input.household());
        var pool = poolAssembler.build(input.accounts(), params, taxStrategy, resolved.inflationRate(),
                resolved.currentYear(), tentActive);
        BondTentPlanner.configure(pool, tent);

        // Household task 5: the survivor spending factor (spec default 0.75 when a household scenario
        // omits it) and the community-property flag drive the first-death transition. Both are inert
        // for a single-person context (no transition ever fires), so the defaults never move a
        // pre-household path.
        BigDecimal survivorSpendingFactor = paramsParser.survivorSpendingFactor(params);
        boolean communityProperty = Boolean.TRUE.equals(params.communityProperty());

        var ctx = new ProjectionRunContext(input, pool, resolved.strategy(),
                resolved.currentYear(), resolved.birthYear(), resolved.retirementYear(), resolved.endYear(),
                resolved.inflationRate(), resolved.spendingPlan(), resolved.incomeSources(),
                resolved.properties(), taxStrategy, survivorSpendingFactor, communityProperty,
                paramsParser.heirTaxRate(params), tent, tentActive && tent == null || replay != null, replay);
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

    private record YearStepResult(ProjectionYearDto yearDto, YearAccumulator nextAccumulator,
                                  Optional<YearTaxPicture> taxPicture) {}

    private ProjectionRunDetail runProjection(ProjectionRunContext ctx) {
        var yearlyData = new ArrayList<ProjectionYearDto>();
        var taxPictures = new ArrayList<YearTaxPicture>();
        var tentYears = new ArrayList<BondTentYear>();
        var acc = YearAccumulator.INITIAL;

        // Household task 5 (transition step 6 — truncation): when the SECOND (survivor's) death falls
        // inside the horizon, the projection ends in that year — the final row's balance is the
        // bequest. HouseholdContext only reports secondDeathYear when it is within the configured
        // horizon, so the min() here is belt-and-suspenders; single-person/both-beyond-horizon
        // contexts report empty and the loop runs the full horizon unchanged (the byte-identical
        // anchor).
        int loopEndYear = ctx.endYear();
        var tent = ctx.tent();
        var household = ctx.input().household();
        if (household != null && household.secondDeathYear().isPresent()) {
            loopEndYear = Math.min(loopEndYear, household.secondDeathYear().get() + 1);
        }

        for (int year = ctx.currentYear(); year < loopEndYear; year++) {
            var step = processYear(ctx, year, acc);
            yearlyData.add(step.yearDto());
            step.taxPicture().ifPresent(taxPictures::add);
            BondTentPlanner.record(tentYears, year, ctx.pool(), tent);
            acc = step.nextAccumulator();
        }

        BigDecimal finalBalance = yearlyData.isEmpty()
                ? ctx.pool().getTotal()
                : yearlyData.getLast().endBalance();

        // Pass one of a tent run, a sizing-only run and every replay pass are discarded work: keep them off INFO.
        var summaryLevel = ctx.sizingPass() ? Level.DEBUG : Level.INFO;
        log.atLevel(summaryLevel).log("{} for scenario '{}': {} years, final balance {}",
                ctx.pool().logTag(), ctx.input().scenarioName(), yearlyData.size(), finalBalance);

        var feasibility = feasibilityAnalyzer.computeFeasibility(yearlyData, ctx.spendingPlan());
        BigDecimal finalNetWorth = yearlyData.isEmpty() ? null : yearlyData.getLast().totalNetWorth();
        var result = new ProjectionResultResponse(ctx.input().scenarioId(), yearlyData, finalBalance,
                acc.yearsInRetirement(), feasibility, finalNetWorth);
        var bondTent = BondTentPlanner.result(tent, tentYears);
        return new ProjectionRunDetail(result, List.copyOf(taxPictures),
                YearTaxPictureBuilder.computeTaxSpace(taxSpaceCalculator, taxPictures, ctx.taxStrategy()),
                TerminalValueResolver.resolve(yearlyData, ctx.input().household(), ctx.heirTaxRate()), bondTent);
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
        BondTentPlanner.topUp(pool, ctx.tent(), year, ctx.replay(), contributions);

        // Snapshot BEFORE applyGrowth(): each owner's RMD is computed off THAT owner's prior year-end
        // traditional balance (IRS Pub. 590-B), not this year's growth. Household task 4: per-owner
        // snapshots so an age-gap couple runs one independent RMD stream per spouse.
        Map<PersonId, BigDecimal> priorTraditionalByOwner = pool.getTraditionalByOwner();
        // Audit C8: `retired` (already resolved above) gates the taxable pool's yield-distribution
        // split -- see MultiPool#applyGrowth(boolean)'s javadoc.
        CrashReplayer.beforeGrowth(ctx.replay(), pool, year);
        var growthResult = pool.applyGrowth(retired);
        CrashReplayer.afterGrowth(ctx.replay(), pool);
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
        CrashReplayer.settle(ctx.replay(), pool, year, age, retired);

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

        var taxPicture = YearTaxPictureBuilder.buildIfRetired(retired,
                new YearTaxPictureBuilder.PictureContext(household, pool.getFilingStatus(), year, age,
                        ctx.birthYear(), ctx.currentYear(), ctx.inflationRate()),
                comp, magiThisYear, selfEmploymentTax);

        return new YearStepResult(yearDto, new YearAccumulator(yearsInRetirement, previousWithdrawal, suspendedLoss,
                magiThisYear, acc.magiYearMinus1()), taxPicture);
    }

}
