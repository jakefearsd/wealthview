package com.wealthview.core.holding.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record HoldingRequest(
        @NotNull UUID accountId,
        @NotBlank @Size(max = 32) String symbol,
        @NotNull @DecimalMin("0") BigDecimal quantity,
        @NotNull @DecimalMin("0") BigDecimal costBasis
) {
}
