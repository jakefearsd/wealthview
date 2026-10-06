package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

public record CreateSpendingProfileRequest(
        @NotBlank String name,
        @DecimalMin("0") BigDecimal essentialExpenses,
        @DecimalMin("0") BigDecimal discretionaryExpenses,
        List<@Valid SpendingTierRequest> spendingTiers) {
}
