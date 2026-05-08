package com.wealthview.core.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank
        @Size(min = 8, max = 64, message = "Password must be between 8 and 64 characters")
        String password,
        @NotBlank String inviteCode
) {
    /**
     * Override the record's auto-generated toString so an accidental
     * {@code log.info("{}", request)} cannot dump the plaintext password
     * or the single-use invite code.
     */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=***, inviteCode=***]";
    }
}
