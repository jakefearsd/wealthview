package com.wealthview.core.auth.mfa.dto;

import java.time.OffsetDateTime;

public record MfaStatusResponse(
        boolean enabled,
        OffsetDateTime setupAt,
        long recoveryCodesRemaining
) {
}
