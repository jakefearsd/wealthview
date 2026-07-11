package com.wealthview.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ClassificationRequest(@NotBlank String assetClass) {
}
