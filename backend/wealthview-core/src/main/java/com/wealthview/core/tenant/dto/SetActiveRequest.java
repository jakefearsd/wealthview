package com.wealthview.core.tenant.dto;

import jakarta.validation.constraints.NotNull;

public record SetActiveRequest(
        @NotNull Boolean active
) {
}
