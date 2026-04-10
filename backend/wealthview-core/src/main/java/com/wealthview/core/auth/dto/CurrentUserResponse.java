package com.wealthview.core.auth.dto;

import java.util.UUID;

public record CurrentUserResponse(
        UUID userId,
        UUID tenantId,
        String email,
        String role
) {
}
