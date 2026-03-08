package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record IncomeStreamRequest(
        String name,
        BigDecimal annualAmount,
        int startAge,
        Integer endAge,
        BigDecimal inflationRate,
        Boolean oneTime) {
}
