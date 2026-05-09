package com.wealthview.core.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/token/refresh}. The cookie auth
 * path reads the refresh token from a cookie; the mobile path expects the
 * client to send it in the body since native clients have no cookie jar.
 */
public record MobileRefreshRequest(
        @NotBlank String refreshToken
) {
    /**
     * Override the record's auto-generated toString so an accidental
     * {@code log.info("{}", request)} cannot dump the refresh token.
     */
    @Override
    public String toString() {
        return "MobileRefreshRequest[refreshToken=***]";
    }
}
