package com.wealthview.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.projection.ProjectionEngine;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class DeterministicProjectionEngine implements ProjectionEngine {

    private static final Logger log = LoggerFactory.getLogger(DeterministicProjectionEngine.class);
    private static final BigDecimal DEFAULT_WITHDRAWAL_RATE = new BigDecimal("0.04");
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ProjectionResultResponse run(ProjectionScenarioEntity scenario) {
        var accounts = scenario.getAccounts();
        var params = parseParams(scenario.getParamsJson());

        int currentYear = LocalDate.now().getYear();
        int birthYear = params.birthYear != null ? params.birthYear : currentYear - 35;
        int currentAge = currentYear - birthYear;
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

        BigDecimal balance = accounts.stream()
                .map(ProjectionAccountEntity::getInitialBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalContributions = accounts.stream()
                .map(ProjectionAccountEntity::getAnnualContribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal weightedReturn = computeWeightedReturn(accounts, balance);

        // Base withdrawal = withdrawalRate * initial balance at retirement
        // We'll compute the initial retirement balance when we reach retirement
        BigDecimal baseWithdrawal = null;

        var yearlyData = new ArrayList<ProjectionYearDto>();
        int yearsInRetirement = 0;

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
                if (baseWithdrawal == null) {
                    baseWithdrawal = startBalance.multiply(withdrawalRate).setScale(SCALE, ROUNDING);
                }
                // Inflation-adjust: baseWithdrawal * (1 + inflation)^(yearsInRetirement - 1)
                BigDecimal inflationMultiplier = BigDecimal.ONE.add(inflationRate)
                        .pow(yearsInRetirement - 1);
                withdrawals = baseWithdrawal.multiply(inflationMultiplier).setScale(SCALE, ROUNDING);

                if (withdrawals.compareTo(balance) > 0) {
                    withdrawals = balance;
                }
                balance = balance.subtract(withdrawals);
            }

            if (balance.compareTo(BigDecimal.ZERO) < 0) {
                balance = BigDecimal.ZERO;
            }

            yearlyData.add(new ProjectionYearDto(
                    year, age, startBalance, contributions, growth, withdrawals, balance, retired));
        }

        BigDecimal finalBalance = yearlyData.isEmpty()
                ? balance
                : yearlyData.getLast().endBalance();

        log.info("Projection for scenario '{}': {} years, final balance {}",
                scenario.getName(), yearlyData.size(), finalBalance);

        return new ProjectionResultResponse(scenario.getId(), yearlyData, finalBalance, yearsInRetirement);
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
            return new ScenarioParams(null, null);
        }
        try {
            JsonNode node = objectMapper.readTree(paramsJson);
            Integer birthYear = node.has("birth_year") ? node.get("birth_year").asInt() : null;
            BigDecimal withdrawalRate = node.has("withdrawal_rate")
                    ? new BigDecimal(node.get("withdrawal_rate").asText())
                    : null;
            return new ScenarioParams(birthYear, withdrawalRate);
        } catch (Exception e) {
            log.warn("Failed to parse params_json: {}", e.getMessage());
            return new ScenarioParams(null, null);
        }
    }

    private record ScenarioParams(Integer birthYear, BigDecimal withdrawalRate) {
    }
}
