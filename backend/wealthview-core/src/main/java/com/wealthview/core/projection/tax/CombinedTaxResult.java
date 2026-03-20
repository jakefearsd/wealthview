package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

public record CombinedTaxResult(
        BigDecimal federalTax,
        BigDecimal stateTax,
        BigDecimal totalTax,
        BigDecimal saltDeduction,
        BigDecimal itemizedDeductions,
        boolean usedItemized) {

    public CombinedTaxResult add(CombinedTaxResult other) {
        return new CombinedTaxResult(
                federalTax.add(other.federalTax),
                stateTax.add(other.stateTax),
                totalTax.add(other.totalTax),
                // SALT and itemized: use the latest non-zero values (these don't accumulate)
                other.saltDeduction.compareTo(BigDecimal.ZERO) > 0 ? other.saltDeduction : saltDeduction,
                other.itemizedDeductions.compareTo(BigDecimal.ZERO) > 0 ? other.itemizedDeductions : itemizedDeductions,
                usedItemized || other.usedItemized);
    }

    public static final CombinedTaxResult ZERO = new CombinedTaxResult(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, false);
}
