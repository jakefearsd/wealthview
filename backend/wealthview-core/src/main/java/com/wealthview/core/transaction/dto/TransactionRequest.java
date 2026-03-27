package com.wealthview.core.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionRequest(
        @NotNull LocalDate date,
        @NotBlank @Pattern(regexp = "buy|sell|dividend|deposit|withdrawal|opening_balance") String type,
        String symbol,
        @DecimalMin("0") BigDecimal quantity,
        @NotNull BigDecimal amount
) {
}
