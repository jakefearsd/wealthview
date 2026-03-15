package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record ResolvedYearSpending(
        BigDecimal portfolioWithdrawal,
        BigDecimal totalSpending
) {}
