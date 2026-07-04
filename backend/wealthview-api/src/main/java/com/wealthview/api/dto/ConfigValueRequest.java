package com.wealthview.api.dto;

import jakarta.validation.constraints.NotNull;

public record ConfigValueRequest(
        @NotNull String value
) {
}
