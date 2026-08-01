package com.wealthview.core.notification.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record NotificationPreferenceRequest(
        // @Valid is required for the element constraints below to run at all: Bean Validation
        // does NOT cascade into a collection's elements without it. Without the cascade a null
        // "enabled" reached NotificationPreferenceService and auto-unboxed into the entity's
        // primitive boolean, surfacing a malformed request as a 500 instead of a 400.
        @NotNull @Valid List<PreferenceItem> preferences
) {
    public record PreferenceItem(
            @NotNull String notificationType,
            @NotNull Boolean enabled
    ) {}
}
