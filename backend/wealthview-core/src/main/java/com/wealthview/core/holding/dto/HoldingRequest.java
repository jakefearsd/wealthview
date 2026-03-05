package com.wealthview.core.holding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record HoldingRequest(
        @NotNull UUID accountId,
        @NotBlank String symbol,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal costBasis
) {
}
