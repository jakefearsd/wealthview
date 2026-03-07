package com.wealthview.core.property.dto;

import java.math.BigDecimal;

public record EquityGrowthPoint(
        String month,
        BigDecimal equity,
        BigDecimal propertyValue,
        BigDecimal mortgageBalance
) {
}
