package com.wealthview.core.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionRequest(
        @NotNull LocalDate date,
        @NotBlank @Pattern(regexp = "buy|sell|dividend|deposit|withdrawal") String type,
        String symbol,
        BigDecimal quantity,
        @NotNull BigDecimal amount
) {
}
