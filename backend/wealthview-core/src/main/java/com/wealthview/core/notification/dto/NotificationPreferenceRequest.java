package com.wealthview.core.notification.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

public record NotificationPreferenceRequest(
        @NotNull List<PreferenceItem> preferences
) {
    public record PreferenceItem(
            @NotNull String notificationType,
            @NotNull Boolean enabled
    ) {}
}
