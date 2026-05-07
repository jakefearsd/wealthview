package com.wealthview.core.auth.dto;

import java.util.UUID;

/**
 * Response body for login/register/refresh — contains only the user identity.
 *
 * <p>Access and refresh tokens are issued as HttpOnly Secure cookies and are
 * never serialized into a response body, so they cannot be read from JavaScript
 * (mitigates XSS-driven token exfiltration).
 */
public record AuthIdentityResponse(
        UUID userId,
        UUID tenantId,
        String email,
        String role
) {
}
