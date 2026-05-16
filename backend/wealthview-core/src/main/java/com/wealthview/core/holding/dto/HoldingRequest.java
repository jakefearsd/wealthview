package com.wealthview.core.holding.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record HoldingRequest(
        @NotNull UUID accountId,
        @NotBlank String symbol,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal costBasis
) {
}
