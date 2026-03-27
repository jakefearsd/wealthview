package com.wealthview.core.property.dto;

import java.math.BigDecimal;

public record SellScenarioResult(
        BigDecimal grossProceeds,
        BigDecimal sellingCosts,
        BigDecimal depreciationRecaptureTax,
        BigDecimal capitalGainsTax,
        BigDecimal netProceeds,
        BigDecimal endingNetWorth
) {
}
