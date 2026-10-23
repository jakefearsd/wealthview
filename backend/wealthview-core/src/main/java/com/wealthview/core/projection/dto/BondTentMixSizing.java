package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

/**
 * How one mix sizes the tent: its real yield (scale 6), the target in today's dollars, the target as a fraction of
 * the projected portfolio at retirement (scale 6), the rule-of-thumb size it is compared with, and the one reason
 * the size differs from it, with that reason's amount.
 */
public record BondTentMixSizing(
        String name,
        BigDecimal realYield,
        BigDecimal target,
        BigDecimal percentOfPortfolio,
        BigDecimal ruleOfThumb,
        BondTentSizingReason reason,
        BigDecimal reasonAmount) {
}
