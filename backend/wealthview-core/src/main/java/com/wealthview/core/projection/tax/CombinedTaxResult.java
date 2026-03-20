package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

public record CombinedTaxResult(
        BigDecimal federalTax,
        BigDecimal stateTax,
        BigDecimal totalTax,
        BigDecimal saltDeduction,
        BigDecimal itemizedDeductions,
        boolean usedItemized) {
}
