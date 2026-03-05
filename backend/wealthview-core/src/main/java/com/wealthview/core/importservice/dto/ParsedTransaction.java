package com.wealthview.core.importservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParsedTransaction(
        LocalDate date,
        String type,
        String symbol,
        BigDecimal quantity,
        BigDecimal amount
) {
}
