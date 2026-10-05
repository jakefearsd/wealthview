package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * End-of-projection (or second-death) pool balances valued for heirs: traditional dollars are taxed at
 * the heirs' assumed ordinary rate (they must empty an inherited IRA within ten years); Roth passes
 * tax-free; the taxable account passes at full value because its cost basis steps up at death.
 * Property equity is deliberately excluded -- it is reported separately as part of net worth.
 */
public record TerminalValue(int year, BigDecimal traditional, BigDecimal roth, BigDecimal taxable,
                            BigDecimal heirTaxRate, BigDecimal afterTaxLegacy, boolean atSecondDeath) {

    public static TerminalValue compute(int year, BigDecimal traditional, BigDecimal roth, BigDecimal taxable,
                                        BigDecimal heirTaxRate, boolean atSecondDeath) {
        BigDecimal afterTax = traditional.multiply(BigDecimal.ONE.subtract(heirTaxRate))
                .add(roth).add(taxable)
                .setScale(SCALE, ROUNDING);
        return new TerminalValue(year, traditional, roth, taxable, heirTaxRate, afterTax, atSecondDeath);
    }
}
