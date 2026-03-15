package com.wealthview.core.projection.dto;

import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;

import java.util.UUID;

public record GuardrailProfileSummary(
        UUID id,
        String name,
        boolean stale,
        boolean active
) {

    public static GuardrailProfileSummary from(GuardrailSpendingProfileEntity entity, boolean active) {
        return new GuardrailProfileSummary(entity.getId(), entity.getName(), entity.isStale(), active);
    }
}
