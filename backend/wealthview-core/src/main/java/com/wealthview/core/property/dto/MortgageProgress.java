package com.wealthview.core.property.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MortgageProgress(
        BigDecimal originalLoanAmount,
        BigDecimal currentBalance,
        BigDecimal principalPaid,
        BigDecimal percentPaidOff,
        LocalDate estimatedPayoffDate,
        int monthsRemaining
) {
}
