package com.wealthview.core.auth.dto;

import java.util.UUID;

/**
 * Service-layer result of an authentication operation.
 *
 * <p>The controller splits this into a cookie pair (access + refresh tokens) and
 * an {@link AuthIdentityResponse} body. The raw tokens never leave the cookie
 * boundary.
 */
public record AuthResult(
        String accessToken,
        String refreshToken,
        UUID userId,
        UUID tenantId,
        String email,
        String role
) {
    public AuthIdentityResponse identity() {
        return new AuthIdentityResponse(userId, tenantId, email, role);
    }
}
