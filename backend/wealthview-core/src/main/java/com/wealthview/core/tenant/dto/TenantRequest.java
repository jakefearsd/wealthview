package com.wealthview.core.tenant.dto;

import jakarta.validation.constraints.NotBlank;

public record TenantRequest(
        @NotBlank String name
) {
}
