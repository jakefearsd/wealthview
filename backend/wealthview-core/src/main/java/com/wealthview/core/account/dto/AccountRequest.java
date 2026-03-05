package com.wealthview.core.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AccountRequest(
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "brokerage|ira|401k|roth|bank") String type,
        String institution
) {
}
