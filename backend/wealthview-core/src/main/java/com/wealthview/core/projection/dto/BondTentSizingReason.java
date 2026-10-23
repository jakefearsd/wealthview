package com.wealthview.core.projection.dto;

/**
 * The additive component that explains why a bond tent's size differs from the rule of thumb
 * (coverage years times 4% of the projected portfolio). The declaration order is the tie-break order.
 */
public enum BondTentSizingReason {
    SPENDING_LEVEL,
    INCOME,
    WITHDRAWAL_TAX,
    MIX_YIELDS
}
