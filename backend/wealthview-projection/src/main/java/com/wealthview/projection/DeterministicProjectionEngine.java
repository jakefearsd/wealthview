package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.wealthview.core.projection.ProjectionEngine;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionPropertyInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.SpendingPlan;
import com.wealthview.core.projection.strategy.DynamicPercentageWithdrawal;
import com.wealthview.core.projection.strategy.FixedPercentageWithdrawal;
import com.wealthview.core.projection.strategy.VanguardDynamicSpendingWithdrawal;
import com.wealthview.core.projection.strategy.WithdrawalStrategy;
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

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

@Component
// GodClass: the engine is an orchestrator that wires many projection collaborators;
// PMD's coupling/cohesion heuristic still flags it after the Phase 3 decomposition.
@SuppressWarnings("PMD.GodClass")
public class DeterministicProjectionEngine implements ProjectionEngine {

    private static final Logger log = LoggerFactory.getLogger(DeterministicProjectionEngine.class);
    private static final BigDecimal DEFAULT_WITHDRAWAL_RATE = new BigDecimal("0.04");

    private final ScenarioParamsParser paramsParser = new ScenarioParamsParser();
    private final SpendingFeasibilityAnalyzer feasibilityAnalyzer = new SpendingFeasibilityAnalyzer();
    private final RetirementWithdrawalProcessor retirementWithdrawalProcessor = new RetirementWithdrawalProcessor();
    private final RetirementTaxAnnotator retirementTaxAnnotator = new RetirementTaxAnnotator();
    private final TaxStrategyFactory taxStrategyFactory;
    private final IncomeSourceProcessor incomeSourceProcessor;
    private final IncomeContributionCalculator incomeContributionCalculator;
    @Nullable
    private final MeterRegistry meterRegistry;

    /** Test-friendly constructor that omits the optional meter registry. */
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory) {
        this(taxCalculator, stateTaxCalculatorFactory, null);
    }

    @Autowired
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory,
                                          @Nullable MeterRegistry meterRegistry) {
        this.taxStrategyFactory = new TaxStrategyFactory(taxCalculator, stateTaxCalculatorFactory);
        this.meterRegistry = meterRegistry;
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
        var pool = buildPoolStrategy(accounts, params, taxStrategy);

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
    private ResolvedParams resolveProjectionParams(ProjectionInput input, ScenarioParamsParser.ScenarioParams params) {
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
                : BigDecimal.ZERO;

        WithdrawalStrategy strategy = resolveStrategy(params, withdrawalRate);
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
                                              ScenarioParamsParser.ScenarioParams params,
                                              TaxCalculationStrategy taxStrategy) {
        var config = new PoolStrategy.PoolConfig(
                params.filingStatus() != null ? FilingStatus.fromString(params.filingStatus()) : FilingStatus.SINGLE,
                params.otherIncome() != null ? params.otherIncome() : BigDecimal.ZERO,
                params.annualRothConversion() != null ? params.annualRothConversion() : BigDecimal.ZERO,
                params.rothConversionStrategy(), params.targetBracketRate(),
                params.rothConversionStartYear(), params.withdrawalOrder(), taxStrategy,
                params.dynamicSequencingBracketRate());
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

        var feasibility = feasibilityAnalyzer.computeFeasibility(yearlyData, ctx.spendingPlan(), ctx.inflationRate());
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
        BigDecimal withdrawals = BigDecimal.ZERO;
        int yearsInRetirement = retired ? acc.yearsInRetirement() + 1 : acc.yearsInRetirement();
        if (!retired) {
            contributions = pool.applyContributions();
        }

        var growthResult = pool.applyGrowth();
        BigDecimal totalGrowth = growthResult.total();

        var incomeResult = processIncomeAndConversions(
                pool, ctx.incomeSources(), age, yearsInRetirement, year, acc.suspendedLoss(),
                resolveConversionOverride(ctx.spendingPlan(), year));
        BigDecimal suspendedLoss = incomeResult.suspendedLoss();
        BigDecimal conversionAmount = incomeResult.conversionAmount();
        BigDecimal taxLiability = incomeResult.taxLiability();

        BigDecimal surplusReinvested = null;
        BigDecimal wdFromTaxable = BigDecimal.ZERO;
        BigDecimal wdFromTraditional = BigDecimal.ZERO;
        BigDecimal wdFromRoth = BigDecimal.ZERO;
        BigDecimal previousWithdrawal = acc.previousWithdrawal();
        PoolStrategy.TaxSourceResult withdrawalTaxSource = PoolStrategy.TaxSourceResult.ZERO;
        if (retired) {
            var rwCtx = new RetirementWithdrawalProcessor.RetirementWithdrawalContext(
                    pool, ctx.strategy(), ctx.spendingPlan(), age, yearsInRetirement, year,
                    ctx.inflationRate(), incomeResult.totalActiveIncome(), startBalance,
                    previousWithdrawal, incomeResult.effectiveOtherIncome(), conversionAmount,
                    incomeResult.isResult(), ctx.taxStrategy());
            var retirementResult = retirementWithdrawalProcessor.process(rwCtx);
            withdrawals = retirementResult.withdrawals();
            taxLiability = taxLiability.add(retirementResult.taxLiability());
            previousWithdrawal = retirementResult.previousWithdrawal();
            surplusReinvested = retirementResult.surplusReinvested();
            wdFromTaxable = retirementResult.withdrawalFromTaxable();
            wdFromTraditional = retirementResult.withdrawalFromTraditional();
            wdFromRoth = retirementResult.withdrawalFromRoth();
            withdrawalTaxSource = retirementResult.withdrawalTaxSource();
        }

        var combinedTaxSource = incomeResult.conversionTaxSource().add(withdrawalTaxSource);

        pool.floorAtZero();

        int yearsElapsed = year - ctx.currentYear();
        BigDecimal propertyEquity = computePropertyEquity(ctx.properties(), yearsElapsed);

        var yearDto = pool.buildYearDto(year, age, startBalance, contributions,
                totalGrowth, withdrawals, retired, conversionAmount, taxLiability,
                growthResult, wdFromTaxable, wdFromTraditional, wdFromRoth, combinedTaxSource);
        yearDto = applyPropertyEquity(yearDto, propertyEquity);
        yearDto = feasibilityAnalyzer.applyViability(yearDto, ctx.spendingPlan(), year, age, yearsInRetirement,
                ctx.inflationRate(), incomeResult.totalActiveIncome());
        yearDto = applyIncomeSourceFields(yearDto, incomeResult.isResult());
        yearDto = yearDto.withSurplusReinvested(surplusReinvested);
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(retired, age, year,
                wdFromTraditional, conversionAmount, incomeResult.effectiveOtherIncome(),
                taxLiability, pool, ctx.taxStrategy());
        yearDto = retirementTaxAnnotator.annotate(yearDto, annCtx);

        return new YearStepResult(yearDto, new YearAccumulator(yearsInRetirement, previousWithdrawal, suspendedLoss));
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
            BigDecimal conversionOverride) {

        IncomeSourceProcessor.IncomeSourceYearResult incomeSourceResult = null;
        BigDecimal totalActiveIncome;
        BigDecimal taxableActiveIncome;

        if (pool.processIncomeSourcesEveryYear() || yearsInRetirement > 0) {
            incomeSourceResult = incomeSourceProcessor.process(incomeSources, age, yearsInRetirement,
                    year, pool.getMagi(), pool.getFilingStatusString(), suspendedLoss);
            suspendedLoss = incomeSourceResult.suspendedLossCarryforward();
            totalActiveIncome = incomeSourceResult.totalCashInflow();
            taxableActiveIncome = incomeSourceResult.totalTaxableIncome();
        } else {
            totalActiveIncome = incomeContributionCalculator.compute(incomeSources, age, yearsInRetirement);
            taxableActiveIncome = totalActiveIncome;
        }

        BigDecimal effectiveOtherIncome = pool.computeEffectiveOtherIncome(taxableActiveIncome, BigDecimal.ZERO);

        PoolStrategy.ConversionResult conversion;
        if (conversionOverride != null && conversionOverride.compareTo(BigDecimal.ZERO) > 0) {
            conversion = pool.executeRothConversionOverride(year, effectiveOtherIncome, conversionOverride);
        } else if (conversionOverride != null) {
            // Override is present but zero → no conversion this year
            conversion = new PoolStrategy.ConversionResult(
                    BigDecimal.ZERO, BigDecimal.ZERO, PoolStrategy.TaxSourceResult.ZERO);
        } else {
            conversion = pool.executeRothConversion(year, effectiveOtherIncome);
        }

        return new IncomeAndConversionResult(incomeSourceResult, totalActiveIncome, effectiveOtherIncome,
                conversion.amountConverted(), conversion.taxLiability(), suspendedLoss, conversion.taxSource());
    }

    private WithdrawalStrategy resolveStrategy(ScenarioParamsParser.ScenarioParams params, BigDecimal withdrawalRate) {
        if (params.withdrawalStrategy() == null || params.withdrawalStrategy().isBlank()) {
            return new FixedPercentageWithdrawal(withdrawalRate);
        }
        return switch (params.withdrawalStrategy()) {
            case "dynamic_percentage" -> new DynamicPercentageWithdrawal(withdrawalRate);
            case "vanguard_dynamic_spending" -> {
                BigDecimal ceiling = params.dynamicCeiling() != null
                        ? params.dynamicCeiling() : new BigDecimal("0.05");
                BigDecimal floor = params.dynamicFloor() != null
                        ? params.dynamicFloor() : new BigDecimal("-0.025");
                yield new VanguardDynamicSpendingWithdrawal(withdrawalRate, ceiling, floor);
            }
            default -> new FixedPercentageWithdrawal(withdrawalRate);
        };
    }

    private ProjectionYearDto applyIncomeSourceFields(ProjectionYearDto base,
                                                        IncomeSourceProcessor.IncomeSourceYearResult isResult) {
        if (isResult == null) {
            return base;
        }

        BigDecimal totalIncome = isResult.totalCashInflow();
        var incomeBySource = isResult.incomeBySource().isEmpty() ? null : isResult.incomeBySource();
        var rentalDetails = isResult.rentalPropertyDetails().isEmpty()
                ? null : isResult.rentalPropertyDetails();

        return base.withIncomeSourceFields(
                positiveOrDefault(totalIncome, base.incomeStreamsTotal()),
                nullIfZero(isResult.rentalIncomeGross()),
                nullIfZero(isResult.rentalExpensesTotal()),
                nullIfZero(isResult.depreciationTotal()),
                nullIfZero(isResult.rentalLossApplied()),
                nullIfZero(isResult.suspendedLossCarryforward()),
                nullIfZero(isResult.socialSecurityTaxable()),
                nullIfZero(isResult.selfEmploymentTax()),
                incomeBySource,
                rentalDetails);
    }

    private BigDecimal nullIfZero(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : null;
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : fallback;
    }

    private BigDecimal computePropertyEquity(List<ProjectionPropertyInput> properties, int yearsElapsed) {
        if (properties.isEmpty()) {
            return null;
        }
        BigDecimal totalEquity = BigDecimal.ZERO;
        for (var prop : properties) {
            BigDecimal appreciationFactor = BigDecimal.ONE.add(prop.annualAppreciationRate())
                    .pow(yearsElapsed);
            BigDecimal projectedValue = prop.currentValue()
                    .multiply(appreciationFactor)
                    .setScale(SCALE, ROUNDING);

            BigDecimal mortgageBalance;
            if (prop.loanAmount() != null && prop.annualInterestRate() != null
                    && prop.loanTermMonths() > 0 && prop.loanStartDate() != null) {
                // Amortize from the start of the projection to projection year
                LocalDate asOf = prop.loanStartDate()
                        .plusYears(yearsElapsed)
                        .withDayOfYear(1);
                mortgageBalance = com.wealthview.core.property.AmortizationCalculator
                        .remainingBalance(prop.loanAmount(), prop.annualInterestRate(),
                                prop.loanTermMonths(), prop.loanStartDate(), asOf)
                        .max(BigDecimal.ZERO);
            } else {
                mortgageBalance = prop.mortgageBalance() != null ? prop.mortgageBalance() : BigDecimal.ZERO;
            }

            totalEquity = totalEquity.add(projectedValue.subtract(mortgageBalance));
        }
        return totalEquity;
    }

    private ProjectionYearDto applyPropertyEquity(ProjectionYearDto base, BigDecimal propertyEquity) {
        if (propertyEquity == null) {
            return base;
        }
        BigDecimal totalNetWorth = base.endBalance().add(propertyEquity);
        return base.withPropertyEquity(propertyEquity, totalNetWorth);
    }

}
