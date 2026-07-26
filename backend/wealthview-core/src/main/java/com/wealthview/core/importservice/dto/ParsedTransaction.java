package com.wealthview.core.importservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.wealthview.persistence.entity.TransactionType;

public record ParsedTransaction(
        LocalDate date,
        TransactionType type,
        String symbol,
        BigDecimal quantity,
        BigDecimal amount
) {
}
