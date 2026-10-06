package com.wealthview.core.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
        @Size(max = 32) String symbol,
        @DecimalMin("0") BigDecimal quantity,
        @NotNull @DecimalMin("0") BigDecimal amount
) {

    /**
     * A buy or sell of zero (or an unspecified number of) shares is meaningless and would corrupt
     * holdings math. Other types (dividend, deposit, fee...) legitimately carry no quantity.
     */
    @JsonIgnore
    @AssertTrue(message = "quantity must be greater than 0 for buy and sell transactions")
    public boolean isQuantityValidForType() {
        var needsQuantity = type == TransactionType.BUY || type == TransactionType.SELL;
        return !needsQuantity || quantity != null && quantity.signum() > 0;
    }
}
