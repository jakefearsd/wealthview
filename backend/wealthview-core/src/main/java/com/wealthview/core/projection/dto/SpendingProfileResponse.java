package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wealthview.persistence.entity.SpendingProfileEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
        } catch (JacksonException e) {
            log.warn("Failed to parse spending tiers JSON for profile {}", entity.getId(), e);
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
