package com.wealthview.core.income.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateIncomeSourceRequest(
        String name,
        String incomeType,
        BigDecimal annualAmount,
        int startAge,
        Integer endAge,
        BigDecimal inflationRate,
        Boolean oneTime,
        String taxTreatment,
        UUID propertyId
) {}
