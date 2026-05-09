package com.wealthview.core.auth.mfa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaCodeRequest(
        @NotBlank @Size(min = 6, max = 8) String totpCode
) {
}
