package com.wealthview.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.projection.ProjectionEngine;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.strategy.DynamicPercentageWithdrawal;
import com.wealthview.core.projection.strategy.FixedPercentageWithdrawal;
import com.wealthview.core.projection.strategy.VanguardDynamicSpendingWithdrawal;
import com.wealthview.core.projection.strategy.WithdrawalContext;
import com.wealthview.core.projection.strategy.WithdrawalStrategy;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.SpendingProfileEntity;
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

    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator) {
        this.taxCalculator = taxCalculator;
    }

    @Override
    public ProjectionResultResponse run(ProjectionScenarioEntity scenario) {
        var accounts = scenario.getAccounts();
        var params = parseParams(scenario.getParamsJson());

        int currentYear = LocalDate.now().getYear();
        int birthYear = params.birthYear != null ? params.birthYear : currentYear - 35;
        int retirementYear = scenario.getRetirementDate() != null
                ? scenario.getRetirementDate().getYear()
                : currentYear + 30;
        int endAge = scenario.getEndAge() != null ? scenario.getEndAge() : 90;
        int endYear = birthYear + endAge;

        BigDecimal withdrawalRate = params.withdrawalRate != null
                ? params.withdrawalRate
                : DEFAULT_WITHDRAWAL_RATE;
        BigDecimal inflationRate = scenario.getInflationRate() != null
                ? scenario.getInflationRate()
                : BigDecimal.ZERO;

        WithdrawalStrategy strategy = resolveStrategy(params, withdrawalRate);
        SpendingData spendingData = loadSpendingData(scenario.getSpendingProfile());

        boolean hasMultiplePools = hasMultipleAccountTypes(accounts);

        if (hasMultiplePools) {
            return runWithPools(scenario, accounts, params, strategy, currentYear, birthYear,
                    retirementYear, endYear, inflationRate, spendingData);
        }

        return runSimple(scenario, accounts, strategy, currentYear, birthYear,
                retirementYear, endYear, inflationRate, spendingData);
    }

    private ProjectionResultResponse runSimple(
            ProjectionScenarioEntity scenario,
            List<ProjectionAccountEntity> accounts,
            WithdrawalStrategy strategy,
            int currentYear, int birthYear, int retirementYear, int endYear,
            BigDecimal inflationRate, SpendingData spendingData) {

        BigDecimal balance = sumInitialBalances(accounts);
        BigDecimal totalContributions = sumContributions(accounts);
        BigDecimal weightedReturn = computeWeightedReturn(accounts, balance);

        var yearlyData = new ArrayList<ProjectionYearDto>();
        int yearsInRetirement = 0;
        BigDecimal previousWithdrawal = BigDecimal.ZERO;

        for (int year = currentYear; year < endYear; year++) {
            int age = year - birthYear;
            boolean retired = year >= retirementYear;
            BigDecimal startBalance = balance;

            BigDecimal contributions = BigDecimal.ZERO;
            BigDecimal growth;
            BigDecimal withdrawals = BigDecimal.ZERO;

            if (!retired) {
                contributions = totalContributions;
                balance = balance.add(contributions);
            }

            growth = balance.multiply(weightedReturn).setScale(SCALE, ROUNDING);
            balance = balance.add(growth);

            if (retired) {
                yearsInRetirement++;
                var ctx = new WithdrawalContext(
                        balance, startBalance, previousWithdrawal, weightedReturn,
                        inflationRate, yearsInRetirement);
                withdrawals = strategy.computeWithdrawal(ctx);

                if (withdrawals.compareTo(balance) > 0) {
                    withdrawals = balance;
                }
                balance = balance.subtract(withdrawals);
                previousWithdrawal = withdrawals;
            }

            if (balance.compareTo(BigDecimal.ZERO) < 0) {
                balance = BigDecimal.ZERO;
            }

            var yearDto = ProjectionYearDto.simple(
                    year, age, startBalance, contributions, growth, withdrawals, balance, retired);
            yearlyData.add(applyViability(yearDto, spendingData, age, yearsInRetirement, inflationRate));
        }

        BigDecimal finalBalance = yearlyData.isEmpty()
                ? balance
                : yearlyData.getLast().endBalance();

        log.info("Projection for scenario '{}': {} years, final balance {}",
                scenario.getName(), yearlyData.size(), finalBalance);

        return new ProjectionResultResponse(scenario.getId(), yearlyData, finalBalance, yearsInRetirement);
    }

    private ProjectionResultResponse runWithPools(
            ProjectionScenarioEntity scenario,
            List<ProjectionAccountEntity> accounts,
            ScenarioParams params,
            WithdrawalStrategy strategy,
            int currentYear, int birthYear, int retirementYear, int endYear,
            BigDecimal inflationRate, SpendingData spendingData) {

        Map<String, List<ProjectionAccountEntity>> grouped = accounts.stream()
                .collect(Collectors.groupingBy(ProjectionAccountEntity::getAccountType));

        var balances = new PoolBalances(
                sumInitialBalances(grouped.getOrDefault("taxable", List.of())),
                sumInitialBalances(grouped.getOrDefault("traditional", List.of())),
                sumInitialBalances(grouped.getOrDefault("roth", List.of())));

        BigDecimal tradContrib = sumContributions(grouped.getOrDefault("traditional", List.of()));
        BigDecimal rothContrib = sumContributions(grouped.getOrDefault("roth", List.of()));
        BigDecimal taxableContrib = sumContributions(grouped.getOrDefault("taxable", List.of()));

        BigDecimal weightedReturn = computeWeightedReturn(accounts, balances.total());

        FilingStatus filingStatus = params.filingStatus != null
                ? FilingStatus.fromString(params.filingStatus) : FilingStatus.SINGLE;
        BigDecimal otherIncome = params.otherIncome != null ? params.otherIncome : BigDecimal.ZERO;
        BigDecimal annualRothConversion = params.annualRothConversion != null
                ? params.annualRothConversion : BigDecimal.ZERO;

        var yearlyData = new ArrayList<ProjectionYearDto>();
        int yearsInRetirement = 0;
        BigDecimal previousWithdrawal = BigDecimal.ZERO;

        for (int year = currentYear; year < endYear; year++) {
            int age = year - birthYear;
            boolean retired = year >= retirementYear;
            BigDecimal startAgg = balances.total();

            BigDecimal contributions = BigDecimal.ZERO;
            BigDecimal withdrawals = BigDecimal.ZERO;
            BigDecimal conversionAmount = BigDecimal.ZERO;
            BigDecimal taxLiability = BigDecimal.ZERO;

            if (!retired) {
                contributions = applyContributions(balances, tradContrib, rothContrib, taxableContrib);
            }

            BigDecimal totalGrowth = applyGrowth(balances, weightedReturn);

            var conversion = executeRothConversion(
                    balances, annualRothConversion, year, filingStatus, otherIncome);
            conversionAmount = conversion.amountConverted();
            taxLiability = conversion.taxLiability();

            if (retired) {
                yearsInRetirement++;
                BigDecimal aggBalance = balances.total();
                var ctx = new WithdrawalContext(
                        aggBalance, startAgg, previousWithdrawal, weightedReturn,
                        inflationRate, yearsInRetirement);
                withdrawals = strategy.computeWithdrawal(ctx);
                if (withdrawals.compareTo(aggBalance) > 0) {
                    withdrawals = aggBalance;
                }

                var withdrawalResult = executeWithdrawals(
                        balances, withdrawals, year, filingStatus, otherIncome, conversionAmount);
                withdrawals = withdrawalResult.totalWithdrawn();
                taxLiability = taxLiability.add(withdrawalResult.taxLiability());

                previousWithdrawal = withdrawals;
            }

            balances.floorAtZero();

            BigDecimal endAgg = balances.total();

            var yearDto = new ProjectionYearDto(
                    year, age, startAgg, contributions, totalGrowth, withdrawals, endAgg, retired,
                    balances.traditional, balances.roth, balances.taxable,
                    conversionAmount.compareTo(BigDecimal.ZERO) > 0 ? conversionAmount : null,
                    taxLiability.compareTo(BigDecimal.ZERO) > 0 ? taxLiability : null,
                    null, null, null, null, null, null);
            yearlyData.add(applyViability(yearDto, spendingData, age, yearsInRetirement, inflationRate));
        }

        BigDecimal finalBalance = yearlyData.isEmpty()
                ? balances.total()
                : yearlyData.getLast().endBalance();

        log.info("Projection with pools for scenario '{}': {} years, final balance {}",
                scenario.getName(), yearlyData.size(), finalBalance);

        return new ProjectionResultResponse(scenario.getId(), yearlyData, finalBalance, yearsInRetirement);
    }

    private boolean hasMultipleAccountTypes(List<ProjectionAccountEntity> accounts) {
        long distinctTypes = accounts.stream()
                .map(ProjectionAccountEntity::getAccountType)
                .distinct()
                .count();
        boolean hasNonTaxable = accounts.stream()
                .anyMatch(a -> !"taxable".equals(a.getAccountType()));
        return distinctTypes > 1 || hasNonTaxable;
    }

    private BigDecimal sumInitialBalances(List<ProjectionAccountEntity> accounts) {
        return accounts.stream()
                .map(ProjectionAccountEntity::getInitialBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumContributions(List<ProjectionAccountEntity> accounts) {
        return accounts.stream()
                .map(ProjectionAccountEntity::getAnnualContribution)
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

    private BigDecimal computeWeightedReturn(List<ProjectionAccountEntity> accounts, BigDecimal totalBalance) {
        if (totalBalance.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (var account : accounts) {
            weightedSum = weightedSum.add(
                    account.getInitialBalance().multiply(account.getExpectedReturn()));
        }
        return weightedSum.divide(totalBalance, SCALE + 4, ROUNDING);
    }

    private ScenarioParams parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return new ScenarioParams(null, null, null, null, null, null, null, null);
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
            return new ScenarioParams(birthYear, withdrawalRate, withdrawalStrategy,
                    dynamicCeiling, dynamicFloor, filingStatus, otherIncome, annualRothConversion);
        } catch (Exception e) {
            log.warn("Failed to parse params_json: {}", e.getMessage());
            return new ScenarioParams(null, null, null, null, null, null, null, null);
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
            BigDecimal annualRothConversion) {
    }

    private static class PoolBalances {
        BigDecimal taxable;
        BigDecimal traditional;
        BigDecimal roth;

        PoolBalances(BigDecimal taxable, BigDecimal traditional, BigDecimal roth) {
            this.taxable = taxable;
            this.traditional = traditional;
            this.roth = roth;
        }

        BigDecimal total() {
            return taxable.add(traditional).add(roth);
        }

        void floorAtZero() {
            taxable = taxable.max(BigDecimal.ZERO);
            traditional = traditional.max(BigDecimal.ZERO);
            roth = roth.max(BigDecimal.ZERO);
        }
    }

    private record IncomeStreamData(
            String name,
            BigDecimal annualAmount,
            int startAge,
            Integer endAge) {
    }

    private record SpendingData(
            BigDecimal essentialExpenses,
            BigDecimal discretionaryExpenses,
            List<IncomeStreamData> incomeStreams) {
    }

    private BigDecimal applyContributions(PoolBalances balances,
                                          BigDecimal tradContrib, BigDecimal rothContrib, BigDecimal taxableContrib) {
        balances.traditional = balances.traditional.add(tradContrib);
        balances.roth = balances.roth.add(rothContrib);
        balances.taxable = balances.taxable.add(taxableContrib);
        return tradContrib.add(rothContrib).add(taxableContrib);
    }

    private BigDecimal applyGrowth(PoolBalances balances, BigDecimal weightedReturn) {
        BigDecimal tradGrowth = balances.traditional.multiply(weightedReturn).setScale(SCALE, ROUNDING);
        BigDecimal rothGrowth = balances.roth.multiply(weightedReturn).setScale(SCALE, ROUNDING);
        BigDecimal taxableGrowth = balances.taxable.multiply(weightedReturn).setScale(SCALE, ROUNDING);
        balances.traditional = balances.traditional.add(tradGrowth);
        balances.roth = balances.roth.add(rothGrowth);
        balances.taxable = balances.taxable.add(taxableGrowth);
        return tradGrowth.add(rothGrowth).add(taxableGrowth);
    }

    private void deductFromPools(PoolBalances balances, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal remaining = amount;

        BigDecimal fromTaxable = remaining.min(balances.taxable);
        balances.taxable = balances.taxable.subtract(fromTaxable);
        remaining = remaining.subtract(fromTaxable);

        BigDecimal fromTraditional = remaining.min(balances.traditional);
        balances.traditional = balances.traditional.subtract(fromTraditional);
        remaining = remaining.subtract(fromTraditional);

        balances.roth = balances.roth.subtract(remaining);
    }

    private record ConversionResult(BigDecimal amountConverted, BigDecimal taxLiability) {}

    private ConversionResult executeRothConversion(PoolBalances balances, BigDecimal conversionLimit,
                                                    int year, FilingStatus filingStatus, BigDecimal otherIncome) {
        if (conversionLimit.compareTo(BigDecimal.ZERO) <= 0
                || balances.traditional.compareTo(BigDecimal.ZERO) <= 0) {
            return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal actual = conversionLimit.min(balances.traditional);
        balances.traditional = balances.traditional.subtract(actual);
        balances.roth = balances.roth.add(actual);

        if (taxCalculator != null) {
            BigDecimal taxableIncome = actual.add(otherIncome);
            BigDecimal tax = taxCalculator.computeTax(taxableIncome, year, filingStatus);
            deductFromPools(balances, tax);
            return new ConversionResult(actual, tax);
        }
        return new ConversionResult(actual, BigDecimal.ZERO);
    }

    private WithdrawalTaxResult executeWithdrawals(PoolBalances balances, BigDecimal totalNeed,
                                                    int year, FilingStatus filingStatus,
                                                    BigDecimal otherIncome, BigDecimal rothConversionAmount) {
        if (totalNeed.compareTo(BigDecimal.ZERO) <= 0) {
            return new WithdrawalTaxResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal remaining = totalNeed;

        BigDecimal fromTaxable = remaining.min(balances.taxable);
        balances.taxable = balances.taxable.subtract(fromTaxable);
        remaining = remaining.subtract(fromTaxable);

        BigDecimal fromTraditional = remaining.min(balances.traditional);
        balances.traditional = balances.traditional.subtract(fromTraditional);
        remaining = remaining.subtract(fromTraditional);

        BigDecimal withdrawalTax = BigDecimal.ZERO;
        if (fromTraditional.compareTo(BigDecimal.ZERO) > 0 && taxCalculator != null) {
            withdrawalTax = taxCalculator.computeTax(
                    fromTraditional.add(otherIncome), year, filingStatus);
            deductFromPools(balances, withdrawalTax);
        }

        BigDecimal fromRoth = remaining.min(balances.roth);
        balances.roth = balances.roth.subtract(fromRoth);

        return new WithdrawalTaxResult(
                fromTaxable.add(fromTraditional).add(fromRoth), withdrawalTax);
    }

    private record WithdrawalTaxResult(BigDecimal totalWithdrawn, BigDecimal taxLiability) {}

    private SpendingData loadSpendingData(SpendingProfileEntity profile) {
        if (profile == null) {
            return null;
        }
        List<IncomeStreamData> streams = List.of();
        try {
            if (profile.getIncomeStreams() != null && !profile.getIncomeStreams().isBlank()
                    && !"[]".equals(profile.getIncomeStreams().trim())) {
                var node = objectMapper.readTree(profile.getIncomeStreams());
                var list = new ArrayList<IncomeStreamData>();
                for (var item : node) {
                    BigDecimal amount = BigDecimal.ZERO;
                    if (item.has("annualAmount") && !item.get("annualAmount").isNull()) {
                        amount = new BigDecimal(item.get("annualAmount").asText());
                    } else if (item.has("annual_amount") && !item.get("annual_amount").isNull()) {
                        amount = new BigDecimal(item.get("annual_amount").asText());
                    }
                    int startAge = 0;
                    if (item.has("startAge") && !item.get("startAge").isNull()) {
                        startAge = item.get("startAge").asInt();
                    } else if (item.has("start_age") && !item.get("start_age").isNull()) {
                        startAge = item.get("start_age").asInt();
                    }
                    Integer endAge = null;
                    if (item.has("endAge") && !item.get("endAge").isNull()) {
                        endAge = item.get("endAge").asInt();
                    } else if (item.has("end_age") && !item.get("end_age").isNull()) {
                        endAge = item.get("end_age").asInt();
                    }
                    list.add(new IncomeStreamData(
                            item.has("name") ? item.get("name").asText() : "",
                            amount, startAge, endAge));
                }
                streams = list;
            }
        } catch (Exception e) {
            log.warn("Failed to parse income_streams: {}", e.getMessage());
        }
        return new SpendingData(profile.getEssentialExpenses(), profile.getDiscretionaryExpenses(), streams);
    }

    private ProjectionYearDto applyViability(ProjectionYearDto base, SpendingData spending,
                                              int age, int yearsInRetirement, BigDecimal inflationRate) {
        if (spending == null || !base.retired()) {
            return base;
        }

        BigDecimal inflationFactor = yearsInRetirement > 1
                ? BigDecimal.ONE.add(inflationRate).pow(yearsInRetirement - 1)
                : BigDecimal.ONE;

        BigDecimal essential = spending.essentialExpenses().multiply(inflationFactor).setScale(SCALE, ROUNDING);
        BigDecimal discretionary = spending.discretionaryExpenses().multiply(inflationFactor).setScale(SCALE, ROUNDING);

        BigDecimal activeIncome = BigDecimal.ZERO;
        for (var stream : spending.incomeStreams()) {
            if (age >= stream.startAge() && (stream.endAge() == null || age < stream.endAge())) {
                activeIncome = activeIncome.add(stream.annualAmount());
            }
        }

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
                essential, discretionary, activeIncome, netNeed, surplus, discAfterCuts);
    }
}
