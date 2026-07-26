package com.wealthview.core.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import com.wealthview.persistence.entity.TransactionType;

/**
 * Create/update payload for a transaction.
 *
 * <p>{@code type} is the {@link TransactionType} enum rather than a {@code String} with a
 * {@code @Pattern} regex duplicating the closed set: Jackson parses the same lowercase wire
 * tokens via the enum's {@code @JsonCreator}, and an unknown token fails deserialisation —
 * surfaced as 400 by {@code GlobalExceptionHandler}'s {@code HttpMessageNotReadableException}
 * handler instead of as a Bean Validation error. Same status, different message text.
 */
public record TransactionRequest(
        @NotNull LocalDate date,
        @NotNull TransactionType type,
        String symbol,
        @DecimalMin("0") BigDecimal quantity,
        @NotNull BigDecimal amount
) {
}
