package com.wealthview.core.property.dto;

import java.math.BigDecimal;

public record HoldScenarioResult(
        BigDecimal endingPropertyValue,
        BigDecimal endingMortgageBalance,
        BigDecimal cumulativeNetCashFlow,
        BigDecimal endingNetWorth
) {
}
