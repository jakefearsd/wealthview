package com.wealthview.core.importservice.dto;

public record CsvRowError(
        int rowNumber,
        String message
) {
}
