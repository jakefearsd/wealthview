package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ScenarioIncomeSourceResponse(
        UUID incomeSourceId,
        String name,
        String incomeType,
        BigDecimal annualAmount,
        BigDecimal overrideAnnualAmount,
        BigDecimal effectiveAmount,
        BigDecimal annualNetCashFlow,
        int startAge,
        Integer endAge,
        BigDecimal inflationRate,
        boolean oneTime) {
}
