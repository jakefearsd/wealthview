package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record ProjectionIncomeSourceInput(
        UUID id,
        String name,
        String incomeType,
        BigDecimal annualAmount,
        int startAge,
        Integer endAge,
        BigDecimal inflationRate,
        boolean oneTime,
        String taxTreatment,
        BigDecimal annualOperatingExpenses,
        BigDecimal annualMortgageInterest,
        BigDecimal annualPropertyTax,
        String depreciationMethod,
        Map<Integer, BigDecimal> depreciationByYear
) {}
