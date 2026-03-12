package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateScenarioRequest(
        String name,
        LocalDate retirementDate,
        Integer endAge,
        BigDecimal inflationRate,
        Integer birthYear,
        BigDecimal withdrawalRate,
        String withdrawalStrategy,
        BigDecimal dynamicCeiling,
        BigDecimal dynamicFloor,
        String filingStatus,
        BigDecimal otherIncome,
        BigDecimal annualRothConversion,
        String withdrawalOrder,
        String rothConversionStrategy,
        BigDecimal targetBracketRate,
        Integer rothConversionStartYear,
        List<CreateProjectionAccountRequest> accounts,
        UUID spendingProfileId,
        List<ScenarioIncomeSourceInput> incomeSources) {
}
