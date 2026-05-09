package com.wealthview.core.auth.dto;

import java.util.UUID;

/**
 * Response body for the mobile/Bearer auth endpoints under
 * {@code /api/v1/auth/token/**}.
 *
 * <p>Unlike {@link AuthIdentityResponse} (which travels alongside HttpOnly
 * cookies for the web client), this record returns the access and refresh
 * tokens in the response body. It is intended for native clients that
 * persist tokens in OS-managed secure storage (iOS Keychain or Android
 * Keystore-backed {@code EncryptedSharedPreferences}). Storage hygiene is
 * the client's responsibility.
 *
 * <p>Jackson is configured globally with
 * {@code PropertyNamingStrategies.SNAKE_CASE}, so these fields serialize as
 * {@code access_token}, {@code refresh_token}, {@code user_id}, etc.
 */
public record MobileAuthResponse(
        String accessToken,
        String refreshToken,
        UUID userId,
        UUID tenantId,
        String email,
        String role
) {
    public static MobileAuthResponse from(AuthResult result) {
        return new MobileAuthResponse(
                result.accessToken(),
                result.refreshToken(),
                result.userId(),
                result.tenantId(),
                result.email(),
                result.role());
    }

    /**
     * Override the record's auto-generated toString to redact both JWTs.
     * MobileAuthResponse flows through the controller layer; an accidental
     * log statement on an exception path could otherwise dump the full
     * access and refresh tokens.
     */
    @Override
    public String toString() {
        return "MobileAuthResponse[accessToken=***, refreshToken=***, userId=" + userId
                + ", tenantId=" + tenantId + ", email=" + email + ", role=" + role + "]";
    }
}
