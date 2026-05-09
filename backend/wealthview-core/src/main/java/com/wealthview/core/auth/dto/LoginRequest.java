package com.wealthview.core.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @Size(max = 64) String deviceLabel
) {
    public LoginRequest(String email, String password) {
        this(email, password, null);
    }

    /**
     * Override the record's auto-generated toString so an accidental
     * {@code log.info("{}", request)} cannot dump the plaintext password.
     * The email is shown unmasked because it's already logged at INFO on
     * successful login by AuthService.
     */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***, deviceLabel=" + deviceLabel + "]";
    }
}
