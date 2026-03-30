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
        boolean stale = entity.isStale() || GuardrailProfileResponse.isOlderThan24Hours(entity);
        return new GuardrailProfileSummary(entity.getId(), entity.getName(), stale, active);
    }
}
