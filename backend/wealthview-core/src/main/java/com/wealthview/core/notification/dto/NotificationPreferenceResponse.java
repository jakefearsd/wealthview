package com.wealthview.core.notification.dto;

public record NotificationPreferenceResponse(
        String notificationType,
        boolean enabled
) {}
