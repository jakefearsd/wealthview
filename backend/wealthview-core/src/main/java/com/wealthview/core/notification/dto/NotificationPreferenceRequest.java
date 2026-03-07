package com.wealthview.core.notification.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record NotificationPreferenceRequest(
        @NotNull List<PreferenceItem> preferences
) {
    public record PreferenceItem(
            @NotNull String notificationType,
            @NotNull Boolean enabled
    ) {}
}
