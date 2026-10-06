package com.wealthview.core.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AccountRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Pattern(regexp = "brokerage|ira|401k|roth|bank") String type,
        @Size(max = 200) String institution,
        @Pattern(regexp = "[A-Z]{3}") String currency
) {
}
