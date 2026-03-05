package com.wealthview.core.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateRoleRequest(
        @NotBlank @Pattern(regexp = "admin|member|viewer") String role
) {
}
