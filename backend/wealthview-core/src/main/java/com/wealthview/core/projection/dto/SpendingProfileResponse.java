package com.wealthview.core.projection.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.persistence.entity.SpendingProfileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(SpendingProfileResponse.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static SpendingProfileResponse from(SpendingProfileEntity entity) {
        List<SpendingTierResponse> tiers = List.of();
        try {
            if (entity.getSpendingTiers() != null && !entity.getSpendingTiers().isBlank()) {
                tiers = MAPPER.readValue(entity.getSpendingTiers(),
                        new TypeReference<>() {});
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse spending tiers JSON: {}", e.getMessage());
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
