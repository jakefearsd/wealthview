package com.wealthview.core.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank
        @Size(min = 12, max = 64, message = "Password must be between 12 and 64 characters")
        String newPassword
) {
    /**
     * Override the record's auto-generated toString so the plaintext
     * password cannot leak via an accidental {@code log.info("{}", request)}.
     */
    @Override
    public String toString() {
        return "PasswordResetRequest[newPassword=***]";
    }
}
