package com.wealthview.core.projection.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Size;

public record CompareRequest(
        @Size(min = 2, max = 3) List<UUID> scenarioIds) {
}
