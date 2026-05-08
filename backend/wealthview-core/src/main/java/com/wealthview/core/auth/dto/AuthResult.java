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

    /**
     * Override the record's auto-generated toString to redact both JWTs.
     * AuthResult flows through the controller layer; an accidental log
     * statement on an exception path could otherwise dump the full
     * access and refresh tokens.
     */
    @Override
    public String toString() {
        return "AuthResult[accessToken=***, refreshToken=***, userId=" + userId
                + ", tenantId=" + tenantId + ", email=" + email + ", role=" + role + "]";
    }
}
