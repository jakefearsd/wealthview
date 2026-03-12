package com.wealthview.core.projection.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.persistence.entity.SpendingProfileEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SpendingProfileResponse(
        UUID id,
        String name,
        BigDecimal essentialExpenses,
        BigDecimal discretionaryExpenses,
        List<SpendingTierResponse> spendingTiers,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static SpendingProfileResponse from(SpendingProfileEntity entity) {
        List<SpendingTierResponse> tiers = List.of();
        try {
            if (entity.getSpendingTiers() != null && !entity.getSpendingTiers().isBlank()) {
                tiers = MAPPER.readValue(entity.getSpendingTiers(),
                        new TypeReference<List<SpendingTierResponse>>() {});
            }
        } catch (Exception e) {
            // fall through with empty list
        }

        return new SpendingProfileResponse(
                entity.getId(),
                entity.getName(),
                entity.getEssentialExpenses(),
                entity.getDiscretionaryExpenses(),
                tiers,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
