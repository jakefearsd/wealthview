package com.wealthview.core.auth.dto;

import com.wealthview.persistence.entity.UserSessionEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code GET /api/v1/auth/sessions}. Marks {@code current=true}
 * when the session id matches the caller's access-token sid claim so the UI
 * can highlight and protect the active session.
 */
public record SessionResponse(
        UUID id,
        String deviceLabel,
        String transport,
        String ipAddress,
        String userAgent,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt,
        boolean current
) {
    public static SessionResponse from(UserSessionEntity s, UUID currentSessionId) {
        return new SessionResponse(
                s.getId(),
                s.getDeviceLabel(),
                s.getTransport(),
                s.getIpAddress(),
                s.getUserAgent(),
                s.getCreatedAt(),
                s.getLastUsedAt(),
                currentSessionId != null && currentSessionId.equals(s.getId())
        );
    }
}
