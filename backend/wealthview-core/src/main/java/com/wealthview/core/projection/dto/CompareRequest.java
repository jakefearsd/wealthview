package com.wealthview.core.projection.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CompareRequest(
        @Size(min = 2, max = 3) List<UUID> scenarioIds) {
}
