package com.wealthview.api.dto;

public record ErrorResponse(
        String error,
        String message,
        int status
) {
}
