package com.wealthview.core.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/mfa/challenge} (cookie path) and
 * {@code POST /api/v1/auth/token/mfa/challenge} (bearer path). Exactly one
 * of {@code totpCode} or {@code recoveryCode} must be supplied; the
 * controller / service rejects requests with neither.
 */
public record MfaChallengeRequest(
        @NotBlank String mfaToken,
        @Size(min = 6, max = 8) String totpCode,
        @Size(min = 8, max = 8) String recoveryCode
) {
    @Override
    public String toString() {
        return "MfaChallengeRequest[mfaToken=***, totpCode=***, recoveryCode=***]";
    }
}
