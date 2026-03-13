package com.wealthview.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.projection.ProjectionEngine;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.SpendingFeasibilitySummary;
import com.wealthview.core.projection.dto.SpendingProfileInput;
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
public class DeterministicProjectionEngine implements ProjectionEngine {

    private static final Logger log = LoggerFactory.getLogger(DeterministicProjectionEngine.class);
    private static final BigDecimal DEFAULT_WITHDRAWAL_RATE = new BigDecimal("0.04");
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
        SpendingData spendingData = loadSpendingData(input.spendingProfile());
        var incomeSources = input.incomeSources() != null ? input.incomeSources() : List.<ProjectionIncomeSourceInput>of();

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
                retirementYear, endYear, inflationRate, spendingData, incomeSources);
    }

    private ProjectionResultResponse runProjection(
            ProjectionInput input,
            PoolStrategy pool,
            WithdrawalStrategy strategy,
            int currentYear, int birthYear, int retirementYear, int endYear,
            BigDecimal inflationRate, SpendingData spendingData,
            List<ProjectionIncomeSourceInput> incomeSources) {

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

            BigDecimal totalGrowth = pool.applyGrowth();

            if (retired) {
                yearsInRetirement++;
            }

            var incomeResult = processIncomeAndConversions(
                    pool, incomeSources, age, yearsInRetirement, year, suspendedLoss);
            suspendedLoss = incomeResult.suspendedLoss();
            BigDecimal conversionAmount = incomeResult.conversionAmount();
            BigDecimal taxLiability = incomeResult.taxLiability();

            if (retired) {
                var retirementResult = processRetirementWithdrawals(
                        pool, strategy, spendingData, age, yearsInRetirement, inflationRate,
                        incomeResult.totalActiveIncome(), startBalance, previousWithdrawal,
                        incomeResult.effectiveOtherIncome(), conversionAmount, year,
                        incomeResult.isResult());
                withdrawals = retirementResult.withdrawals();
                taxLiability = taxLiability.add(retirementResult.taxLiability());
                previousWithdrawal = retirementResult.previousWithdrawal();
            }

            pool.floorAtZero();

            var yearDto = pool.buildYearDto(year, age, startBalance, contributions,
                    totalGrowth, withdrawals, retired, conversionAmount, taxLiability);
            yearDto = applyViability(yearDto, spendingData, age, yearsInRetirement, inflationRate, incomeSources);
            if (incomeResult.isResult() != null) {
                yearDto = applyIncomeSourceFields(yearDto, incomeResult.isResult());
            }
            yearlyData.add(yearDto);
        }

        BigDecimal finalBalance = yearlyData.isEmpty()
                ? pool.getTotal()
                : yearlyData.getLast().endBalance();

        log.info("{} for scenario '{}': {} years, final balance {}",
                pool.logTag(), input.scenarioName(), yearlyData.size(), finalBalance);

        var feasibility = computeFeasibility(yearlyData, spendingData, inflationRate);
        return new ProjectionResultResponse(input.scenarioId(), yearlyData, finalBalance,
                yearsInRetirement, feasibility);
    }

    private record IncomeAndConversionResult(
            IncomeSourceProcessor.IncomeSourceYearResult isResult,
            BigDecimal totalActiveIncome,
            BigDecimal effectiveOtherIncome,
            BigDecimal conversionAmount,
            BigDecimal taxLiability,
            BigDecimal suspendedLoss) {
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
                conversion.amountConverted(), conversion.taxLiability(), suspendedLoss);
    }

    private record RetirementWithdrawalResult(
            BigDecimal withdrawals,
            BigDecimal taxLiability,
            BigDecimal previousWithdrawal) {
    }

    @SuppressWarnings("PMD.ExcessiveParameterList")
    private RetirementWithdrawalResult processRetirementWithdrawals(
            PoolStrategy pool, WithdrawalStrategy strategy, SpendingData spendingData,
            int age, int yearsInRetirement, BigDecimal inflationRate,
            BigDecimal totalActiveIncome, BigDecimal startBalance, BigDecimal previousWithdrawal,
            BigDecimal effectiveOtherIncome, BigDecimal conversionAmount, int year,
            IncomeSourceProcessor.IncomeSourceYearResult isResult) {

        BigDecimal aggBalance = pool.getTotal();
        BigDecimal portfolioNeed;

        if (spendingData != null) {
            BigDecimal spendingNeed = computeSpendingNeed(spendingData, age, yearsInRetirement, inflationRate);
            portfolioNeed = spendingNeed.subtract(totalActiveIncome).max(BigDecimal.ZERO).min(aggBalance);
            previousWithdrawal = portfolioNeed.add(totalActiveIncome);
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

        return new RetirementWithdrawalResult(withdrawalResult.totalWithdrawn(), taxLiability, previousWithdrawal);
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
            return new ScenarioParams(null, null, null, null, null, null, null, null,
                    WithdrawalOrder.TAXABLE_FIRST, null, null, null);
        }
        try {
            JsonNode node = objectMapper.readTree(paramsJson);
            Integer birthYear = node.has("birth_year") ? node.get("birth_year").asInt() : null;
            BigDecimal withdrawalRate = node.has("withdrawal_rate")
                    ? new BigDecimal(node.get("withdrawal_rate").asText())
                    : null;
            String withdrawalStrategy = node.has("withdrawal_strategy")
                    ? node.get("withdrawal_strategy").asText()
                    : null;
            BigDecimal dynamicCeiling = node.has("dynamic_ceiling")
                    ? new BigDecimal(node.get("dynamic_ceiling").asText())
                    : null;
            BigDecimal dynamicFloor = node.has("dynamic_floor")
                    ? new BigDecimal(node.get("dynamic_floor").asText())
                    : null;
            String filingStatus = node.has("filing_status")
                    ? node.get("filing_status").asText()
                    : null;
            BigDecimal otherIncome = node.has("other_income")
                    ? new BigDecimal(node.get("other_income").asText())
                    : null;
            BigDecimal annualRothConversion = node.has("annual_roth_conversion")
                    ? new BigDecimal(node.get("annual_roth_conversion").asText())
                    : null;
            WithdrawalOrder withdrawalOrder = node.has("withdrawal_order")
                    ? WithdrawalOrder.fromString(node.get("withdrawal_order").asText())
                    : WithdrawalOrder.TAXABLE_FIRST;
            String rothConversionStrategy = node.has("roth_conversion_strategy")
                    ? node.get("roth_conversion_strategy").asText()
                    : null;
            BigDecimal targetBracketRate = node.has("target_bracket_rate")
                    ? new BigDecimal(node.get("target_bracket_rate").asText())
                    : null;
            Integer rothConversionStartYear = node.has("roth_conversion_start_year")
                    ? node.get("roth_conversion_start_year").asInt() : null;
            return new ScenarioParams(birthYear, withdrawalRate, withdrawalStrategy,
                    dynamicCeiling, dynamicFloor, filingStatus, otherIncome, annualRothConversion,
                    withdrawalOrder, rothConversionStrategy, targetBracketRate,
                    rothConversionStartYear);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NumberFormatException e) {
            log.warn("Failed to parse params_json: {}", e.getMessage());
            return new ScenarioParams(null, null, null, null, null, null, null, null,
                    WithdrawalOrder.TAXABLE_FIRST, null, null, null);
        }
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

    private record SpendingTierData(
            String name,
            int startAge,
            Integer endAge,
            BigDecimal essentialExpenses,
            BigDecimal discretionaryExpenses) {
    }

    private record SpendingData(
            BigDecimal essentialExpenses,
            BigDecimal discretionaryExpenses,
            List<SpendingTierData> spendingTiers) {
    }

    private record ResolvedSpending(BigDecimal essential, BigDecimal discretionary) {}

    private ResolvedSpending resolveSpending(SpendingData spending, int age) {
        if (spending.spendingTiers() != null && !spending.spendingTiers().isEmpty()) {
            for (var tier : spending.spendingTiers()) {
                if (age >= tier.startAge() && (tier.endAge() == null || age < tier.endAge())) {
                    return new ResolvedSpending(tier.essentialExpenses(), tier.discretionaryExpenses());
                }
            }
        }
        return new ResolvedSpending(spending.essentialExpenses(), spending.discretionaryExpenses());
    }

    private int computeYearsInTier(SpendingData spending, int age, int yearsInRetirement) {
        if (spending.spendingTiers() != null && !spending.spendingTiers().isEmpty()) {
            for (var tier : spending.spendingTiers()) {
                if (age >= tier.startAge() && (tier.endAge() == null || age < tier.endAge())) {
                    int retirementStartAge = age - yearsInRetirement + 1;
                    int effectiveTierStart = Math.max(tier.startAge(), retirementStartAge);
                    return age - effectiveTierStart;
                }
            }
        }
        return -1;
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

    private SpendingData loadSpendingData(@Nullable SpendingProfileInput profile) {
        if (profile == null) {
            return null;
        }
        List<SpendingTierData> tiers = List.of();
        try {
            if (profile.spendingTiers() != null && !profile.spendingTiers().isBlank()
                    && !"[]".equals(profile.spendingTiers().trim())) {
                var tierNode = objectMapper.readTree(profile.spendingTiers());
                var tierList = new ArrayList<SpendingTierData>();
                for (var item : tierNode) {
                    var essExp = getDecimal(item, "essentialExpenses", "essential_expenses", BigDecimal.ZERO);
                    var discExp = getDecimal(item, "discretionaryExpenses", "discretionary_expenses", BigDecimal.ZERO);
                    int startAge = getInt(item, "startAge", "start_age", 0);
                    Integer endAge = getOptionalInt(item, "endAge", "end_age");
                    tierList.add(new SpendingTierData(
                            getString(item, "name", ""),
                            startAge, endAge, essExp, discExp));
                }
                tiers = tierList;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to parse spending_tiers: {}", e.getMessage());
        }

        return new SpendingData(profile.essentialExpenses(), profile.discretionaryExpenses(), tiers);
    }

    private SpendingFeasibilitySummary computeFeasibility(List<ProjectionYearDto> yearlyData,
                                                           SpendingData spendingData,
                                                           BigDecimal inflationRate) {
        if (spendingData == null) {
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

            if (year.spendingSurplus() != null && year.spendingSurplus().compareTo(BigDecimal.ZERO) < 0
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

    private ProjectionYearDto applyViability(ProjectionYearDto base, SpendingData spending,
                                              int age, int yearsInRetirement, BigDecimal inflationRate,
                                              List<ProjectionIncomeSourceInput> incomeSources) {
        if (spending == null || !base.retired()) {
            return base;
        }

        var resolved = resolveSpending(spending, age);
        BigDecimal inflationFactor = computeInflationFactor(spending, age, yearsInRetirement, inflationRate);

        BigDecimal essential = resolved.essential().multiply(inflationFactor).setScale(SCALE, ROUNDING);
        BigDecimal discretionary = resolved.discretionary().multiply(inflationFactor).setScale(SCALE, ROUNDING);

        BigDecimal activeIncome = incomeContributionCalculator.compute(incomeSources, age, yearsInRetirement);

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
                base.socialSecurityTaxable(), base.selfEmploymentTax());
    }

    private ProjectionYearDto applyIncomeSourceFields(ProjectionYearDto base, IncomeSourceProcessor.IncomeSourceYearResult isResult) {
        if (isResult == null) {
            return base;
        }

        BigDecimal totalIncome = isResult.totalCashInflow();

        return new ProjectionYearDto(
                base.year(), base.age(), base.startBalance(), base.contributions(),
                base.growth(), base.withdrawals(), base.endBalance(), base.retired(),
                base.traditionalBalance(), base.rothBalance(), base.taxableBalance(),
                base.rothConversionAmount(), base.taxLiability(),
                base.essentialExpenses(), base.discretionaryExpenses(),
                totalIncome.compareTo(BigDecimal.ZERO) > 0 ? totalIncome : base.incomeStreamsTotal(),
                base.netSpendingNeed(), base.spendingSurplus(), base.discretionaryAfterCuts(),
                isResult.rentalIncomeGross().compareTo(BigDecimal.ZERO) > 0 ? isResult.rentalIncomeGross() : null,
                isResult.rentalExpensesTotal().compareTo(BigDecimal.ZERO) > 0 ? isResult.rentalExpensesTotal() : null,
                isResult.depreciationTotal().compareTo(BigDecimal.ZERO) > 0 ? isResult.depreciationTotal() : null,
                isResult.rentalLossApplied().compareTo(BigDecimal.ZERO) > 0 ? isResult.rentalLossApplied() : null,
                isResult.suspendedLossCarryforward().compareTo(BigDecimal.ZERO) > 0 ? isResult.suspendedLossCarryforward() : null,
                isResult.socialSecurityTaxable().compareTo(BigDecimal.ZERO) > 0 ? isResult.socialSecurityTaxable() : null,
                isResult.selfEmploymentTax().compareTo(BigDecimal.ZERO) > 0 ? isResult.selfEmploymentTax() : null);
    }

    private BigDecimal computeInflationFactor(SpendingData spending, int age,
                                               int yearsInRetirement, BigDecimal inflationRate) {
        int yearsInTier = computeYearsInTier(spending, age, yearsInRetirement);
        if (yearsInTier >= 0) {
            return yearsInTier > 0
                    ? BigDecimal.ONE.add(inflationRate).pow(yearsInTier)
                    : BigDecimal.ONE;
        }
        return yearsInRetirement > 1
                ? BigDecimal.ONE.add(inflationRate).pow(yearsInRetirement - 1)
                : BigDecimal.ONE;
    }

    private BigDecimal computeSpendingNeed(SpendingData spendingData, int age,
                                            int yearsInRetirement, BigDecimal inflationRate) {
        var resolved = resolveSpending(spendingData, age);
        BigDecimal inflationFactor = computeInflationFactor(spendingData, age, yearsInRetirement, inflationRate);
        BigDecimal essential = resolved.essential().multiply(inflationFactor).setScale(SCALE, ROUNDING);
        BigDecimal discretionary = resolved.discretionary().multiply(inflationFactor).setScale(SCALE, ROUNDING);
        return essential.add(discretionary);
    }

}
