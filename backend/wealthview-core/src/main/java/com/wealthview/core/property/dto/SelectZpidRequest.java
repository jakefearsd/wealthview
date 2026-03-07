package com.wealthview.core.property.dto;

import jakarta.validation.constraints.NotBlank;

public record SelectZpidRequest(
        @NotBlank String zpid
) {
}
