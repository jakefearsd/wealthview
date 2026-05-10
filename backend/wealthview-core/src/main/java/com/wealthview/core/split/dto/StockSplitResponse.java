package com.wealthview.core.split.dto;

import com.wealthview.persistence.entity.StockSplitEntity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API representation of a stock split row.
 */
public record StockSplitResponse(
        UUID id,
        String symbol,
        LocalDate effectiveDate,
        int numerator,
        int denominator,
        String source,
        OffsetDateTime appliedAt,
        String notes) {

    public static StockSplitResponse from(StockSplitEntity e) {
        return new StockSplitResponse(
                e.getId(), e.getSymbol(), e.getEffectiveDate(),
                e.getNumerator(), e.getDenominator(), e.getSource(),
                e.getAppliedAt(), e.getNotes());
    }
}
