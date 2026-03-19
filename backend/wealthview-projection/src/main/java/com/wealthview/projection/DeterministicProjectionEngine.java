package com.wealthview.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.projection.ProjectionEngine;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionPropertyInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.SpendingFeasibilitySummary;
import com.wealthview.core.projection.dto.SpendingPlan;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.dto.TierBasedSpendingPlan;
import com.wealthview.core.projection.strategy.DynamicPercentageWithdrawal;
import com.wealthview.core.projection.strategy.FixedPercentageWithdrawal;
import com.wealthview.core.projection.strategy.VanguardDynamicSpendingWithdrawal;
import com.wealthview.core.projection.strategy.WithdrawalContext;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.strategy.WithdrawalStrategy;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import com.wealthview.core.projection.tax.SelfEmploymentTaxCalculator;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@SuppressWarnings({"PMD.GodClass", "PMD.CouplingBetweenObjects"})
public class DeterministicProjectionEngine implements ProjectionEngine {

    private static final Logger log = LoggerFactory.getLogger(DeterministicProjectionEngine.class);
    private static final BigDecimal DEFAULT_WITHDRAWAL_RATE = new BigDecimal("0.04");
    private static final BigDecimal SHORTFALL_TOLERANCE = new BigDecimal("-10");
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FederalTaxCalculator taxCalculator;
    private final IncomeSourceProcessor incomeSourceProcessor;
    private final IncomeContributionCalculator incomeContributionCalculator;

    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator) {
        this.taxCalculator = taxCalculator;
        var rentalLossCalculator = new RentalLossCalculator();
        var ssTaxCalculator = new SocialSecurityTaxCalculator();
        var seTaxCalculator = new SelfEmploymentTaxCalculator();
        this.incomeSourceProcessor = new IncomeSourceProcessor(
                rentalLossCalculator, ssTaxCalculator, seTaxCalculator);
        this.incomeContributionCalculator = new IncomeContributionCalculator();
    }

    @Override
    public ProjectionResultResponse run(ProjectionInput input) {
        MDC.put("operation", "projection");
        MDC.put("scenarioName", input.scenarioName() != null ? input.scenarioName() : "unnamed");
        try {
            return runInternal(input);
        } finally {
            MDC.remove("operation");
            MDC.remove("scenarioName");
        }
    }

    private ProjectionResultResponse runInternal(ProjectionInput input) {
        var accounts = input.accounts();
        var params = parseParams(input.paramsJson());

        log.info("Starting projection for scenario '{}': {} accounts, retirement year {}, end age {}",
                input.scenarioName(), accounts.size(),
                input.retirementDate() != null ? input.retirementDate().getYear() : "default",
                input.endAge() != null ? input.endAge() : 90);

        int currentYear = input.referenceYear() != null ? input.referenceYear() : LocalDate.now().getYear();
        int birthYear = params.birthYear != null ? params.birthYear : currentYear - 35;
        int retirementYear = input.retirementDate() != null
                ? input.retirementDate().getYear()
                : currentYear + 30;
        int endAge = input.endAge() != null ? input.endAge() : 90;
        int endYear = birthYear + endAge;

        BigDecimal withdrawalRate = params.withdrawalRate != null
                ? params.withdrawalRate
                : DEFAULT_WITHDRAWAL_RATE;
        BigDecimal inflationRate = input.inflationRate() != null
                ? input.inflationRate()
                : BigDecimal.ZERO;

        WithdrawalStrategy strategy = resolveStrategy(params, withdrawalRate);
        SpendingPlan spendingPlan = null;
        if (input.guardrailSpending() != null) {
            spendingPlan = input.guardrailSpending();
        } else if (input.spendingProfile() != null) {
            spendingPlan = parseTierBasedPlan(input.spendingProfile());
        }

        var incomeSources = input.incomeSources() != null ? input.incomeSources() : List.<ProjectionIncomeSourceInput>of();
        var properties = input.properties() != null ? input.properties() : List.<ProjectionPropertyInput>of();

        boolean hasMultiplePools = hasMultipleAccountTypes(accounts);

        PoolStrategy pool;
        if (hasMultiplePools) {
            Map<String, List<ProjectionAccountInput>> grouped = accounts.stream()
                    .collect(Collectors.groupingBy(ProjectionAccountInput::accountType));

            FilingStatus filingStatus = params.filingStatus != null
                    ? FilingStatus.fromString(params.filingStatus) : FilingStatus.SINGLE;
            BigDecimal otherIncome = params.otherIncome != null ? params.otherIncome : BigDecimal.ZERO;
            BigDecimal annualRothConversion = params.annualRothConversion != null
                    ? params.annualRothConversion : BigDecimal.ZERO;

            pool = new PoolStrategy.MultiPool(grouped,
                    computeWeightedReturn(accounts,
                            sumInitialBalances(grouped.getOrDefault("taxable", List.of()))
                                    .add(sumInitialBalances(grouped.getOrDefault("traditional", List.of())))
                                    .add(sumInitialBalances(grouped.getOrDefault("roth", List.of())))),
                    filingStatus, otherIncome, annualRothConversion,
                    params.rothConversionStrategy(), params.targetBracketRate(),
                    params.rothConversionStartYear(), params.withdrawalOrder(), taxCalculator);
        } else {
            BigDecimal balance = sumInitialBalances(accounts);
            pool = new PoolStrategy.SinglePool(balance, sumContributions(accounts),
                    computeWeightedReturn(accounts, balance));
        }

        return runProjection(input, pool, strategy, currentYear, birthYear,
                retirementYear, endYear, inflationRate, spendingPlan, incomeSources, properties);
    }

    private ProjectionResultResponse runProjection(
            ProjectionInput input,
            PoolStrategy pool,
            WithdrawalStrategy strategy,
            int currentYear, int birthYear, int retirementYear, int endYear,
            BigDecimal inflationRate, SpendingPlan spendingPlan,
            List<ProjectionIncomeSourceInput> incomeSources,
            List<ProjectionPropertyInput> properties) {

        var yearlyData = new ArrayList<ProjectionYearDto>();
        int yearsInRetirement = 0;
        BigDecimal previousWithdrawal = BigDecimal.ZERO;
        BigDecimal suspendedLoss = BigDecimal.ZERO;

        for (int year = currentYear; year < endYear; year++) {
            int age = year - birthYear;
            boolean retired = year >= retirementYear;
            BigDecimal startBalance = pool.getTotal();

            BigDecimal contributions = BigDecimal.ZERO;
            BigDecimal withdrawals = BigDecimal.ZERO;

            if (!retired) {
                contributions = pool.applyContributions();
            }

            var growthResult = pool.applyGrowth();
            BigDecimal totalGrowth = growthResult.total();

            if (retired) {
                yearsInRetirement++;
            }

            var incomeResult = processIncomeAndConversions(
                    pool, incomeSources, age, yearsInRetirement, year, suspendedLoss);
            suspendedLoss = incomeResult.suspendedLoss();
            BigDecimal conversionAmount = incomeResult.conversionAmount();
            BigDecimal taxLiability = incomeResult.taxLiability();

            BigDecimal surplusReinvested = null;
            BigDecimal wdFromTaxable = BigDecimal.ZERO;
            BigDecimal wdFromTraditional = BigDecimal.ZERO;
            BigDecimal wdFromRoth = BigDecimal.ZERO;
            PoolStrategy.TaxSourceResult withdrawalTaxSource = PoolStrategy.TaxSourceResult.ZERO;
            if (retired) {
                var retirementResult = processRetirementWithdrawals(
                        pool, strategy, spendingPlan, age, yearsInRetirement,
                        inflationRate, incomeResult.totalActiveIncome(), startBalance,
                        previousWithdrawal, incomeResult.effectiveOtherIncome(), conversionAmount,
                        year, incomeResult.isResult());
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

            int yearsElapsed = year - currentYear;
            BigDecimal propertyEquity = computePropertyEquity(properties, yearsElapsed);

            var yearDto = pool.buildYearDto(year, age, startBalance, contributions,
                    totalGrowth, withdrawals, retired, conversionAmount, taxLiability,
                    growthResult, wdFromTaxable, wdFromTraditional, wdFromRoth, combinedTaxSource);
            yearDto = applyPropertyEquity(yearDto, propertyEquity);
            yearDto = applyViability(yearDto, spendingPlan, year, age, yearsInRetirement, inflationRate,
                    incomeResult.totalActiveIncome());
            if (incomeResult.isResult() != null) {
                yearDto = applyIncomeSourceFields(yearDto, incomeResult.isResult());
            }
            if (surplusReinvested != null) {
                yearDto = applySurplusReinvested(yearDto, surplusReinvested);
            }
            yearlyData.add(yearDto);
        }

        BigDecimal finalBalance = yearlyData.isEmpty()
                ? pool.getTotal()
                : yearlyData.getLast().endBalance();

        log.info("{} for scenario '{}': {} years, final balance {}",
                pool.logTag(), input.scenarioName(), yearlyData.size(), finalBalance);

        var feasibility = computeFeasibility(yearlyData, spendingPlan, inflationRate);
        BigDecimal finalNetWorth = yearlyData.isEmpty() ? null : yearlyData.getLast().totalNetWorth();
        return new ProjectionResultResponse(input.scenarioId(), yearlyData, finalBalance,
                yearsInRetirement, feasibility, finalNetWorth);
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
            int age, int yearsInRetirement, int year, BigDecimal suspendedLoss) {

        IncomeSourceProcessor.IncomeSourceYearResult incomeSourceResult = null;
        BigDecimal totalActiveIncome;

        if (pool.processIncomeSourcesEveryYear() || yearsInRetirement > 0) {
            incomeSourceResult = incomeSourceProcessor.process(incomeSources, age, yearsInRetirement,
                    year, pool.getMagi(), pool.getFilingStatusString(), suspendedLoss);
            suspendedLoss = incomeSourceResult.suspendedLossCarryforward();
            totalActiveIncome = incomeSourceResult.totalCashInflow();
        } else {
            totalActiveIncome = incomeContributionCalculator.compute(incomeSources, age, yearsInRetirement);
        }

        BigDecimal effectiveOtherIncome = pool.computeEffectiveOtherIncome(totalActiveIncome, BigDecimal.ZERO);
        var conversion = pool.executeRothConversion(year, effectiveOtherIncome);

        return new IncomeAndConversionResult(incomeSourceResult, totalActiveIncome, effectiveOtherIncome,
                conversion.amountConverted(), conversion.taxLiability(), suspendedLoss, conversion.taxSource());
    }

    private record RetirementWithdrawalResult(
            BigDecimal withdrawals,
            BigDecimal taxLiability,
            BigDecimal previousWithdrawal,
            BigDecimal surplusReinvested,
            BigDecimal withdrawalFromTaxable,
            BigDecimal withdrawalFromTraditional,
            BigDecimal withdrawalFromRoth,
            PoolStrategy.TaxSourceResult withdrawalTaxSource) {
    }

    @SuppressWarnings("PMD.ExcessiveParameterList")
    private RetirementWithdrawalResult processRetirementWithdrawals(
            PoolStrategy pool, WithdrawalStrategy strategy, SpendingPlan spendingPlan,
            int age, int yearsInRetirement, BigDecimal inflationRate,
            BigDecimal totalActiveIncome, BigDecimal startBalance, BigDecimal previousWithdrawal,
            BigDecimal effectiveOtherIncome, BigDecimal conversionAmount, int year,
            IncomeSourceProcessor.IncomeSourceYearResult isResult) {

        BigDecimal aggBalance = pool.getTotal();
        BigDecimal portfolioNeed;
        BigDecimal surplusReinvested = null;

        if (spendingPlan != null) {
            var resolved = spendingPlan.resolveYear(year, age, yearsInRetirement,
                    inflationRate, totalActiveIncome);
            portfolioNeed = resolved.portfolioWithdrawal().min(aggBalance);
            previousWithdrawal = resolved.totalSpending();

            // Detect surplus: income exceeds total spending
            BigDecimal grossSurplus = totalActiveIncome.subtract(resolved.totalSpending());
            if (grossSurplus.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal tax = BigDecimal.ZERO;
                if (taxCalculator != null && isResult != null) {
                    FilingStatus filingStatus = FilingStatus.fromString(pool.getFilingStatusString());
                    tax = taxCalculator.computeTax(isResult.totalTaxableIncome(), year, filingStatus);
                }
                BigDecimal afterTaxSurplus = grossSurplus.subtract(tax).max(BigDecimal.ZERO);
                if (afterTaxSurplus.compareTo(BigDecimal.ZERO) > 0) {
                    pool.depositToTaxable(afterTaxSurplus);
                    surplusReinvested = afterTaxSurplus;
                }
            }
        } else {
            var ctx = new WithdrawalContext(
                    aggBalance, startBalance, previousWithdrawal, pool.getWeightedReturn(),
                    inflationRate, yearsInRetirement);
            portfolioNeed = strategy.computeWithdrawal(ctx).min(aggBalance);
            previousWithdrawal = portfolioNeed;
        }

        var withdrawalResult = pool.executeWithdrawals(
                portfolioNeed, year, effectiveOtherIncome, conversionAmount);
        BigDecimal taxLiability = withdrawalResult.taxLiability();

        if (pool.tracksSETax() && isResult != null
                && isResult.selfEmploymentTax().compareTo(BigDecimal.ZERO) > 0) {
            taxLiability = taxLiability.add(isResult.selfEmploymentTax());
        }

        return new RetirementWithdrawalResult(withdrawalResult.totalWithdrawn(), taxLiability,
                previousWithdrawal, surplusReinvested,
                withdrawalResult.fromTaxable(), withdrawalResult.fromTraditional(),
                withdrawalResult.fromRoth(), withdrawalResult.taxSource());
    }

    private boolean hasMultipleAccountTypes(List<ProjectionAccountInput> accounts) {
        long distinctTypes = accounts.stream()
                .map(ProjectionAccountInput::accountType)
                .distinct()
                .count();
        boolean hasNonTaxable = accounts.stream()
                .anyMatch(a -> !"taxable".equals(a.accountType()));
        return distinctTypes > 1 || hasNonTaxable;
    }

    private BigDecimal sumInitialBalances(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .map(ProjectionAccountInput::initialBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumContributions(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .map(ProjectionAccountInput::annualContribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private WithdrawalStrategy resolveStrategy(ScenarioParams params, BigDecimal withdrawalRate) {
        if (params.withdrawalStrategy == null || params.withdrawalStrategy.isBlank()) {
            return new FixedPercentageWithdrawal(withdrawalRate);
        }
        return switch (params.withdrawalStrategy) {
            case "dynamic_percentage" -> new DynamicPercentageWithdrawal(withdrawalRate);
            case "vanguard_dynamic_spending" -> {
                BigDecimal ceiling = params.dynamicCeiling != null
                        ? params.dynamicCeiling : new BigDecimal("0.05");
                BigDecimal floor = params.dynamicFloor != null
                        ? params.dynamicFloor : new BigDecimal("-0.025");
                yield new VanguardDynamicSpendingWithdrawal(withdrawalRate, ceiling, floor);
            }
            default -> new FixedPercentageWithdrawal(withdrawalRate);
        };
    }

    private BigDecimal computeWeightedReturn(List<ProjectionAccountInput> accounts, BigDecimal totalBalance) {
        if (totalBalance.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (var account : accounts) {
            weightedSum = weightedSum.add(
                    account.initialBalance().multiply(account.expectedReturn()));
        }
        return weightedSum.divide(totalBalance, SCALE + 4, ROUNDING);
    }

    private ScenarioParams parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return defaultParams();
        }
        try {
            JsonNode node = objectMapper.readTree(paramsJson);
            WithdrawalOrder withdrawalOrder = node.has("withdrawal_order")
                    ? WithdrawalOrder.fromString(node.get("withdrawal_order").asText())
                    : WithdrawalOrder.TAXABLE_FIRST;
            return new ScenarioParams(
                    parseOptionalInt(node, "birth_year"),
                    parseOptionalBigDecimal(node, "withdrawal_rate"),
                    parseOptionalString(node, "withdrawal_strategy"),
                    parseOptionalBigDecimal(node, "dynamic_ceiling"),
                    parseOptionalBigDecimal(node, "dynamic_floor"),
                    parseOptionalString(node, "filing_status"),
                    parseOptionalBigDecimal(node, "other_income"),
                    parseOptionalBigDecimal(node, "annual_roth_conversion"),
                    withdrawalOrder,
                    parseOptionalString(node, "roth_conversion_strategy"),
                    parseOptionalBigDecimal(node, "target_bracket_rate"),
                    parseOptionalInt(node, "roth_conversion_start_year"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NumberFormatException e) {
            log.warn("Failed to parse params_json", e);
            return defaultParams();
        }
    }

    private ScenarioParams defaultParams() {
        return new ScenarioParams(null, null, null, null, null, null, null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null, null);
    }

    private BigDecimal parseOptionalBigDecimal(JsonNode node, String fieldName) {
        return node.has(fieldName) ? new BigDecimal(node.get(fieldName).asText()) : null;
    }

    private Integer parseOptionalInt(JsonNode node, String fieldName) {
        return node.has(fieldName) ? node.get(fieldName).asInt() : null;
    }

    private String parseOptionalString(JsonNode node, String fieldName) {
        return node.has(fieldName) ? node.get(fieldName).asText() : null;
    }

    private record ScenarioParams(
            Integer birthYear,
            BigDecimal withdrawalRate,
            String withdrawalStrategy,
            BigDecimal dynamicCeiling,
            BigDecimal dynamicFloor,
            String filingStatus,
            BigDecimal otherIncome,
            BigDecimal annualRothConversion,
            WithdrawalOrder withdrawalOrder,
            String rothConversionStrategy,
            BigDecimal targetBracketRate,
            Integer rothConversionStartYear) {
    }


    private BigDecimal getDecimal(JsonNode item, String camelCase, String snakeCase, BigDecimal fallback) {
        if (item.has(camelCase) && !item.get(camelCase).isNull()) {
            return new BigDecimal(item.get(camelCase).asText());
        } else if (item.has(snakeCase) && !item.get(snakeCase).isNull()) {
            return new BigDecimal(item.get(snakeCase).asText());
        }
        return fallback;
    }

    private int getInt(JsonNode item, String camelCase, String snakeCase, int fallback) {
        if (item.has(camelCase) && !item.get(camelCase).isNull()) {
            return item.get(camelCase).asInt();
        } else if (item.has(snakeCase) && !item.get(snakeCase).isNull()) {
            return item.get(snakeCase).asInt();
        }
        return fallback;
    }

    private Integer getOptionalInt(JsonNode item, String camelCase, String snakeCase) {
        if (item.has(camelCase) && !item.get(camelCase).isNull()) {
            return item.get(camelCase).asInt();
        } else if (item.has(snakeCase) && !item.get(snakeCase).isNull()) {
            return item.get(snakeCase).asInt();
        }
        return null;
    }

    private String getString(JsonNode item, String fieldName, String fallback) {
        if (item.has(fieldName) && !item.get(fieldName).isNull()) {
            return item.get(fieldName).asText();
        }
        return fallback;
    }

    private TierBasedSpendingPlan parseTierBasedPlan(@Nullable SpendingProfileInput profile) {
        if (profile == null) {
            return null;
        }
        List<TierBasedSpendingPlan.SpendingTierData> tiers = List.of();
        try {
            if (profile.spendingTiers() != null && !profile.spendingTiers().isBlank()
                    && !"[]".equals(profile.spendingTiers().trim())) {
                var tierNode = objectMapper.readTree(profile.spendingTiers());
                var tierList = new ArrayList<TierBasedSpendingPlan.SpendingTierData>();
                for (var item : tierNode) {
                    var essExp = getDecimal(item, "essentialExpenses", "essential_expenses", BigDecimal.ZERO);
                    var discExp = getDecimal(item, "discretionaryExpenses", "discretionary_expenses", BigDecimal.ZERO);
                    int startAge = getInt(item, "startAge", "start_age", 0);
                    Integer endAge = getOptionalInt(item, "endAge", "end_age");
                    tierList.add(new TierBasedSpendingPlan.SpendingTierData(
                            getString(item, "name", ""),
                            startAge, endAge, essExp, discExp));
                }
                tiers = tierList;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to parse spending_tiers", e);
        }

        return TierBasedSpendingPlan.of(profile.essentialExpenses(), profile.discretionaryExpenses(), tiers);
    }

    private SpendingFeasibilitySummary computeFeasibility(List<ProjectionYearDto> yearlyData,
                                                           SpendingPlan spendingPlan,
                                                           BigDecimal inflationRate) {
        if (spendingPlan == null) {
            return null;
        }

        Integer firstShortfallYear = null;
        Integer firstShortfallAge = null;
        BigDecimal minRealSurplus = null;
        BigDecimal sustainableForWeakest = null;
        BigDecimal requiredForWeakest = null;

        int retiredYearIndex = 0;
        for (var year : yearlyData) {
            if (!year.retired()) {
                continue;
            }
            retiredYearIndex++;

            if (year.spendingSurplus() != null && year.spendingSurplus().compareTo(SHORTFALL_TOLERANCE) < 0
                    && firstShortfallYear == null) {
                firstShortfallYear = year.year();
                firstShortfallAge = year.age();
            }

            BigDecimal availableNominal = year.withdrawals();
            if (year.incomeStreamsTotal() != null) {
                availableNominal = availableNominal.add(year.incomeStreamsTotal());
            }

            BigDecimal nominalRequired = BigDecimal.ZERO;
            if (year.essentialExpenses() != null) {
                nominalRequired = nominalRequired.add(year.essentialExpenses());
            }
            if (year.discretionaryExpenses() != null) {
                nominalRequired = nominalRequired.add(year.discretionaryExpenses());
            }

            BigDecimal expenseInflationFactor = retiredYearIndex > 1
                    ? BigDecimal.ONE.add(inflationRate).pow(retiredYearIndex - 1)
                    : BigDecimal.ONE;

            BigDecimal realAvailable = expenseInflationFactor.compareTo(BigDecimal.ZERO) > 0
                    ? availableNominal.divide(expenseInflationFactor, SCALE, ROUNDING)
                    : availableNominal;

            BigDecimal realRequired = expenseInflationFactor.compareTo(BigDecimal.ZERO) > 0
                    ? nominalRequired.divide(expenseInflationFactor, SCALE, ROUNDING)
                    : nominalRequired;

            BigDecimal realSurplus = realAvailable.subtract(realRequired);

            if (minRealSurplus == null || realSurplus.compareTo(minRealSurplus) < 0) {
                minRealSurplus = realSurplus;
                sustainableForWeakest = realAvailable;
                requiredForWeakest = realRequired;
            }
        }

        if (sustainableForWeakest == null) {
            return new SpendingFeasibilitySummary(true, null, null, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        boolean feasible = firstShortfallYear == null;
        return new SpendingFeasibilitySummary(feasible, firstShortfallYear, firstShortfallAge,
                sustainableForWeakest, requiredForWeakest);
    }

    private ProjectionYearDto applyViability(ProjectionYearDto base, SpendingPlan spendingPlan,
                                              int year, int age, int yearsInRetirement,
                                              BigDecimal inflationRate, BigDecimal activeIncome) {
        if (spendingPlan == null || !base.retired()) {
            return base;
        }

        var resolved = spendingPlan.resolveYear(year, age, yearsInRetirement, inflationRate, activeIncome);
        BigDecimal essential = resolved.essential();
        BigDecimal discretionary = resolved.discretionary();

        BigDecimal netNeed = essential.add(discretionary).subtract(activeIncome).max(BigDecimal.ZERO);
        BigDecimal surplus = base.withdrawals().subtract(netNeed);

        BigDecimal discAfterCuts;
        if (surplus.compareTo(BigDecimal.ZERO) < 0) {
            discAfterCuts = discretionary.add(surplus).max(BigDecimal.ZERO);
        } else {
            discAfterCuts = discretionary;
        }

        return new ProjectionYearDto(
                base.year(), base.age(), base.startBalance(), base.contributions(),
                base.growth(), base.withdrawals(), base.endBalance(), base.retired(),
                base.traditionalBalance(), base.rothBalance(), base.taxableBalance(),
                base.rothConversionAmount(), base.taxLiability(),
                essential, discretionary, activeIncome, netNeed, surplus, discAfterCuts,
                base.rentalIncomeGross(), base.rentalExpensesTotal(), base.depreciationTotal(),
                base.rentalLossApplied(), base.suspendedLossCarryforward(),
                base.socialSecurityTaxable(), base.selfEmploymentTax(),
                base.incomeBySource(),
                base.propertyEquity(), base.totalNetWorth(), base.surplusReinvested(),
                base.taxableGrowth(), base.traditionalGrowth(), base.rothGrowth(),
                base.taxPaidFromTaxable(), base.taxPaidFromTraditional(), base.taxPaidFromRoth(),
                base.withdrawalFromTaxable(), base.withdrawalFromTraditional(), base.withdrawalFromRoth(),
                base.rentalPropertyDetails());
    }

    private ProjectionYearDto applyIncomeSourceFields(ProjectionYearDto base, IncomeSourceProcessor.IncomeSourceYearResult isResult) {
        if (isResult == null) {
            return base;
        }

        BigDecimal totalIncome = isResult.totalCashInflow();

        var incomeBySource = isResult.incomeBySource().isEmpty() ? null : isResult.incomeBySource();

        return new ProjectionYearDto(
                base.year(), base.age(), base.startBalance(), base.contributions(),
                base.growth(), base.withdrawals(), base.endBalance(), base.retired(),
                base.traditionalBalance(), base.rothBalance(), base.taxableBalance(),
                base.rothConversionAmount(), base.taxLiability(),
                base.essentialExpenses(), base.discretionaryExpenses(),
                positiveOrDefault(totalIncome, base.incomeStreamsTotal()),
                base.netSpendingNeed(), base.spendingSurplus(), base.discretionaryAfterCuts(),
                nullIfZero(isResult.rentalIncomeGross()),
                nullIfZero(isResult.rentalExpensesTotal()),
                nullIfZero(isResult.depreciationTotal()),
                nullIfZero(isResult.rentalLossApplied()),
                nullIfZero(isResult.suspendedLossCarryforward()),
                nullIfZero(isResult.socialSecurityTaxable()),
                nullIfZero(isResult.selfEmploymentTax()),
                incomeBySource,
                base.propertyEquity(), base.totalNetWorth(), base.surplusReinvested(),
                base.taxableGrowth(), base.traditionalGrowth(), base.rothGrowth(),
                base.taxPaidFromTaxable(), base.taxPaidFromTraditional(), base.taxPaidFromRoth(),
                base.withdrawalFromTaxable(), base.withdrawalFromTraditional(), base.withdrawalFromRoth(),
                isResult.rentalPropertyDetails().isEmpty() ? null : isResult.rentalPropertyDetails());
    }

    private ProjectionYearDto applySurplusReinvested(ProjectionYearDto base, BigDecimal surplusReinvested) {
        return new ProjectionYearDto(
                base.year(), base.age(), base.startBalance(), base.contributions(),
                base.growth(), base.withdrawals(), base.endBalance(), base.retired(),
                base.traditionalBalance(), base.rothBalance(), base.taxableBalance(),
                base.rothConversionAmount(), base.taxLiability(),
                base.essentialExpenses(), base.discretionaryExpenses(),
                base.incomeStreamsTotal(), base.netSpendingNeed(), base.spendingSurplus(),
                base.discretionaryAfterCuts(),
                base.rentalIncomeGross(), base.rentalExpensesTotal(), base.depreciationTotal(),
                base.rentalLossApplied(), base.suspendedLossCarryforward(),
                base.socialSecurityTaxable(), base.selfEmploymentTax(),
                base.incomeBySource(),
                base.propertyEquity(), base.totalNetWorth(), surplusReinvested,
                base.taxableGrowth(), base.traditionalGrowth(), base.rothGrowth(),
                base.taxPaidFromTaxable(), base.taxPaidFromTraditional(), base.taxPaidFromRoth(),
                base.withdrawalFromTaxable(), base.withdrawalFromTraditional(), base.withdrawalFromRoth(),
                base.rentalPropertyDetails());
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
                java.time.LocalDate asOf = prop.loanStartDate()
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
        return new ProjectionYearDto(
                base.year(), base.age(), base.startBalance(), base.contributions(),
                base.growth(), base.withdrawals(), base.endBalance(), base.retired(),
                base.traditionalBalance(), base.rothBalance(), base.taxableBalance(),
                base.rothConversionAmount(), base.taxLiability(),
                base.essentialExpenses(), base.discretionaryExpenses(),
                base.incomeStreamsTotal(), base.netSpendingNeed(), base.spendingSurplus(),
                base.discretionaryAfterCuts(),
                base.rentalIncomeGross(), base.rentalExpensesTotal(), base.depreciationTotal(),
                base.rentalLossApplied(), base.suspendedLossCarryforward(),
                base.socialSecurityTaxable(), base.selfEmploymentTax(),
                base.incomeBySource(),
                propertyEquity, totalNetWorth, base.surplusReinvested(),
                base.taxableGrowth(), base.traditionalGrowth(), base.rothGrowth(),
                base.taxPaidFromTaxable(), base.taxPaidFromTraditional(), base.taxPaidFromRoth(),
                base.withdrawalFromTaxable(), base.withdrawalFromTraditional(), base.withdrawalFromRoth(),
                base.rentalPropertyDetails());
    }

}
