package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
        List<CreateProjectionAccountRequest> accounts) {
}
