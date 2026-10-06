package com.wealthview.core.property;

import java.math.BigDecimal;

/**
 * A span of mortgage payments split into the interest part (deductible against rental income)
 * and the principal part (a cash outflow only). Amounts are nominal.
 */
public record DebtService(BigDecimal interest, BigDecimal principal) {

    public static final DebtService NONE = new DebtService(BigDecimal.ZERO, BigDecimal.ZERO);

    public BigDecimal total() {
        return interest.add(principal);
    }
}
