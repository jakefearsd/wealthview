package com.wealthview.core.auth.mfa.dto;

import java.util.List;

/**
 * One-shot response for {@code POST /api/v1/auth/mfa/setup}. Includes the
 * raw Base32 TOTP secret, an {@code otpauth://} URI usable to render a QR
 * code, and the freshly minted recovery codes. Recovery codes are returned
 * exactly once — the server stores BCrypt hashes only.
 */
public record MfaSetupResponse(
        String secret,
        String qrCodeUri,
        List<String> recoveryCodes
) {
    @Override
    public String toString() {
        return "MfaSetupResponse[secret=***, qrCodeUri=***, recoveryCodes=*** ("
                + recoveryCodes.size() + ")]";
    }
}
